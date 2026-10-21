package com.github.musicyou.ui.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.CircularSpectrumGeometry
import com.github.musicyou.audio.visualization.SphereInteriorGeometry
import com.github.musicyou.ui.glass.AdaptiveText
import com.github.musicyou.ui.glass.GlassSurface

/** Audio reactive circular spectrum, with a quiet stationary ring when audio is absent. */
@Composable
internal fun SpectrumSphere(frameProvider: () -> AudioVisualizationFrame?, playing: Boolean, modifier: Modifier) {
    val geometry = remember { CircularSpectrumGeometry() }
    val interior = remember { SphereInteriorGeometry() }
    val path = remember { Path() }
    val innerPaths = remember { List(interior.strandCount) { Path() } }
    val status by remember(frameProvider, playing) {
        derivedStateOf(structuralEqualityPolicy()) { if (!playing) "Paused" else if (frameProvider()?.ready == true) "Playing" else "Waiting for audio" }
    }
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AdaptiveText("Spectrum sphere", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                AdaptiveText(status, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().weight(1f).graphicsLayer().preferredFrameRate(if (playing) 60f else 0f)
                .testTag("audio-sphere-plot").semantics {
                    contentDescription = "Circular frequency spectrum driven by decoded audio"
                    stateDescription = status
                }) {
                val audio = frameProvider()?.takeIf { playing && it.ready }
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension * 0.32f
                val energy = audio?.rms?.coerceIn(0f, 1f) ?: 0f
                val mint = Color(0xFF90FFD0)
                val teal = Color(0xFF42EBCB)
                drawCircle(Brush.radialGradient(listOf(teal.copy(alpha = 0.10f + energy * 0.1f), Color.Transparent), center, radius * 1.6f), radius * 1.6f, center)
                interior.fill(audio?.spectrum.orEmpty(), audio?.rms ?: 0f, audio?.positionUs ?: 0L, center.x, center.y, radius)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF061C20).copy(alpha = 0.92f), Color(0xFF09292B).copy(alpha = 0.66f)), center, radius), radius * 0.83f, center)
                for (strand in 0 until interior.strandCount) {
                    val coordinates = interior.strands[strand]
                    val filament = innerPaths[strand]
                    filament.reset()
                    for (point in 0 until interior.pointsPerStrand) {
                        if (point == 0) filament.moveTo(coordinates[0], coordinates[1])
                        else filament.lineTo(coordinates[point * 2], coordinates[point * 2 + 1])
                    }
                    drawPath(filament, teal.copy(alpha = 0.035f + energy * 0.04f), style = Stroke(3.2.dp.toPx()))
                    drawPath(filament, mint.copy(alpha = 0.13f + energy * 0.22f), style = Stroke(0.55.dp.toPx()))
                }
                for (particle in 0 until interior.particleCount) {
                    val opacity = interior.particles[particle * 3 + 2]
                    drawCircle(mint.copy(alpha = opacity * (0.36f + energy * 0.45f)), (0.45f + opacity * 0.65f).dp.toPx(),
                        Offset(interior.particles[particle * 3], interior.particles[particle * 3 + 1]))
                }
                geometry.fill(audio?.spectrum.orEmpty(), audio?.rms ?: 0f, audio?.peak ?: 0f, center.x, center.y, radius)
                path.reset()
                for (point in 0 until geometry.pointCount) {
                    val x = geometry.coordinates[point * 2]
                    val y = geometry.coordinates[point * 2 + 1]
                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, teal.copy(alpha = 0.08f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, teal.copy(alpha = 0.22f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, mint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
