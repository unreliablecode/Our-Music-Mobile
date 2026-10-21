package com.github.musicyou.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class Mp4VideoTimingTest {
    @Test fun completeFramesSupportBothMediaHeaderVersionsAndExtendedBoxHeaders() {
        for (version in 0..1) withFile(movie(version, listOf(3 to 1_500), 4_500, extended = version == 1)) { Mp4VideoTiming.verify(it, 3) }
    }

    @Test fun shortenedFinalFrameAndOtherFrameRatesAreRejected() {
        rejects(movie(0, listOf(2 to 1_500, 1 to 1_320), 4_320))
        rejects(movie(0, listOf(3 to 3_000), 9_000))
    }

    @Test fun missingPicturesAndConflictingMediaDurationsAreRejected() {
        rejects(movie(0, listOf(2 to 1_500), 3_000))
        rejects(movie(1, listOf(3 to 1_500), 4_400))
    }

    @Test fun truncatedOrOverrunningBoxesAndTimingEntriesAreRejected() {
        val valid = movie(0, listOf(3 to 1_500), 4_500)
        rejects(valid.copyOf(valid.size - 1))
        val overrun = valid.copyOf().apply { this[0] = 0x7f }
        rejects(overrun)
        val shortTiming = movie(0, listOf(3 to 1_500), 4_500, entryCount = 2)
        rejects(shortTiming)
    }

    private fun movie(version: Int, entries: List<Pair<Int, Int>>, duration: Long, extended: Boolean = false, entryCount: Int = entries.size): ByteArray {
        val handler = box("hdlr", bytes { writeInt(0); writeInt(0); writeInt(0x76696465); writeLong(0); writeInt(0) })
        val header = box("mdhd", bytes {
            writeInt(version shl 24)
            if (version == 1) { writeLong(0); writeLong(0) } else { writeInt(0); writeInt(0) }
            writeInt(90_000)
            if (version == 1) writeLong(duration) else writeInt(duration.toInt())
            writeInt(0)
        })
        val timing = box("stts", bytes { writeInt(0); writeInt(entryCount); entries.forEach { (count, delta) -> writeInt(count); writeInt(delta) } })
        val media = box("mdia", handler + header + box("minf", box("stbl", timing)))
        return box("ftyp", byteArrayOf()) + box("mdat", ByteArray(64)) + box("moov", box("trak", media), extended)
    }

    private fun box(type: String, payload: ByteArray, extended: Boolean = false): ByteArray = bytes {
        writeInt(if (extended) 1 else payload.size + 8)
        write(type.toByteArray(Charsets.US_ASCII))
        if (extended) writeLong(payload.size.toLong() + 16)
        write(payload)
    }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.write() }
        return output.toByteArray()
    }

    private fun rejects(bytes: ByteArray) = withFile(bytes) { file ->
        try { Mp4VideoTiming.verify(file, 3); fail("Invalid 60 FPS sample timing was accepted") }
        catch (_: IllegalStateException) { }
    }

    private fun withFile(bytes: ByteArray, action: (File) -> Unit) {
        val file = File.createTempFile("video_timing_", ".mp4")
        try { file.writeBytes(bytes); action(file) } finally { file.delete() }
    }
}
