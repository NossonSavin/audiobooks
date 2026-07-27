package voice.features.settings.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DecimalFormat
import voice.core.strings.R as StringsR

@Composable
internal fun PlaybackSpeedDialog(
  currentSpeed: Float,
  onSpeedConfirm: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  val speedFormatter = remember { DecimalFormat("0.##x") }
  var selectedSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier.fillMaxWidth(0.95F),
    ) {
      Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
      ) {
        Text(
          text = stringResource(StringsR.string.playback_speed_title) + ": " + speedFormatter.format(selectedSpeed),
          style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))
        val valueRange = 0.5F..3.5F
        val rangeSize = valueRange.endInclusive - valueRange.start
        val stepSize = 0.05
        val steps = (rangeSize / stepSize).toInt() - 1

        Slider(
          steps = steps,
          valueRange = valueRange,
          value = selectedSpeed,
          onValueChange = { selectedSpeed = it },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          listOf(1f, 1.25f, 1.5f, 1.75f, 2f).forEach { preset ->
            TextButton(
              onClick = { selectedSpeed = preset },
            ) {
              Text(text = speedFormatter.format(preset))
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = {
              onSpeedConfirm(selectedSpeed)
              onDismiss()
            },
          ) {
            Text(stringResource(StringsR.string.common_dialog_ok))
          }
        }
      }
    }
  }
}
