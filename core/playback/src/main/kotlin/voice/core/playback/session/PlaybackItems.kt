package voice.core.playback.session

import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.ChapterMark
import voice.core.data.durationMs
import voice.core.data.markForPosition

internal data class PlaybackItem(
  val index: Int,
  val bookId: BookId,
  val chapter: Chapter,
  val markIndex: Int,
  val mark: ChapterMark,
) {
  val mediaId: MediaId.ChapterMark
    get() = MediaId.ChapterMark(
      bookId = bookId,
      chapterId = chapter.id,
      markIndex = markIndex,
      startMs = mark.startMs,
      endMs = mark.endMs,
    )
}

internal fun Book.playbackItems(): List<PlaybackItem> {
  var index = 0
  return chapters.map { chapter ->
    PlaybackItem(
      index = index++,
      bookId = id,
      chapter = chapter,
      markIndex = 0,
      mark = ChapterMark(
        name = chapter.name ?: chapter.id.value,
        startMs = 0L,
        endMs = chapter.duration
      )
    )
  }
}

internal fun Book.playbackItemForPosition(
  chapterId: ChapterId,
  positionInChapterMs: Long,
): PlaybackItem? {
  return playbackItems().firstOrNull {
    it.chapter.id == chapterId
  }
}

internal val MediaId.bookId: BookId?
  get() = when (this) {
    is MediaId.Book -> id
    is MediaId.Chapter -> bookId
    is MediaId.ChapterMark -> bookId
    MediaId.Recent,
    MediaId.Root,
    -> null
  }

internal val MediaId.realChapterId: ChapterId?
  get() = when (this) {
    is MediaId.Chapter -> chapterId
    is MediaId.ChapterMark -> chapterId
    is MediaId.Book,
    MediaId.Recent,
    MediaId.Root,
    -> null
  }

internal fun MediaId.positionInChapter(positionInCurrentMediaItemMs: Long): Long? {
  return when (this) {
    is MediaId.Chapter -> positionInCurrentMediaItemMs
    is MediaId.ChapterMark -> startMs + positionInCurrentMediaItemMs
    is MediaId.Book,
    MediaId.Recent,
    MediaId.Root,
    -> null
  }
}

internal fun PlaybackItem.positionInMediaItem(positionInChapterMs: Long): Long {
  return (positionInChapterMs - mark.startMs).coerceIn(0L, mark.durationMs)
}
