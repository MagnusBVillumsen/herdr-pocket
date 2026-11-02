package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus

@Composable
fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Canvas(modifier.size(12.dp)) {
        if (status == AgentStatus.Idle || status == AgentStatus.Unknown) {
            drawCircle(color, radius = size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        } else {
            drawCircle(color)
        }
    }
}

@Composable
fun StarButton(important: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics { contentDescription = if (important) "Unmark important" else "Mark important" },
    ) {
        Text(
            text = if (important) "★" else "☆",
            fontSize = 22.sp,
            color = if (important) starColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun GlyphButton(glyph: String, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(glyph, fontSize = 22.sp)
    }
}
