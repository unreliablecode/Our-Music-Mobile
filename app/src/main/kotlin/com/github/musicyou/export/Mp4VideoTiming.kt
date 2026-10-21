package com.github.musicyou.export

import java.io.File
import java.io.RandomAccessFile

/** Checks every picture duration in the MP4's media timebase, including its final picture. */
internal object Mp4VideoTiming {
    private const val ERROR = "The finalized video did not preserve its fixed 60 FPS sample durations. No video was saved."
    private const val MOOV = 0x6d6f6f76
    private const val TRAK = 0x7472616b
    private const val MDIA = 0x6d646961
    private const val HDLR = 0x68646c72
    private const val MDHD = 0x6d646864
    private const val MINF = 0x6d696e66
    private const val STBL = 0x7374626c
    private const val STTS = 0x73747473
    private const val VIDE = 0x76696465

    fun verify(file: File, expectedFrames: Long) {
        check(expectedFrames > 0) { ERROR }
        RandomAccessFile(file, "r").use { input ->
            val root = Box(0, 0L, input.length())
            val movie = input.children(root).required(MOOV)
            var videoTracks = 0
            for (track in input.children(movie).filter { it.type == TRAK }) {
                val media = input.children(track).required(MDIA)
                val mediaChildren = input.children(media)
                val handler = mediaChildren.required(HDLR)
                check(handler.length >= 12L) { ERROR }
                input.seek(handler.payload + 8L)
                if (input.readInt() != VIDE) continue
                videoTracks++
                val header = mediaChildren.required(MDHD)
                check(header.length >= 4L) { ERROR }
                input.seek(header.payload)
                val version = input.readInt() ushr 24
                check(version == 0 || version == 1) { ERROR }
                check(header.length >= if (version == 0) 24L else 36L) { ERROR }
                input.seek(header.payload + if (version == 0) 12L else 20L)
                val timescale = input.unsignedInt()
                val declaredDuration = if (version == 0) input.unsignedInt() else input.readLong()
                check(timescale > 0L && declaredDuration > 0L) { ERROR }
                val information = mediaChildren.required(MINF)
                val table = input.children(information).required(STBL)
                val timing = input.children(table).required(STTS)
                check(timing.length >= 8L) { ERROR }
                input.seek(timing.payload)
                check(input.readInt() ushr 24 == 0) { ERROR }
                val entries = input.unsignedInt()
                check(entries in 1L..expectedFrames && entries <= (timing.length - 8L) / 8L) { ERROR }
                var count = 0L
                var duration = 0L
                var entry = 0L
                while (entry < entries) {
                    val samples = input.unsignedInt()
                    val delta = input.unsignedInt()
                    check(samples > 0L && samples <= expectedFrames - count && delta > 0L && delta * VideoTimeline.FPS == timescale) { ERROR }
                    check(samples <= Long.MAX_VALUE / delta) { ERROR }
                    val segmentDuration = samples * delta
                    check(duration <= Long.MAX_VALUE - segmentDuration) { ERROR }
                    duration += segmentDuration
                    count += samples
                    entry++
                }
                check(count == expectedFrames && duration == declaredDuration) { ERROR }
            }
            check(videoTracks == 1) { ERROR }
        }
    }

    private data class Box(val type: Int, val payload: Long, val end: Long) {
        val length: Long get() = end - payload
    }

    private fun RandomAccessFile.unsignedInt(): Long = readInt().toLong() and 0xffffffffL

    private fun List<Box>.required(type: Int): Box {
        val matches = filter { it.type == type }
        check(matches.size == 1) { ERROR }
        return matches.single()
    }

    private fun RandomAccessFile.children(parent: Box): List<Box> {
        val result = ArrayList<Box>()
        var position = parent.payload
        while (position < parent.end) {
            check(result.size < 4_096 && parent.end - position >= 8L) { ERROR }
            seek(position)
            val size32 = unsignedInt()
            val type = readInt()
            val headerSize = if (size32 == 1L) 16L else 8L
            check(parent.end - position >= headerSize) { ERROR }
            val size = when (size32) { 0L -> parent.end - position; 1L -> readLong(); else -> size32 }
            check(size >= headerSize && size <= parent.end - position) { ERROR }
            val end = position + size
            result += Box(type, position + headerSize, end)
            position = end
        }
        return result
    }
}
