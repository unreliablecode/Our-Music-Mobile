package com.github.musicyou.audio.visualization

import org.junit.Assert.assertFalse
import org.junit.Test

class VisualizationLifecycleTest {
    @Test fun aStaleServiceSubscriptionCannotRestartAClosedAnalyzer() {
        val visualization = RealtimeAudioVisualization()
        visualization.close()
        val subscription = visualization.acquire()
        visualization.setPlaying(true)
        visualization.present(1_000_000L)
        assertFalse(visualization.capturing)
        assertFalse(visualization.hasObservers)
        assertFalse(visualization.visualization.value.ready)
        subscription.close()
        subscription.close()
    }
}
