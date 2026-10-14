package com.github.musicyou.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateValidationTest {
    @Test fun semanticVersionsFollowReleasePrecedence() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "2.0.0")
        versions.zipWithNext().forEach { (left, right) -> assertTrue("$left < $right", UpdateVersion.parse(left) < UpdateVersion.parse(right)) }
        assertEquals(0, UpdateVersion.parse("1.0.0+build.1").compareTo(UpdateVersion.parse("1.0.0+build.2")))
        assertTrue(UpdateVersion.parse("2.0.0-999999999999999999999") < UpdateVersion.parse("2.0.0-next"))
    }

    @Test fun updatesRequireVersionCodeAndVersionNameToAdvance() {
        assertTrue(shouldOfferUpdate("2.0.0", 2, "1.9.9", 1))
        assertFalse(shouldOfferUpdate("2.0.0", 1, "1.0.0", 1))
        assertFalse(shouldOfferUpdate("1.0.0", 2, "1.0.0", 1))
        assertFalse(shouldOfferUpdate("1.9.9", 3, "2.0.0", 2))
    }

    @Test fun invalidVersionStringsAreRejected() {
        listOf("1.0", "01.0.0", "1.0.0-01", "1.0.0+", "1.0.0-", "1.0.0 extra").forEach { value ->
            assertThrows(Exception::class.java) { UpdateVersion.parse(value) }
        }
    }

    @Test fun incompleteAndMalformedManifestsAreRejected() {
        assertThrows(Exception::class.java) { UpdateRelease.parse("not JSON", PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse("{}", PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":2"), PACKAGE) }
    }

    @Test fun checksumAndPackageIdentityAreRequired() {
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("a".repeat(64), "0".repeat(64)), PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace(PACKAGE, "net.other.app"), PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("\"apkBytes\":1024", "\"apkBytes\":0"), PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("\"versionCode\":2", "\"versionCode\":0"), PACKAGE) }
    }

    @Test fun downloadsCannotUseAnotherRepositoryOrPlainHttp() {
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("https://github.com", "http://github.com"), PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("Our-Music-Mobile/releases/download", "Other-App/releases/download"), PACKAGE) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().replace("github.com/unreliablecode/Our-Music-Mobile/releases/download", "github.com.evil.example/unreliablecode/Our-Music-Mobile/releases/download"), PACKAGE) }
        assertEquals("2.0.0", UpdateRelease.parse(manifest(), PACKAGE).versionName)
    }

    @Test fun modifiedOrTruncatedDownloadsNeverPassChecksumVerification() {
        val file = File.createTempFile("ourmusic", ".apk")
        try {
            file.writeText("abc")
            val checksum = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            verifyDownloadedApk(file, 3, checksum)
            file.writeText("abd")
            assertThrows(Exception::class.java) { verifyDownloadedApk(file, 3, checksum) }
            file.writeText("ab")
            assertThrows(Exception::class.java) { verifyDownloadedApk(file, 3, checksum) }
        } finally { file.delete() }
    }

    private fun manifest() = """{"schemaVersion":1,"packageName":"$PACKAGE","versionName":"2.0.0","versionCode":2,"apkUrl":"https://github.com/unreliablecode/Our-Music-Mobile/releases/download/v2.0.0/OurMusic.apk","sha256":"${"a".repeat(64)}","apkBytes":1024,"minSdk":23,"publishedAt":"2026-10-14","changelog":"New controls","releaseUrl":"https://github.com/unreliablecode/Our-Music-Mobile/releases/tag/v2.0.0"}"""
    companion object { const val PACKAGE = "net.omusic.unreliablecode" }
}
