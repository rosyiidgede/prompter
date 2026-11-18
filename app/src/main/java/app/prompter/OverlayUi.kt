package app.prompter

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The floating prompter window content. Drag the top grabber, pinch to resize, double-tap text to restart. */
@Composable
fun OverlayContent(
    scale: Float,
    topInsetPx: Int,
    onDragStart: () -> Unit,
    onDragMove: () -> Unit,
    onZoom: (Float) -> Unit,
    onSnap: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(Color.Black.copy(alpha = PrompterStore.opacity))
            .padding(top = with(LocalDensity.current) { topInsetPx.toDp() }),
    ) {
        PrompterText(
            scroll = scroll,
            playing = playing,
            onReachedEnd = { playing = false },
            modifier = Modifier
                .fillMaxWidth()
                .height((210f * scale).dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        playing = false
                        scope.launch { scroll.scrollTo(0) }
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) }
                },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(onClick = {
                playing = false
                scope.launch { scroll.scrollTo(0) }
            }) { GlyphText("↺") }
            RoundButton(onClick = { PrompterStore.speed = (PrompterStore.speed - 5f).coerceAtLeast(10f) }) {
                GlyphText("−", 20)
            }
            RoundButton(size = 52.dp, ring = true, onClick = { playing = !playing }) {
                PlayPauseIcon(playing, Modifier.size(18.dp))
            }
            RoundButton(onClick = { PrompterStore.speed = (PrompterStore.speed + 5f).coerceAtMost(150f) }) {
                GlyphText("+", 20)
            }
            RoundButton(onClick = onSnap) { GlyphText("◎") }
            RoundButton(onClick = onBack) { GlyphText("⌂") }
            RoundButton(onClick = onClose) { GlyphText("✕", 14) }
        }
        Text(
            text = "%.1fx".format(PrompterStore.speed / 45f),
            color = Color(0x99FFFFFF),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 2.dp),
        )
        // Grabber at the bottom keeps the text flush with the top edge (next to the camera).
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            onDragMove()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x66FFFFFF)),
            )
        }

    }
}
