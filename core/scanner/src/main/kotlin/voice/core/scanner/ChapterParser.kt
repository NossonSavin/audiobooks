package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.isAudioFile
import voice.core.data.repo.ChapterRepo
import voice.core.data.repo.getOrPut
import voice.core.documentfile.CachedDocumentFile
import java.time.Instant

internal data class ChapterParseResult(
  val chapters: List<Chapter>,
  val firstChapterMetadata: Metadata?,
)

@Inject
internal class ChapterParser(
  private val chapterRepo: ChapterRepo,
  private val mediaAnalyzer: MediaAnalyzer,
) {

  suspend fun parse(
    documentFile: CachedDocumentFile,
    audioFiles: List<CachedDocumentFile>? = null,
  ): ChapterParseResult = coroutineScope {
    val filesToParse = audioFiles ?: collectAudioFiles(documentFile)
    if (filesToParse.isEmpty()) {
      return@coroutineScope ChapterParseResult(emptyList(), null)
    }

    val parsedResults = filesToParse.map { file ->
      async {
        val id = ChapterId(file.uri)
        var analyzed: Metadata? = null
        val chapter = chapterRepo.getOrPut(
          id = id,
          lastModified = Instant.ofEpochMilli(file.lastModified),
          fileSize = file.length,
        ) {
          val metaData = mediaAnalyzer.analyze(file) ?: return@getOrPut null
          analyzed = metaData
          Chapter(
            id = id,
            duration = metaData.duration,
            fileLastModified = Instant.ofEpochMilli(file.lastModified),
            name = metaData.title ?: metaData.fileName,
            markData = metaData.chapters,
            fileSize = file.length,
          )
        }
        if (chapter != null) {
          chapter to analyzed
        } else {
          null
        }
      }
    }.awaitAll().filterNotNull()

    val chapters = parsedResults.map { it.first }.sorted()
    val metadataMap = parsedResults.mapNotNull { (chapter, metadata) ->
      if (metadata != null) chapter.id to metadata else null
    }.toMap()

    ChapterParseResult(
      chapters = chapters,
      firstChapterMetadata = chapters.firstOrNull()?.let { metadataMap[it.id] },
    )
  }

  private fun collectAudioFiles(file: CachedDocumentFile): List<CachedDocumentFile> {
    if (file.isAudioFile()) return listOf(file)
    if (!file.isDirectory) return emptyList()
    val result = mutableListOf<CachedDocumentFile>()
    fun walk(f: CachedDocumentFile) {
      if (f.isAudioFile()) {
        result.add(f)
      } else if (f.isDirectory) {
        f.children.forEach { walk(it) }
      }
    }
    walk(file)
    return result
  }
}
