package com.github.musicyou.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.github.musicyou.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, AwaitingPermission, Installing, Error }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: UpdateRelease? = null,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val automaticChecks: Boolean = true,
    val checkedAt: Long = 0,
    val message: String? = null
) {
    val hasAvailableUpdate: Boolean get() = updateAvailable
    val updateAvailable: Boolean get() = release?.let {
        shouldOfferUpdate(it.versionName, it.versionCode, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    } == true
}

class UpdateCoordinator private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("our_music_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checkMutex = Mutex()
    private var downloadJob: Job? = null
    private val cachedRelease = runCatching {
        preferences.getString("release_manifest", null)?.let { UpdateRelease.parse(it, context.packageName) }
    }.getOrNull()
    private val mutableState = MutableStateFlow(UpdateState(
        phase = if (cachedRelease?.let { shouldOfferUpdate(it.versionName, it.versionCode, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()) } == true) UpdatePhase.Available
            else if (preferences.getLong("last_checked", 0) > 0) UpdatePhase.Current else UpdatePhase.Idle,
        release = cachedRelease,
        automaticChecks = preferences.getBoolean("automatic_checks", true),
        checkedAt = preferences.getLong("last_checked", 0)
    ))
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val updateDirectory get() = File(context.cacheDir, "updates").also { it.mkdirs() }

    fun setAutomaticChecks(enabled: Boolean) {
        preferences.edit().putBoolean("automatic_checks", enabled).apply()
        mutableState.update { it.copy(automaticChecks = enabled) }
    }

    suspend fun checkAutomatically() {
        if (state.value.automaticChecks && System.currentTimeMillis() - state.value.checkedAt >= 6 * 60 * 60 * 1000L) checkRelease()
    }

    fun checkNow() { scope.launch { checkRelease() } }

    private suspend fun checkRelease() = checkMutex.withLock {
        if (downloadJob?.isCompleted == false || state.value.phase == UpdatePhase.Installing) return@withLock
        mutableState.update { it.copy(phase = UpdatePhase.Checking, message = null) }
        try {
            val release = withContext(Dispatchers.IO) {
                val connection = openHttps(BuildConfig.UPDATE_MANIFEST_URL)
                try {
                    if (connection.responseCode == 404) {
                        preferences.edit().remove("release_manifest").apply()
                        return@withContext null
                    }
                    requireHttpSuccess(connection)
                    val text = connection.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        val result = StringBuilder()
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            result.append(buffer, 0, count)
                            require(result.length <= 512 * 1024) { "Release manifest is too large" }
                        }
                        result.toString()
                    }
                    UpdateRelease.parse(text, context.packageName).also { release ->
                        if (shouldOfferUpdate(release.versionName, release.versionCode, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) {
                            val asset = openHttps(release.apkUrl, method = "HEAD")
                            try {
                                requireHttpSuccess(asset)
                                val declaredSize = asset.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                                require(declaredSize <= 0 || declaredSize == release.apkBytes) { "Published APK size does not match the release" }
                            } finally { asset.disconnect() }
                        }
                        preferences.edit().putString("release_manifest", text).apply()
                    }
                } finally { connection.disconnect() }
            }
            val checkedAt = System.currentTimeMillis()
            preferences.edit().putLong("last_checked", checkedAt).apply()
            val newer = release?.let {
                shouldOfferUpdate(it.versionName, it.versionCode, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
            } == true
            mutableState.update { it.copy(
                phase = if (newer) UpdatePhase.Available else UpdatePhase.Current,
                release = release, checkedAt = checkedAt,
                message = if (release == null) "The first release has not been published yet." else null
            ) }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            mutableState.update { it.copy(phase = UpdatePhase.Error, message = exception.message ?: "Could not check for updates") }
        }
    }

    fun cancelDownload() {
        val job = downloadJob ?: return
        job.invokeOnCompletion {
            mutableState.update { it.copy(phase = UpdatePhase.Available, message = "Download paused. Tap Download to resume.") }
        }
        job.cancel()
    }

    fun downloadAndInstall() {
        if (downloadJob?.isCompleted == false) return
        val release = state.value.release ?: return
        if (!state.value.updateAvailable) return
        mutableState.update { it.copy(phase = UpdatePhase.Downloading, message = null) }
        downloadJob = scope.launch {
            try {
                require(Build.VERSION.SDK_INT >= release.minSdk) { "This update requires a newer Android version" }
                val apk = withContext(Dispatchers.IO) { downloadApk(release) }
                withContext(Dispatchers.IO) { validateApk(apk, release) }
                mutableState.update { it.copy(phase = UpdatePhase.Ready, progress = 1f, message = null) }
                installReadyUpdate()
            } catch (exception: Exception) {
                if (exception is CancellationException) return@launch
                mutableState.update { it.copy(phase = UpdatePhase.Error, message = exception.message ?: "Could not download the update") }
            }
        }
    }

    private suspend fun downloadApk(release: UpdateRelease): File {
        val destination = File(updateDirectory, "update-${release.versionCode}.apk")
        if (destination.isFile && sha256(destination).equals(release.sha256, true)) return destination
        val partial = File(updateDirectory, "update-${release.versionCode}.part")
        val identity = File(updateDirectory, "update-${release.versionCode}.sha256")
        if (!identity.isFile || identity.readText() != release.sha256 || partial.length() > release.apkBytes) {
            partial.delete()
            identity.writeText(release.sha256)
        }
        var offset = partial.length()
        if (offset == release.apkBytes) {
            if (sha256(partial).equals(release.sha256, true)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
                return destination
            }
            partial.delete()
            offset = 0
        }
        val connection = openHttps(release.apkUrl, offset)
        try {
            requireHttpSuccess(connection)
            val append = offset > 0 && connection.responseCode == 206 &&
                connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
            if (!append) offset = 0
            mutableState.update { it.copy(phase = UpdatePhase.Downloading, downloadedBytes = offset, progress = offset.toFloat() / release.apkBytes, message = null) }
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(65536)
                    var lastProgressAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        offset += count
                        require(offset <= release.apkBytes) { "APK exceeds the declared download size" }
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 100) {
                            mutableState.update { it.copy(downloadedBytes = offset, progress = offset.toFloat() / release.apkBytes) }
                            lastProgressAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
        require(partial.length() == release.apkBytes) { "Download interrupted. Tap Download to resume." }
        if (!sha256(partial).equals(release.sha256, true)) {
            partial.delete()
            error("APK checksum verification failed")
        }
        partial.copyTo(destination, overwrite = true)
        partial.delete()
        return destination
    }

    private fun validateApk(apk: File, release: UpdateRelease) {
        verifyDownloadedApk(apk, release.apkBytes, release.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("Downloaded file is not an Android package")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName && candidate.versionName == release.versionName && versionCode(candidate) == release.versionCode) { "APK identity or version does not match the release" }
        require(signatures(candidate) == signatures(installed)) { "APK signing certificate does not match this installation" }
    }

    fun installReadyUpdate() {
        val release = state.value.release ?: return
        val apk = File(updateDirectory, "update-${release.versionCode}.apk")
        if (!apk.isFile) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            mutableState.update { it.copy(phase = UpdatePhase.AwaitingPermission, message = "Allow Our Music to install updates, then return here and tap Install.") }
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    validateApk(apk, release)
                    val installer = context.packageManager.packageInstaller
                    val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(context.packageName)
                        setSize(apk.length())
                        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    val sessionId = installer.createSession(parameters)
                    installer.openSession(sessionId).use { session ->
                        try {
                            session.openWrite("base.apk", 0, apk.length()).use { output ->
                                apk.inputStream().use { it.copyTo(output) }
                                session.fsync(output)
                            }
                            val callback = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_STATUS")
                            val pending = PendingIntent.getBroadcast(context, sessionId, callback,
                                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                            session.commit(pending.intentSender)
                        } catch (exception: Exception) { session.abandon(); throw exception }
                    }
                }
                mutableState.update { it.copy(phase = UpdatePhase.Installing, message = "Confirm the update in Android's installer.") }
            } catch (exception: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.Error, message = exception.message ?: "Could not open the installer") }
            }
        }
    }

    internal fun installationResult(success: Boolean, message: String?) {
        mutableState.update { it.copy(phase = if (success) UpdatePhase.Current else UpdatePhase.Error, message = message) }
    }

    companion object {
        @Volatile private var instance: UpdateCoordinator? = null
        fun get(context: Context): UpdateCoordinator = instance ?: synchronized(this) {
            instance ?: UpdateCoordinator(context.applicationContext).also { instance = it }
        }
        private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        private fun signatures(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet().orEmpty().also { require(it.isNotEmpty()) { "APK has no signing certificate" } }
        }
        private fun requireHttpSuccess(connection: HttpURLConnection) {
            if (connection.responseCode !in 200..299) throw IOException("Download server returned ${connection.responseCode}")
        }
        private fun openHttps(address: String, offset: Long = 0, method: String = "GET"): HttpURLConnection {
            var url = URL(address)
            repeat(6) {
                require(url.protocol == "https") { "Update addresses must use HTTPS" }
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "OurMusic/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Cache-Control", "no-cache")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing download redirect")
                    val next = URL(url, location)
                    connection.disconnect()
                    url = next
                } else return connection
            }
            throw IOException("Too many download redirects")
        }
    }
}

@Composable
fun rememberUpdateCoordinator(): UpdateCoordinator {
    val context = LocalContext.current.applicationContext
    return remember(context) { UpdateCoordinator.get(context) }
}
