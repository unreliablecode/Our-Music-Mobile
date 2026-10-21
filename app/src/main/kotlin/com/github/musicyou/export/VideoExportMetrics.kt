package com.github.musicyou.export

/** Optional measurements for device benchmarks; the normal export path does not collect them. */
internal class VideoExportMetrics {
    var decodeNanos = 0L
    var audioEncodeNanos = 0L
    var calibrationNanos = 0L
    var analysisNanos = 0L
    var frameRenderNanos = 0L
    var drainNanos = 0L
    var totalNanos = 0L
    var frames = 0L
    var hardwareCanvasFrames = 0L
    var encoderName = ""
}
