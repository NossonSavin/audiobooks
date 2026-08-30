package voice.core.data.backup

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Bookmark
import voice.core.data.ChapterId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.data.folders.RootAudiobookFoldersStore
import voice.core.data.folders.SingleFileAudiobookFoldersStore
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookmarkRepo
import voice.core.data.store.CurrentBookStore
import voice.core.logging.api.Logger
import java.time.Instant
import kotlin.uuid.Uuid

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class BackupManagerImpl(
  private val contentRepo: BookContentRepo,
  private val bookmarkRepo: BookmarkRepo,
  private val audiobookFolders: AudiobookFolders,
  @RootAudiobookFoldersStore
  private val rootAudioBookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SingleFileAudiobookFoldersStore
  private val singleFileAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
) : BackupManager {

  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override suspend fun createBackup(): VoiceBackup {
    val contents = contentRepo.all()
    val bookmarks = bookmarkRepo.all()
    val rootFolders = rootAudioBookFoldersStore.data.first().map { it.toString() }
    val singleFiles = singleFileAudiobookFoldersStore.data.first().map { it.toString() }
    val currentBook = currentBookStore.data.first()

    return VoiceBackup(
      version = 1,
      timestamp = System.currentTimeMillis(),
      books = contents.map { content ->
        BookBackup(
          id = content.id.value,
          name = content.name,
          author = content.author,
          genre = content.genre,
          narrator = content.narrator,
          series = content.series,
          part = content.part,
          currentChapter = content.currentChapter.value,
          positionInChapter = content.positionInChapter,
          playbackSpeed = content.playbackSpeed,
          skipSilence = content.skipSilence,
          gain = content.gain,
          isActive = content.isActive,
          lastPlayedAtEpochMs = content.lastPlayedAt.toEpochMilli(),
          addedAtEpochMs = content.addedAt.toEpochMilli(),
          chapters = content.chapters.map { it.value },
        )
      },
      bookmarks = bookmarks.map { bookmark ->
        BookmarkBackup(
          id = bookmark.id.value.toString(),
          bookId = bookmark.bookId.value,
          chapterId = bookmark.chapterId.value,
          title = bookmark.title,
          time = bookmark.time,
          addedAtEpochMs = bookmark.addedAt.toEpochMilli(),
          setBySleepTimer = bookmark.setBySleepTimer,
        )
      },
      folderUris = rootFolders,
      fileUris = singleFiles,
      currentBookId = currentBook?.value,
    )
  }

  override fun serializeBackup(backup: VoiceBackup): String {
    return json.encodeToString(VoiceBackup.serializer(), backup)
  }

  override suspend fun restoreBackupFromJson(jsonString: String): ImportResult {
    val backup = json.decodeFromString(VoiceBackup.serializer(), jsonString)
    return restoreBackup(backup)
  }

  override suspend fun restoreBackup(backup: VoiceBackup): ImportResult {
    var booksRestored = 0

    // Restore books
    for (bookBackup in backup.books) {
      val existing = contentRepo.get(BookId(bookBackup.id))
      val content = if (existing != null) {
        val chapterExists = existing.chapters.any { it.value == bookBackup.currentChapter }
        val currentChapter = if (chapterExists) ChapterId(bookBackup.currentChapter) else existing.currentChapter
        val position = if (chapterExists) bookBackup.positionInChapter.coerceAtLeast(0L) else existing.positionInChapter
        existing.copy(
          name = bookBackup.name.ifEmpty { existing.name },
          author = bookBackup.author ?: existing.author,
          genre = bookBackup.genre ?: existing.genre,
          narrator = bookBackup.narrator ?: existing.narrator,
          series = bookBackup.series ?: existing.series,
          part = bookBackup.part ?: existing.part,
          currentChapter = currentChapter,
          positionInChapter = position,
          playbackSpeed = bookBackup.playbackSpeed,
          skipSilence = bookBackup.skipSilence,
          gain = bookBackup.gain,
          lastPlayedAt = if (bookBackup.lastPlayedAtEpochMs > 0) Instant.ofEpochMilli(bookBackup.lastPlayedAtEpochMs) else existing.lastPlayedAt,
        )
      } else {
        val chapters = bookBackup.chapters.map { ChapterId(it) }.ifEmpty { listOf(ChapterId(bookBackup.currentChapter)) }
        val currentChapter = if (ChapterId(bookBackup.currentChapter) in chapters) ChapterId(bookBackup.currentChapter) else chapters.first()
        BookContent(
          id = BookId(bookBackup.id),
          name = bookBackup.name,
          author = bookBackup.author,
          genre = bookBackup.genre,
          narrator = bookBackup.narrator,
          series = bookBackup.series,
          part = bookBackup.part,
          currentChapter = currentChapter,
          positionInChapter = bookBackup.positionInChapter.coerceAtLeast(0L),
          playbackSpeed = bookBackup.playbackSpeed,
          skipSilence = bookBackup.skipSilence,
          gain = bookBackup.gain,
          isActive = bookBackup.isActive,
          lastPlayedAt = if (bookBackup.lastPlayedAtEpochMs > 0) Instant.ofEpochMilli(bookBackup.lastPlayedAtEpochMs) else Instant.EPOCH,
          addedAt = if (bookBackup.addedAtEpochMs > 0) Instant.ofEpochMilli(bookBackup.addedAtEpochMs) else Instant.now(),
          chapters = chapters,
          cover = null,
        )
      }
      contentRepo.put(content)
      booksRestored++
    }

    // Restore bookmarks
    val bookmarks = backup.bookmarks.mapNotNull { bm ->
      try {
        val uuid = try {
          Uuid.parse(bm.id)
        } catch (_: Exception) {
          Uuid.random()
        }
        Bookmark(
          id = Bookmark.Id(uuid),
          bookId = BookId(bm.bookId),
          chapterId = ChapterId(bm.chapterId),
          title = bm.title,
          time = bm.time.coerceAtLeast(0L),
          addedAt = if (bm.addedAtEpochMs > 0) Instant.ofEpochMilli(bm.addedAtEpochMs) else Instant.now(),
          setBySleepTimer = bm.setBySleepTimer,
        )
      } catch (e: Exception) {
        Logger.w(e, "Failed to restore bookmark: $bm")
        null
      }
    }
    bookmarkRepo.addAll(bookmarks)
    val bookmarksRestored = bookmarks.size

    // Restore folder URIs
    for (folderUri in backup.folderUris) {
      try {
        audiobookFolders.add(folderUri.toUri(), FolderType.Folder)
      } catch (e: Exception) {
        Logger.w(e, "Failed to re-add folder uri: $folderUri")
      }
    }
    for (fileUri in backup.fileUris) {
      try {
        audiobookFolders.add(fileUri.toUri(), FolderType.File)
      } catch (e: Exception) {
        Logger.w(e, "Failed to re-add file uri: $fileUri")
      }
    }

    // Restore current book
    val currentBookId = backup.currentBookId
    if (currentBookId != null) {
      try {
        currentBookStore.updateData { BookId(currentBookId) }
      } catch (e: Exception) {
        Logger.w(e, "Failed to restore current book id: $currentBookId")
      }
    }

    return ImportResult(
      booksCount = booksRestored,
      bookmarksCount = bookmarksRestored,
    )
  }
}
