package com.teleprompterpro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teleprompterpro.app.ui.theme.AppColors
import com.teleprompterpro.app.util.AppError

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

/** A tall, full-width slider with a big touch target and live value label. */
@Composable
fun BigSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onChangeFinished: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onChangeFinished,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
        )
    }
}

@Composable
fun ErrorBanner(error: AppError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (error.recoverable) AppColors.Warn.copy(alpha = 0.95f) else AppColors.Danger.copy(alpha = 0.95f),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(error.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(error.message, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}
