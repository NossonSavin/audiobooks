package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.isAudioFile
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.logging.api.Logger

internal data class DiscoveredBook(
  val bookFolder: CachedDocumentFile,
  val audioFiles: List<CachedDocumentFile>,
)

@Inject
internal class MediaScanner(
  private val contentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val deviceHasPermissionBug: DeviceHasStoragePermissionBug,
) {

  suspend fun performScan(folders: List<CachedDocumentFile>) {
    val discoveredBooks = folders
      .flatMap { discoverBooks(it) }
      .distinctBy { it.bookFolder.uri }

    contentRepo.setAllInactiveExcept(discoveredBooks.map { BookId(it.bookFolder.uri) })

    val probeFile = discoveredBooks.asSequence()
      .flatMap { it.audioFiles }
      .firstOrNull { it.uri.authority == "com.android.externalstorage.documents" }

    if (probeFile != null) {
      if (deviceHasPermissionBug.checkForBugAndSet(probeFile)) {
        Logger.w("Device has permission bug, aborting scan! Probed $probeFile")
        return
      }
    }

    val allChapterIds = discoveredBooks.flatMap { book ->
      book.audioFiles.map { ChapterId(it.uri) }
    }
    chapterRepo.warmup(allChapterIds)

    val semaphore = Semaphore(4)
    coroutineScope {
      discoveredBooks
        .sortedByDescending { it.audioFiles.size }
        .map { discoveredBook ->
          async {
            semaphore.withPermit {
              scan(discoveredBook)
            }
          }
        }
        .awaitAll()
    }
  }

  private fun discoverBooks(file: CachedDocumentFile): List<DiscoveredBook> {
    if (file.isFile) {
      return if (file.isAudioFile()) {
        listOf(DiscoveredBook(bookFolder = file, audioFiles = listOf(file)))
      } else {
        emptyList()
      }
    }
    val children = file.children
    val subFolders = children.filter { it.isDirectory }
    return if (subFolders.isEmpty()) {
      val audioFiles = children.filter { it.isAudioFile() }
      listOf(DiscoveredBook(bookFolder = file, audioFiles = audioFiles))
    } else {
      subFolders.flatMap { discoverBooks(it) }
    }
  }

  private suspend fun scan(discoveredBook: DiscoveredBook) {
    val (file, audioFiles) = discoveredBook
    val parseResult = chapterParser.parse(file, audioFiles)
    val chapters = parseResult.chapters
    if (chapters.isEmpty()) return

    val content = bookParser.parseAndStore(chapters, file, parseResult.firstChapterMetadata)

    val chapterIds = chapters.map { it.id }
    val currentChapterGone = content.currentChapter !in chapterIds
    val currentChapter = if (currentChapterGone) chapterIds.first() else content.currentChapter
    val positionInChapter = if (currentChapterGone) 0 else content.positionInChapter
    val updated = content.copy(
      chapters = chapterIds,
      currentChapter = currentChapter,
      positionInChapter = positionInChapter,
      isActive = true,
    )
    if (content != updated) {
      validateIntegrity(updated, chapters)
      contentRepo.put(updated)
    }
  }
}

