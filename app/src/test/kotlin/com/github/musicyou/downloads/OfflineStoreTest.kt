package com.github.musicyou.downloads

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OfflineStoreTest {
    private fun withStore(test: (OfflineStore) -> Unit) {
        val directory = Files.createTempDirectory("offline-store-").toFile()
        try { test(OfflineStore(directory)) } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteTransferCannotBeMarkedDownloaded() = withStore { store ->
        store.upsert(OfflineDownload("song", "Song", status = DownloadStatus.DOWNLOADING))
        store.partialFile("song").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IllegalStateException::class.java) { store.complete("song", 10, sha256(store.partialFile("song"))) }
        assertEquals(DownloadStatus.DOWNLOADING, store.record("song")?.status)
        assertNull(store.completedFile("song"))
        assertFalse(store.audioFile("song").exists())
    }

    @Test fun checksumMustPassBeforeFinalRename() = withStore { store ->
        store.upsert(OfflineDownload("song", "Song", status = DownloadStatus.DOWNLOADING))
        store.partialFile("song").writeText("complete audio fixture")
        assertThrows(IllegalStateException::class.java) { store.complete("song", store.partialFile("song").length(), "wrong digest") }
        assertFalse(store.audioFile("song").exists())
        assertNotEquals(DownloadStatus.COMPLETE, store.record("song")?.status)
    }

    @Test fun completedFileAndMetadataSurviveRestartAndMissingFileIsRejected() = withStore { store ->
        store.upsert(OfflineDownload("song", "Song", artist = "Artist", status = DownloadStatus.DOWNLOADING))
        store.partialFile("song").writeText("complete audio fixture")
        store.complete("song", store.partialFile("song").length(), sha256(store.partialFile("song")))
        assertFalse(store.partialFile("song").exists())
        val restored = OfflineStore(store.directory)
        assertEquals(DownloadStatus.COMPLETE, restored.record("song")?.status)
        assertEquals("Artist", restored.record("song")?.artist)
        assertNotNull(restored.completedFile("song"))
        restored.audioFile("song").delete()
        val missing = OfflineStore(store.directory)
        assertEquals(DownloadStatus.FAILED, missing.record("song")?.status)
        assertNull(missing.completedFile("song"))
    }

    @Test fun sameLengthCorruptionIsRejectedAfterRestart() = withStore { store ->
        store.upsert(OfflineDownload("song", "Song", status = DownloadStatus.DOWNLOADING))
        store.partialFile("song").writeText("valid")
        store.complete("song", 5, sha256(store.partialFile("song")))
        store.audioFile("song").writeText("wrong")
        val restored = OfflineStore(store.directory)
        assertNull(restored.completedFile("song"))
        assertEquals(DownloadStatus.FAILED, restored.record("song")?.status)
    }

    @Test fun interruptedJobIsQueuedWithPartialBytesPreserved() = withStore { store ->
        store.upsert(OfflineDownload("song", "Song", status = DownloadStatus.DOWNLOADING, downloadedBytes = 3, totalBytes = 10))
        store.partialFile("song").writeText("123")
        val restored = OfflineStore(store.directory)
        assertEquals(DownloadStatus.QUEUED, restored.record("song")?.status)
        assertEquals(3L, restored.partialFile("song").length())
        assertNull(restored.completedFile("song"))
    }
}
