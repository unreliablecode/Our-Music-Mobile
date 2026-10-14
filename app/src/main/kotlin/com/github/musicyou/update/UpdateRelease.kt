package com.github.musicyou.update

import org.json.JSONObject
import java.net.URI

data class UpdateRelease(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
    val apkBytes: Long,
    val minSdk: Int,
    val publishedAt: String,
    val changelog: String,
    val releaseUrl: String
) {
    companion object {
        fun parse(text: String, expectedPackage: String): UpdateRelease {
            require(text.length <= 512 * 1024) { "Release manifest is too large" }
            val json = JSONObject(text)
            listOf("schemaVersion", "versionCode", "apkBytes", "minSdk").forEach { name ->
                val value = json.get(name)
                require(value is Number && Regex("[0-9]+").matches(value.toString())) { "Invalid numeric release field" }
            }
            require(json.getInt("schemaVersion") == 1) { "Unsupported release manifest" }
            val release = UpdateRelease(
                json.getString("versionName"), json.getLong("versionCode"), json.getString("packageName"),
                json.getString("apkUrl"), json.getString("sha256"), json.getLong("apkBytes"),
                json.getInt("minSdk"), json.optString("publishedAt"), json.optString("changelog"),
                json.getString("releaseUrl")
            )
            UpdateVersion.parse(release.versionName)
            require(release.packageName == expectedPackage) { "Release belongs to another app" }
            require(release.versionCode > 0 && release.apkBytes in 1..512L * 1024 * 1024) { "Invalid release size or version" }
            require(release.minSdk >= 23) { "Invalid Android version requirement" }
            requireValidChecksum(release.sha256)
            val apkUri = URI(release.apkUrl)
            require(apkUri.scheme == "https" && apkUri.host == "github.com" && apkUri.userInfo == null &&
                apkUri.port == -1 && apkUri.query == null && apkUri.fragment == null &&
                apkUri.path.startsWith("/unreliablecode/Our-Music-Mobile/releases/download/v${release.versionName}/") && apkUri.path.endsWith(".apk")) { "Invalid release download address" }
            require(release.releaseUrl == "https://github.com/unreliablecode/Our-Music-Mobile/releases/tag/v${release.versionName}") { "Invalid release address" }
            return release
        }
    }
}
