package voice.features.playbackScreen.view

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import voice.core.strings.R
import voice.core.ui.icons.VoiceIcons
import voice.features.playbackScreen.BookPlayViewState

@Composable
internal fun BookPlayAppBar(
  viewState: BookPlayViewState,
  onSleepTimerClick: () -> Unit,
  onBookmarkClick: () -> Unit,
  onBookmarkLongClick: () -> Unit,
  onSpeedChangeClick: () -> Unit,
  onSkipSilenceClick: () -> Unit,
  onVolumeBoostClick: () -> Unit,
  onCloseClick: () -> Unit,
  useLandscapeLayout: Boolean,
) {
  val appBarActions: @Composable RowScope.() -> Unit = {
    IconButton(onClick = onSleepTimerClick) {
      val sleepTimerIcon = if (viewState.sleepTimerState is BookPlayViewState.SleepTimerViewState.Disabled) {
        VoiceIcons.Bedtime
      } else {
        VoiceIcons.BedtimeOff
      }
      Icon(
        imageVector = sleepTimerIcon,
        contentDescription = stringResource(id = R.string.sleep_timer_action_open),
      )
    }
    Box(
      modifier = Modifier
        .size(40.dp)
        .combinedClickable(
          onClick = onBookmarkClick,
          onLongClick = onBookmarkLongClick,
          indication = ripple(bounded = false, radius = 20.dp),
          interactionSource = remember { MutableInteractionSource() },
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = VoiceIcons.CollectionsBookmark,
        contentDescription = stringResource(id = R.string.bookmark_title),
      )
    }
    Box(
      modifier = Modifier
        .minimumInteractiveComponentSize()
        .widthIn(min = 48.dp)
        .combinedClickable(
          onClick = onSpeedChangeClick,
          indication = ripple(bounded = false, radius = 24.dp),
          interactionSource = remember { MutableInteractionSource() },
        )
        .padding(horizontal = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      val speedFormatter = remember { DecimalFormat("0.00'x'") }
      Text(
        text = speedFormatter.format(viewState.playbackSpeed),
        style = MaterialTheme.typography.titleLarge.copy(
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
        ),
      )
    }
    OverflowMenu(
      skipSilence = viewState.skipSilence,
      onSkipSilenceClick = onSkipSilenceClick,
      onVolumeBoostClick = onVolumeBoostClick,
    )
  }
  if (useLandscapeLayout) {
    TopAppBar(
      navigationIcon = {
        CloseIcon(onCloseClick)
      },
      actions = appBarActions,
      title = {
        AppBarTitle(viewState.title)
      },
    )
  } else {
    MediumTopAppBar(
      navigationIcon = {
        CloseIcon(onCloseClick)
      },
      actions = appBarActions,
      title = {
        AppBarTitle(viewState.title)
      },
    )
  }
}
