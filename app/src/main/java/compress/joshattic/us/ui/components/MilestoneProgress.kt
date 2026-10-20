package compress.joshattic.us.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Progress bar with 11 labelled milestones (0%, 10% ... 100%) shown above the bar.
 * A milestone dot is filled once the progress has reached it.
 */
@Composable
fun MilestoneProgressBar(
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    // Dots only change 11 times in total, so they read this derived value instead of the raw progress.
    val reachedCount by remember {
        derivedStateOf { ((progress().coerceIn(0f, 1f)) * 10f + 0.001f).toInt() }
    }
    val filledColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val trackWidth = maxWidth
            val cellWidth = 30.dp
            for (i in 0..10) {
                val reached = reachedCount >= i
                Column(
                    modifier = Modifier
                        .width(cellWidth)
                        .offset(x = trackWidth * (i / 10f) - cellWidth / 2),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${i * 10}%",
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        color = if (reached) filledColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (reached) filledColor else emptyColor)
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { progress().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            strokeCap = StrokeCap.Round
        )
    }
}
