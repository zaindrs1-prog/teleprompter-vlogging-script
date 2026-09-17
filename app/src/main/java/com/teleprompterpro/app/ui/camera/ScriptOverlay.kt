package com.teleprompterpro.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teleprompterpro.app.ui.theme.AppColors
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The teleprompter text box that sits ON TOP of the live camera preview.
 *
 * Scrolling is driven purely by [progress] (0..1): 0 puts the first line on
 * the reading line, 1 puts the last line there. Because the padding above
 * and below the text equals the viewport, `scroll = progress * textHeight`
 * is exact — no drift between the ETA math and what's on screen.
 *
 * The user can also drag the text; when the drag ends we report the new
 * progress back via [onUserSeek] so the engine (and ETA) follow.
 */
@Composable
fun ScriptOverlay(
    text: String,
    progress: Float,
    fontSp: Float,
    backgroundOpacity: Float,
    mirror: Boolean,
    highlightUntilChar: Int,
    playing: Boolean,
    onUserSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var userDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = backgroundOpacity))
            .graphicsLayer { scaleX = if (mirror) -1f else 1f },
    ) {
        val viewport = maxHeight
        val readingLine = viewport * 0.28f

        // Engine → view: follow progress unless the user is dragging.
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { inProgress ->
                    if (inProgress) {
                        userDragging = true
                    } else if (userDragging) {
                        userDragging = false
                        val max = scrollState.maxValue
                        if (max > 0) onUserSeek(scrollState.value.toFloat() / max)
                    }
                }
        }
        LaunchedEffect(progress, scrollState.maxValue, userDragging) {
            if (!userDragging) {
                val max = scrollState.maxValue
                if (max > 0) scrollState.scrollTo((progress.coerceIn(0f, 1f) * max).toInt())
            }
        }

        val annotated = remember(text, highlightUntilChar) {
            buildAnnotatedString {
                val cut = highlightUntilChar.coerceIn(0, text.length)
                if (cut > 0) {
                    withStyle(SpanStyle(color = AppColors.Prompter.copy(alpha = 0.45f))) { append(text.substring(0, cut)) }
                    append(text.substring(cut))
                } else {
                    append(text)
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(readingLine))
            if (text.isBlank()) {
                Text(
                    "This script is empty. Tap Edit to add text.",
                    color = AppColors.Prompter.copy(alpha = 0.7f),
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.35f).sp,
                )
            } else {
                Text(
                    annotated,
                    color = AppColors.Prompter,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.35f).sp,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(viewport - readingLine))
        }

        // Reading-line marker (left edge) so the eye has an anchor.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = readingLine, start = 6.dp)
                .height((fontSp * 1.35f).dp)
                .fillMaxWidth(0.012f)
                .background(if (playing) AppColors.Ready else AppColors.Warn, RoundedCornerShape(2.dp)),
        )
    }
}
