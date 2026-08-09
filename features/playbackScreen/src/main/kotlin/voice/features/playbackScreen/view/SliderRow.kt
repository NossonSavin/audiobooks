package voice.features.playbackScreen.view

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import voice.core.strings.R
import voice.core.ui.formatTime
import kotlin.time.Duration

@Composable
internal fun SliderRow(
  duration: Duration,
  playedTime: Duration,
  playbackSpeed: Float,
  bookRemainingTime: Duration?,
  bookTotalDuration: Duration?,
  bookTotalPlayedTime: Duration?,
  bookProgress: Float?,
  onSeek: (Duration) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      var localValue by remember { mutableFloatStateOf(0F) }
      val interactionSource = remember { MutableInteractionSource() }
      val dragging by interactionSource.collectIsDraggedAsState()
      val currentPlayedAudioMs = if (dragging) {
        (duration * localValue.toDouble()).inWholeMilliseconds
      } else {
        playedTime.inWholeMilliseconds
      }
      Text(
        text = formatTime(
          timeMs = currentPlayedAudioMs,
          durationMs = duration.inWholeMilliseconds,
        ),
      )
      Slider(
        modifier = Modifier
          .weight(1F)
          .padding(horizontal = 8.dp),
        interactionSource = interactionSource,
        value = if (dragging) {
          localValue
        } else {
          (playedTime / duration).toFloat()
            .coerceIn(0F, 1F)
        },
        onValueChange = {
          localValue = it
        },
        onValueChangeFinished = {
          onSeek(duration * localValue.toDouble())
        },
      )
      val remainingAudioMs = (duration.inWholeMilliseconds - currentPlayedAudioMs).coerceAtLeast(0L)
      val remainingMsAdjusted = if (playbackSpeed > 0F) {
        (remainingAudioMs / playbackSpeed).toLong()
      } else {
        remainingAudioMs
      }
      val totalDurationAdjusted = if (playbackSpeed > 0F) {
        (duration.inWholeMilliseconds / playbackSpeed).toLong()
      } else {
        duration.inWholeMilliseconds
      }
      Text(
        text = "-${
          formatTime(
            timeMs = remainingMsAdjusted,
            durationMs = totalDurationAdjusted,
          )
        }",
      )
    }

    if (bookRemainingTime != null && bookTotalDuration != null && bookTotalPlayedTime != null && bookProgress != null) {
      Text(
        text = stringResource(
          id = R.string.playback_book_status,
          formatTime(bookTotalPlayedTime.inWholeMilliseconds),
          formatTime(bookTotalDuration.inWholeMilliseconds),
          (bookProgress * 100).toInt(),
          formatTime(bookRemainingTime.inWholeMilliseconds),
        ),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
        fontWeight = FontWeight.Normal,
      )
    }
  }
}
