package app.prompter

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.Canvas

val Accent = Color(0xFF8B5CF6)

/** Auto-scrolling script text. Scrolls while [playing] is true. */
@Composable
fun PrompterText(
    scroll: ScrollState,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onReachedEnd: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val speed by rememberUpdatedState(PrompterStore.speed)
    val endCb by rememberUpdatedState(onReachedEnd)

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            try {
                scroll.scrollBy(speed * density * dt)
            } catch (e: CancellationException) {
                // a finger drag took over the scroll for a moment; keep auto-scrolling afterwards
                currentCoroutineContext().ensureActive()
            }
            if (!scroll.canScrollForward) {
                endCb()
                break
            }
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val boxHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .graphicsLayer { scaleX = if (PrompterStore.mirror) -1f else 1f }
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = PrompterStore.script,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = PrompterStore.fontSize.sp,
                lineHeight = (PrompterStore.fontSize * 1.4f).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(boxHeight))
        }
    }
}

@Composable
fun RoundButton(
    size: Dp = 40.dp,
    ring: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (ring) Color(0xFF2A1B52) else Color(0x33FFFFFF))
            .then(if (ring) Modifier.border(3.dp, Accent, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        if (playing) {
            val w = size.width * 0.28f
            drawRect(Color.White, Offset(size.width * 0.12f, 0f), Size(w, size.height))
            drawRect(Color.White, Offset(size.width * 0.60f, 0f), Size(w, size.height))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.15f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.15f, size.height)
                close()
            }
            drawPath(p, Color.White)
        }
    }
}

@Composable
fun GlyphText(s: String, size: Int = 16) {
    Text(s, color = Color.White, fontSize = size.sp)
}
