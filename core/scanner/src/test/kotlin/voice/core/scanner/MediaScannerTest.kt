package voice.core.scanner

import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.folders.FolderType
import voice.core.data.repo.BookContentRepoImpl
import voice.core.data.repo.BookRepositoryImpl
import voice.core.data.repo.ChapterRepoImpl
import voice.core.data.repo.internals.AppDb
import voice.core.data.toUri
import voice.core.documentfile.FileBasedDocumentFactory
import voice.core.documentfile.FileBasedDocumentFile
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class MediaScannerTest {

  @Test
  fun singleFileDeletion() = test {
    val audiobookFolder = folder("audiobooks")

    val book1 = File(audiobookFolder, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    scan(audiobookFolder)

    book1Chapters.first().delete()

    scan(audiobookFolder)

    assertBookContents(
      BookContentView(
        id = book1,
        chapters = book1Chapters.drop(1),
      ),
    )
  }

  @Test
  fun metadataPreservedOnDeletion() = test {
    val audiobookFolder = folder("audiobooks")

    val book1 = File(audiobookFolder, "book1")
    val book1Id = BookId(book1.toUri())
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    scan(audiobookFolder)

    val contentWithPositionAtLastChapter =
      bookContentRepo.get(BookId(book1.toUri()))!!.copy(currentChapter = ChapterId(book1Chapters.last().toUri()))
    bookContentRepo.put(contentWithPositionAtLastChapter)

    book1Chapters.forEach { it.toUri().toFile().delete() }

    scan(audiobookFolder)

    audioFile(book1, "1.mp3")
    audioFile(book1, "2.mp3")
    audioFile(book1, "10.mp3")

    assertEquals(expected = contentWithPositionAtLastChapter, actual = bookContentRepo.get(book1Id))
  }

  @Test
  fun multipleRoots() = test {
    val audiobookFolder1 = folder("audiobooks1")

    val book1 = File(audiobookFolder1, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    val audiobookFolder2 = folder("audiobooks2")

    val book2 = File(audiobookFolder2, "book2")
    val book2Chapters = listOf(audioFile(book2, "1.mp3"))

    scan(audiobookFolder1, audiobookFolder2)

    assertBookContents(
      BookContentView(book1, chapters = book1Chapters),
      BookContentView(book2, chapters = book2Chapters),
    )
  }

  @Test
  fun scanRoot() = test {
    val audiobookFolder = folder("audiobooks1")

    val book1 = File(audiobookFolder, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    val book2 = File(audiobookFolder, "book2")
    val book2Chapters = listOf(
      audioFile(book2, "1.mp3"),
      audioFile(book2, "2.mp3"),
      audioFile(book2, "10.mp3"),
    )

    scan(audiobookFolder)

    assertBookContents(
      BookContentView(book1, chapters = book1Chapters),
      BookContentView(book2, chapters = book2Chapters),
    )
  }

  @Test
  fun scanSingleFile() = test {
    val book = audioFile(parent = folder("audiobooks1"), "test.mp3")
    scan(book)
    assertBookContents(
      BookContentView(book, chapters = listOf(book)),
    )
  }

  @Test
  fun scanSingleFolder() = test {
    val folder = folder("book")
    val book = audioFile(parent = folder, "test.mp3")
    scan(folder)
    assertBookContents(
      BookContentView(folder, chapters = listOf(book)),
    )
  }

  @Test
  fun newBookReusesFirstChapterMetadata() = test {
    val folder = folder("book")
    audioFile(parent = folder, "1.mp3")
    audioFile(parent = folder, "2.mp3")

    scan(folder)

    assertEquals(expected = 2, actual = analyzeCalls)
  }

  @Test
  fun recursiveScanning() = test {
    val root = folder("root")
    val author = folder("root/author")
    val book1 = folder("root/author/book1")
    val book1Chapter = audioFile(book1, "c1.mp3")

    val book2 = folder("root/author/book2/cd1")
    val book2Chapter = audioFile(book2, "cd1_c1.mp3")

    // Adding root should find book1 and cd1
    scan(root)

    assertBookContents(
      BookContentView(book1, chapters = listOf(book1Chapter)),
      BookContentView(book2, chapters = listOf(book2Chapter)),
    )
  }

  @Test
  fun multiChapterParallelScan() = test {
    val folder = folder("large_book")
    val chapters = (1..10).map { i ->
      audioFile(parent = folder, "$i.mp3")
    }

    scan(folder)

    assertBookContents(
      BookContentView(folder, chapters = chapters),
    )
    assertEquals(expected = 10, actual = analyzeCalls)
  }

  @Test
  fun cachedChaptersSkipAnalysis() = test {
    val folder = folder("book")
    audioFile(parent = folder, "1.mp3")
    audioFile(parent = folder, "2.mp3")

    scan(folder)
    assertEquals(expected = 2, actual = analyzeCalls)

    // Second scan should use warmed-up cache without re-analyzing
    scan(folder)
    assertEquals(expected = 2, actual = analyzeCalls)
  }

  private fun test(test: suspend TestEnvironment.() -> Unit) {
    runTest {
      TestEnvironment().use { test(it) }
    }
  }

  private class TestEnvironment : Closeable {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
      .allowMainThreadQueries()
      .build()
    val bookContentRepo = BookContentRepoImpl(db.bookContentDao())
    private val chapterRepo = ChapterRepoImpl(db.chapterDao())
    private val mediaAnalyzer = mockk<MediaAnalyzer>()
    var analyzeCalls = 0
    private val scanner = MediaScanner(
      contentRepo = bookContentRepo,
      chapterRepo = chapterRepo,
      chapterParser = ChapterParser(
        chapterRepo = chapterRepo,
        mediaAnalyzer = mediaAnalyzer,
      ),
      bookParser = BookParser(
        contentRepo = bookContentRepo,
        mediaAnalyzer = mediaAnalyzer,
        fileFactory = FileBasedDocumentFactory,
      ),
      deviceHasPermissionBug = mockk(),
    )

    val bookRepo = BookRepositoryImpl(chapterRepo, bookContentRepo)

    private val root: File = Files.createTempDirectory(this::class.java.canonicalName!!).toFile()

    suspend fun scan(
      vararg roots: File,
    ) {
      scanner.performScan(roots.map(::FileBasedDocumentFile))
    }

    @IgnorableReturnValue
    fun audioFile(
      parent: File,
      name: String,
    ): File {
      check(name.endsWith(".mp3"))
      return File(parent, name)
        .also {
          it.parentFile?.mkdirs()
          check(it.createNewFile())
        }
        .also {
          coEvery { mediaAnalyzer.analyze(any()) } coAnswers {
            analyzeCalls++
            Metadata(
              duration = 1000L,
              artist = "Author",
              album = "Book Name",
              fileName = "Chapter",
              chapters = emptyList(),
              title = "Title",
              genre = "Genre",
              narrator = "Narrator",
              series = "Series",
              part = "Part",
            )
          }
        }
    }

    fun folder(name: String): File {
      return File(root, name)
        .also { it.mkdirs() }
    }

    suspend fun assertBookContents(vararg expected: BookContentView) {
      bookRepo.all()
        .map {
          BookContentView(
            id = it.id.toUri().toFile(),
            chapters = it.content.chapters.map { chapter ->
              chapter.toUri().toFile()
            },
          )
        }
        .let { actual ->
          assertEquals(
            expected = expected.sortedBy { it.id },
            actual = actual.sortedBy { it.id },
          )
        }
    }

    override fun close() {
      root.delete()
    }
  }

  data class BookContentView(
    val id: File,
    val chapters: List<File>,
  )
}
