package com.github.musicyou.update

import java.io.File
import java.security.MessageDigest

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifyDownloadedApk(file: File, expectedBytes: Long, expectedChecksum: String) {
    require(file.isFile && file.length() == expectedBytes && sha256(file).equals(expectedChecksum, true)) {
        "APK checksum verification failed"
    }
}
