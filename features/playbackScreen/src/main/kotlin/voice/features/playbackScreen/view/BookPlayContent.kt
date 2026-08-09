package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import voice.core.data.BookId
import voice.features.playbackScreen.BookPlayViewState
import kotlin.time.Duration

@Composable
internal fun BookPlayContent(
  contentPadding: PaddingValues,
  viewState: BookPlayViewState,
  bookId: BookId,
  onPlayClick: () -> Unit,
  onRewindClick: () -> Unit,
  onFastForwardClick: () -> Unit,
  onSeek: (Duration) -> Unit,
  onSkipToNext: () -> Unit,
  onSkipToPrevious: () -> Unit,
  onCurrentChapterClick: () -> Unit,
  useLandscapeLayout: Boolean,
) {
  if (useLandscapeLayout) {
    Row(
      modifier = Modifier
        .padding(
          top = contentPadding.calculateTopPadding(),
          bottom = contentPadding.calculateBottomPadding(),
        )
    ) {
      CoverRow(
        bookId = bookId,
        cover = viewState.cover,
        onPlayClick = onPlayClick,
        sleepTimerState = viewState.sleepTimerState,
        modifier = Modifier
          .fillMaxHeight()
          .weight(1F)
          .padding(bottom = 16.dp),
      )
      Column(
        modifier = Modifier
          .fillMaxHeight()
          .weight(1F)
          .padding(
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
          ),
        verticalArrangement = Arrangement.Center,
      ) {
        viewState.chapterName?.let { chapterName ->
          ChapterRow(
            chapterName = chapterName,
            nextPreviousVisible = viewState.showPreviousNextButtons,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
            onCurrentChapterClick = onCurrentChapterClick,
          )
        }
        Spacer(modifier = Modifier.size(8.dp))
        SliderRow(
          duration = viewState.duration,
          playedTime = viewState.playedTime,
          playbackSpeed = viewState.playbackSpeed,
          bookRemainingTime = viewState.bookRemainingTime,
          bookTotalDuration = viewState.bookTotalDuration,
          bookTotalPlayedTime = viewState.bookTotalPlayedTime,
          bookProgress = viewState.bookProgress,
          onSeek = onSeek,
        )
        Spacer(modifier = Modifier.size(16.dp))
        PlaybackRow(
          playing = viewState.playing,
          onPlayClick = onPlayClick,
          onRewindClick = onRewindClick,
          onFastForwardClick = onFastForwardClick,
        )
      }
    }
  } else {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = contentPadding.calculateTopPadding()),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      CoverRow(
        bookId = bookId,
        onPlayClick = onPlayClick,
        cover = viewState.cover,
        sleepTimerState = viewState.sleepTimerState,
        modifier = Modifier
          .weight(1F)
          .fillMaxWidth(),
      )
      Column(
        modifier = Modifier
          .padding(bottom = contentPadding.calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        viewState.chapterName?.let { chapterName ->
          Spacer(modifier = Modifier.size(10.dp))
          ChapterRow(
            chapterName = chapterName,
            nextPreviousVisible = viewState.showPreviousNextButtons,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
            onCurrentChapterClick = onCurrentChapterClick,
          )
        }
        Spacer(modifier = Modifier.size(10.dp))
        SliderRow(
          duration = viewState.duration,
          playedTime = viewState.playedTime,
          playbackSpeed = viewState.playbackSpeed,
          bookRemainingTime = viewState.bookRemainingTime,
          bookTotalDuration = viewState.bookTotalDuration,
          bookTotalPlayedTime = viewState.bookTotalPlayedTime,
          bookProgress = viewState.bookProgress,
          onSeek = onSeek,
        )
        Spacer(modifier = Modifier.size(16.dp))
        PlaybackRow(
          playing = viewState.playing,
          onPlayClick = onPlayClick,
          onRewindClick = onRewindClick,
          onFastForwardClick = onFastForwardClick,
        )
        Spacer(modifier = Modifier.size(24.dp))
      }
    }
  }
}
