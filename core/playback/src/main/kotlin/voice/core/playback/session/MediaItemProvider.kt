package voice.core.playback.session

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import voice.core.data.Book
import voice.core.data.BookComparator
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.durationMs
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.HideCoverFromSystemStore
import voice.core.data.toUri
import voice.core.logging.api.Logger
import java.io.File
import voice.core.strings.R as StringsR

import android.graphics.BitmapFactory

@Inject
class MediaItemProvider(
  private val bookRepository: BookRepository,
  private val application: Application,
  private val chapterRepo: ChapterRepo,
  private val contentRepo: BookContentRepo,
  private val imageFileProvider: ImageFileProvider,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @HideCoverFromSystemStore
  private val hideCoverFromSystemStore: DataStore<Boolean>,
) {

  private suspend fun hideCoverFromSystem(): Boolean = hideCoverFromSystemStore.data.first()

  fun root(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_root),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.Root,
    mediaType = MediaType.AudioBookRoot,
  )

  suspend fun recent(): MediaItem? {
    if (currentBookStoreId.data.first() == null) return null
    return MediaItem(
      title = application.getString(StringsR.string.media_session_library_recent),
      browsable = true,
      isPlayable = false,
      mediaId = MediaId.Recent,
      mediaType = MediaType.AudioBook,
    )
  }

  suspend fun item(id: String): MediaItem? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    val hide = hideCoverFromSystem()
    return when (mediaId) {
      MediaId.Root -> root()
      is MediaId.Book -> {
        bookRepository.get(mediaId.id)?.let { mediaItem(it, hide) }
      }
      is MediaId.Chapter -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        chapterRepo.get(mediaId.chapterId)?.let {
          mediaItem(it, content, hide)
        }
      }
      is MediaId.ChapterMark -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        val chapter = chapterRepo.get(mediaId.chapterId) ?: return null
        val mark = chapter.chapterMarks.getOrNull(mediaId.markIndex) ?: return null
        mediaItem(
          playbackItem = PlaybackItem(
            index = 0,
            bookId = mediaId.bookId,
            chapter = chapter,
            markIndex = mediaId.markIndex,
            mark = mark,
          ),
          content = content,
          imageUri = content.cover?.toProvidedUri()?.takeUnless { hide },
        )
      }
      MediaId.Recent -> recent()
    }
  }

  fun mediaItemsWithStartPosition(book: Book, hideCoverFromSystem: Boolean): MediaItemsWithStartPosition {
    return MediaItemsWithStartPosition(
      listOf(mediaItem(book, hideCoverFromSystem)),
      C.INDEX_UNSET,
      C.TIME_UNSET,
    )
  }

  suspend fun mediaItemsWithStartPosition(id: String): MediaItemsWithStartPosition? {
    return when (val mediaId = id.toMediaIdOrNull()) {
      is MediaId.Book -> {
        val book = bookRepository.get(mediaId.id) ?: return null
        mediaItemsWithStartPosition(book, hideCoverFromSystem())
      }
      is MediaId.Chapter, is MediaId.ChapterMark, MediaId.Root, MediaId.Recent, null -> null
    }
  }

  suspend fun chapters(bookId: BookId): List<MediaItem>? {
    val book = bookRepository.get(bookId) ?: return null
    return playbackItems(book)
  }

  internal suspend fun playbackItems(book: Book): List<MediaItem> {
    val hide = hideCoverFromSystem()
    val imageUri = book.content.cover?.toProvidedUri()?.takeUnless { hide }
    return book.playbackItems().map { playbackItem ->
      mediaItem(playbackItem, book.content, imageUri)
    }
  }

  suspend fun children(id: String): List<MediaItem>? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    val hide = hideCoverFromSystem()
    return when (mediaId) {
      MediaId.Root -> {
        bookRepository.all()
          .sortedWith(BookComparator.ByLastPlayed)
          .map { book ->
            mediaItem(book, hide)
          }
      }
      is MediaId.Book -> chapters(mediaId.id)
      is MediaId.Chapter, is MediaId.ChapterMark -> null
      MediaId.Recent -> {
        val bookId = currentBookStoreId.data.first() ?: return null
        val book = bookRepository.get(bookId) ?: return null
        listOf(mediaItem(book, hide))
      }
    }
  }

  fun mediaItem(
    book: Book,
    hideCoverFromSystem: Boolean,
  ): MediaItem {
    return MediaItem(
      title = book.content.name,
      mediaId = MediaId.Book(book.id),
      browsable = false,
      isPlayable = true,
      imageUri = book.content.cover?.toProvidedUri()?.takeUnless { hideCoverFromSystem },
      mediaType = MediaType.AudioBook,
    )
  }

  private fun mediaItem(
    chapter: Chapter,
    content: BookContent,
    hideCoverFromSystem: Boolean,
  ): MediaItem {
    return MediaItem(
      title = chapter.name ?: chapter.id.value,
      mediaId = MediaId.Chapter(bookId = content.id, chapterId = chapter.id),
      browsable = false,
      isPlayable = true,
      sourceUri = chapter.id.toUri().toDirectFileUriIfAvailable(),
      imageUri = content.cover?.toProvidedUri()?.takeUnless { hideCoverFromSystem },
      artist = content.author,
      mediaType = MediaType.AudioBookChapter,
    )
  }

  private fun mediaItem(
    playbackItem: PlaybackItem,
    content: BookContent,
    imageUri: Uri?,
  ): MediaItem {
    val isClippingNeeded = playbackItem.mark.startMs > 0L || playbackItem.mark.endMs < playbackItem.chapter.duration
    val clipping = if (isClippingNeeded) {
      ClippingConfiguration.Builder()
        .setStartPositionMs(playbackItem.mark.startMs)
        .setEndPositionMs(playbackItem.mark.endMs)
        .build()
    } else {
      ClippingConfiguration.UNSET
    }
    return MediaItem(
      title = playbackItem.mark.name
        ?: playbackItem.chapter.name
        ?: playbackItem.chapter.id.value,
      mediaId = playbackItem.mediaId,
      browsable = false,
      isPlayable = true,
      sourceUri = playbackItem.chapter.id.toUri().toDirectFileUriIfAvailable(),
      imageUri = imageUri,
      artist = content.author,
      durationMs = playbackItem.mark.durationMs,
      clippingConfiguration = clipping,
      mediaType = MediaType.AudioBookChapter,
    )
  }

  private fun File.toProvidedUri(): Uri? {
    if (!isFile || length() <= 0L) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return imageFileProvider.uri(this)
  }
}

internal fun Uri.toDirectFileUriIfAvailable(): Uri {
  if (scheme == "file") return this
  if (scheme == "content" && authority == "com.android.externalstorage.documents") {
    try {
      val docId = try {
        android.provider.DocumentsContract.getDocumentId(this)
      } catch (_: Exception) {
        val decoded = java.net.URLDecoder.decode(toString(), "UTF-8")
        decoded.substringAfterLast("/document/")
          .takeIf { it.isNotEmpty() && it != decoded }
          ?: decoded.substringAfterLast("/tree/")
      }
      if (!docId.isNullOrEmpty()) {
        val file = if (docId.startsWith("primary:", ignoreCase = true)) {
          val relativePath = docId.substringAfter(":")
          File(android.os.Environment.getExternalStorageDirectory(), relativePath)
        } else if (docId.contains(":")) {
          val split = docId.split(":", limit = 2)
          File("/storage/${split[0]}", split[1])
        } else {
          null
        }
        if (file != null && file.exists() && file.canRead()) {
          return Uri.fromFile(file)
        }
      }
    } catch (_: Exception) {
    }
  }
  return this
}
