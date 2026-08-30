package voice.core.data.backup

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Bookmark
import voice.core.data.ChapterId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.DocumentFileWithUri
import voice.core.data.folders.FolderType
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookmarkRepo
import voice.core.data.repo.internals.MemoryDataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

  private val contentRepo = FakeBookContentRepo()
  private val bookmarkRepo = FakeBookmarkRepo()
  private val audiobookFolders = FakeAudiobookFolders()
  private val rootFoldersStore = MemoryDataStore<Set<Uri>>(emptySet())
  private val singleFilesStore = MemoryDataStore<Set<Uri>>(emptySet())
  private val currentBookStore = MemoryDataStore<BookId?>(null)

  private val backupManager = BackupManagerImpl(
    contentRepo = contentRepo,
    bookmarkRepo = bookmarkRepo,
    audiobookFolders = audiobookFolders,
    rootAudioBookFoldersStore = rootFoldersStore,
    singleFileAudiobookFoldersStore = singleFilesStore,
    currentBookStore = currentBookStore,
  )

  @Test
  fun `createBackup and restoreBackup roundtrip preserves positions and bookmarks`() = runTest {
    val bookId1 = BookId("uri://book1")
    val chapter1 = ChapterId("uri://book1/ch1")
    val chapter2 = ChapterId("uri://book1/ch2")
    val bookContent1 = BookContent(
      id = bookId1,
      name = "Book 1",
      author = "Author 1",
      playbackSpeed = 1.5f,
      skipSilence = true,
      gain = 2.0f,
      isActive = true,
      lastPlayedAt = Instant.ofEpochMilli(123456789),
      addedAt = Instant.ofEpochMilli(123450000),
      chapters = listOf(chapter1, chapter2),
      currentChapter = chapter2,
      positionInChapter = 42000L,
      cover = null,
      genre = "Fiction",
      narrator = "Narrator",
      series = "Series",
      part = "1",
    )
    contentRepo.put(bookContent1)

    val bookmark = Bookmark(
      id = Bookmark.Id(Uuid.random()),
      bookId = bookId1,
      chapterId = chapter1,
      title = "Bookmark 1",
      time = 10000L,
      addedAt = Instant.ofEpochMilli(123456000),
      setBySleepTimer = false,
    )
    bookmarkRepo.addBookmark(bookmark)

    val folderUri = Uri.parse("uri://folder")
    rootFoldersStore.updateData { setOf(folderUri) }
    currentBookStore.updateData { bookId1 }

    val backup = backupManager.createBackup()
    val json = backupManager.serializeBackup(backup)

    // Clear repositories to simulate wiping data
    contentRepo.clear()
    bookmarkRepo.clear()
    audiobookFolders.added.clear()
    currentBookStore.updateData { null }

    val result = backupManager.restoreBackupFromJson(json)
    assertEquals(1, result.booksCount)
    assertEquals(1, result.bookmarksCount)

    val restoredBook = contentRepo.get(bookId1)
    assertNotNull(restoredBook)
    assertEquals(bookContent1.name, restoredBook.name)
    assertEquals(bookContent1.currentChapter, restoredBook.currentChapter)
    assertEquals(bookContent1.positionInChapter, restoredBook.positionInChapter)
    assertEquals(bookContent1.playbackSpeed, restoredBook.playbackSpeed)
    assertEquals(bookContent1.skipSilence, restoredBook.skipSilence)
    assertEquals(bookContent1.gain, restoredBook.gain)
    assertEquals(bookContent1.lastPlayedAt, restoredBook.lastPlayedAt)

    val restoredBookmarks = bookmarkRepo.all()
    assertEquals(1, restoredBookmarks.size)
    assertEquals(bookmark.title, restoredBookmarks.first().title)
    assertEquals(bookmark.time, restoredBookmarks.first().time)

    assertEquals(listOf(folderUri to FolderType.Folder), audiobookFolders.added)
    assertEquals(bookId1, currentBookStore.data.firstOrNull())
  }

  @Test
  fun `restoring backup on existing book updates position and preserves chapter list`() = runTest {
    val bookId = BookId("uri://book1")
    val ch1 = ChapterId("uri://book1/ch1")
    val ch2 = ChapterId("uri://book1/ch2")

    // Existing book in library (e.g. freshly scanned with 0 progress)
    val existing = BookContent(
      id = bookId,
      name = "Book 1",
      author = "Author 1",
      playbackSpeed = 1.0f,
      skipSilence = false,
      gain = 0.0f,
      isActive = true,
      lastPlayedAt = Instant.EPOCH,
      addedAt = Instant.now(),
      chapters = listOf(ch1, ch2),
      currentChapter = ch1,
      positionInChapter = 0L,
      cover = null,
      genre = null,
      narrator = null,
      series = null,
      part = null,
    )
    contentRepo.put(existing)

    val backup = VoiceBackup(
      version = 1,
      timestamp = 1000L,
      books = listOf(
        BookBackup(
          id = bookId.value,
          name = "Book 1",
          currentChapter = ch2.value,
          positionInChapter = 85000L,
          playbackSpeed = 1.75f,
          skipSilence = true,
          gain = 1.5f,
          lastPlayedAtEpochMs = 999999L,
        ),
      ),
    )

    val result = backupManager.restoreBackup(backup)
    assertEquals(1, result.booksCount)

    val updated = contentRepo.get(bookId)
    assertNotNull(updated)
    assertEquals(ch2, updated.currentChapter)
    assertEquals(85000L, updated.positionInChapter)
    assertEquals(1.75f, updated.playbackSpeed)
    assertEquals(true, updated.skipSilence)
    assertEquals(1.5f, updated.gain)
    assertEquals(listOf(ch1, ch2), updated.chapters)
  }

  private suspend fun <T> Flow<T>.firstOrNull(): T? {
    var result: T? = null
    try {
      collect {
        result = it
        throw kotlinx.coroutines.CancellationException()
      }
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
    return result
  }

  private class FakeBookContentRepo : BookContentRepo {
    private val contents = MutableStateFlow<List<BookContent>>(emptyList())

    fun clear() {
      contents.value = emptyList()
    }

    override fun flow(): Flow<List<BookContent>> = contents

    override suspend fun all(): List<BookContent> = contents.value

    override fun flow(id: BookId): Flow<BookContent?> = flowOf(contents.value.find { it.id == id })

    override suspend fun get(id: BookId): BookContent? = contents.value.find { it.id == id }

    override suspend fun setAllInactiveExcept(ids: List<BookId>) {
      contents.value = contents.value.map { it.copy(isActive = it.id in ids) }
    }

    override suspend fun put(content: BookContent) {
      contents.value = contents.value.filterNot { it.id == content.id } + content
    }
  }

  private class FakeBookmarkRepo : BookmarkRepo {
    private val bookmarks = mutableListOf<Bookmark>()

    fun clear() {
      bookmarks.clear()
    }

    override suspend fun deleteBookmark(id: Bookmark.Id) {
      bookmarks.removeAll { it.id == id }
    }

    override suspend fun addBookmark(bookmark: Bookmark) {
      bookmarks.removeAll { it.id == bookmark.id }
      bookmarks.add(bookmark)
    }

    override suspend fun addAll(bookmarks: List<Bookmark>) {
      for (bm in bookmarks) {
        addBookmark(bm)
      }
    }

    override suspend fun all(): List<Bookmark> = bookmarks.toList()

    override suspend fun addBookmarkAtBookPosition(
      book: Book,
      title: String?,
      setBySleepTimer: Boolean,
    ): Bookmark {
      val bm = Bookmark(
        id = Bookmark.Id.random(),
        bookId = book.id,
        chapterId = book.content.currentChapter,
        title = title,
        time = book.content.positionInChapter,
        addedAt = Instant.now(),
        setBySleepTimer = setBySleepTimer,
      )
      addBookmark(bm)
      return bm
    }

    override suspend fun bookmarks(book: BookContent): List<Bookmark> {
      return bookmarks.filter { it.bookId == book.id }
    }
  }

  private class FakeAudiobookFolders : AudiobookFolders {
    val added = mutableListOf<Pair<Uri, FolderType>>()

    override fun all(): Flow<List<DocumentFileWithUri>> = flowOf(emptyList())

    override suspend fun add(uri: Uri, type: FolderType) {
      added.add(uri to type)
    }

    override suspend fun remove(uri: Uri, type: FolderType) {
      added.removeAll { it.first == uri && it.second == type }
    }

    override suspend fun hasAnyFolders(): Boolean = added.isNotEmpty()
  }
}
