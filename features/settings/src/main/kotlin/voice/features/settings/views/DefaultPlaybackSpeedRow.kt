package voice.features.settings.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.text.DecimalFormat
import voice.core.strings.R as StringsR
import voice.core.ui.icons.VoiceIcons

@Composable
internal fun DefaultPlaybackSpeedRow(
  defaultPlaybackSpeed: Float,
  openPlaybackSpeedDialog: () -> Unit,
) {
  val speedFormatter = remember { DecimalFormat("0.##x") }

  ListItem(
    modifier = Modifier
      .clickable {
        openPlaybackSpeedDialog()
      }
      .fillMaxWidth(),
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Speed,
        contentDescription = stringResource(StringsR.string.settings_playback_default_speed_title),
      )
    },
    headlineContent = {
      Text(text = stringResource(StringsR.string.settings_playback_default_speed_title))
    },
    supportingContent = {
      Text(text = speedFormatter.format(defaultPlaybackSpeed))
    },
  )
}
