package voice.core.scanner

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.MediaItem
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import voice.core.data.MarkData
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.nameWithoutExtension
import voice.core.logging.api.Logger
import voice.core.scanner.matroska.MatroskaMetaDataExtractor
import voice.core.scanner.matroska.MatroskaParseException
import voice.core.scanner.mp4.Mp4ChapterExtractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

@Inject
internal class MediaAnalyzer(
  private val context: Context,
  private val mp4ChapterExtractor: Mp4ChapterExtractor,
  private val matroskaExtractorFactory: MatroskaMetaDataExtractor.Factory,
) {

  // we use a custom MediaSourceFactory because the default one for the
  // retriever also extracts the covers
  private val mediaSourceFactory = DefaultMediaSourceFactory(
    context,
    DefaultExtractorsFactory(),
  )

  suspend fun analyze(file: CachedDocumentFile): Metadata? {
    val builder = Metadata.Builder(file.nameWithoutExtension())

    val fileType = FileTypes.inferFileTypeFromUri(file.uri)
    val extension = (file.name ?: "").substringAfterLast(delimiter = ".", missingDelimiterValue = "").lowercase()
    val isMp4 = fileType == FileTypes.MP4 || extension == "mp4" || extension == "m4a" || extension == "m4b"
    val isMatroska = fileType == FileTypes.MATROSKA || extension == "mka" || extension == "mkv"

    val nativeData = retrieveNativeMetadata(file.uri)
    var duration = nativeData?.second

    if (nativeData != null) {
      val meta = nativeData.first
      if (meta.title != null) builder.title = meta.title
      if (meta.artist != null) builder.artist = meta.artist
      if (meta.album != null) builder.album = meta.album
      if (meta.genre != null) builder.genre = meta.genre
      if (meta.narrator != null) builder.narrator = meta.narrator
    }

    if (isMp4) {
      parseMp4Chapters(file, builder)
      if (duration != null && duration > Duration.ZERO) {
        return builder.build(duration)
      }
    } else if (isMatroska) {
      parseMatroskaMetaData(file, builder)
      if (duration != null && duration > Duration.ZERO) {
        return builder.build(duration)
      }
    }

    val media3Result = retrieveMetadataAndDuration(file.uri)
    val trackGroups = media3Result?.first
    val media3Duration = media3Result?.second

    if (duration == null || duration <= Duration.ZERO) {
      duration = media3Duration
    }

    if (duration == null || duration <= Duration.ZERO) {
      Logger.w("Duration is zero or negative for file: ${file.uri}")
      return null
    }

    if (trackGroups != null) {
      repeat(trackGroups.length) { trackGroupsIndex ->
        val trackGroup = trackGroups[trackGroupsIndex]
        if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
          repeat(trackGroup.length) { formatIndex ->
            val format = trackGroup.getFormat(formatIndex)
            format.metadata?.let { metadata ->
              repeat(metadata.length()) { metadataIndex ->
                when (val entry = metadata.get(metadataIndex)) {
                  is TextInformationFrame -> visitText(entry, builder)
                  is ChapterFrame -> visitChapter(entry, builder)
                  is VorbisComment -> visitVorbis(entry, builder)
                  is MdtaMetadataEntry -> visitMdta(entry, builder)
                  else -> Logger.d("Unknown metadata entry: $entry")
                }
              }
            }
          }
        }
      }
    }

    if (isMp4 && builder.chapters.isEmpty()) {
      parseMp4Chapters(file, builder)
    }

    return builder.build(duration)
  }

  private fun retrieveNativeMetadata(uri: Uri): Pair<NativeMetadata, Duration>? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
          retriever.setDataSource(pfd.fileDescriptor)
        } ?: retriever.setDataSource(context, uri)
      } catch (_: Exception) {
        retriever.setDataSource(context, uri)
      }
      val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        ?: return null
      val duration = durationMs.milliseconds
      val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
      val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
        ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
      val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
      val genre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
      val author = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR)
        ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER)

      NativeMetadata(
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        narrator = author,
      ) to duration
    } catch (e: Exception) {
      Logger.v("Native metadata extraction failed for $uri: $e")
      null
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {}
    }
  }

  private data class NativeMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val narrator: String?,
  )

  private fun parseMatroskaMetaData(
    file: CachedDocumentFile,
    builder: Metadata.Builder,
  ) {
    try {
      matroskaExtractorFactory.create(file.uri).use { extractor ->
        val mediaInfo = extractor.readMediaInfo()
        builder.chapters.addAll(mediaInfo.chapters)
        builder.artist = builder.artist ?: mediaInfo.artist
        builder.album = builder.album ?: mediaInfo.album
        builder.title = builder.title ?: mediaInfo.title
      }
    } catch (e: MatroskaParseException) {
      Logger.w(e, "Error parsing Matroska metadata")
    }
  }

  private suspend fun parseMp4Chapters(
    file: CachedDocumentFile,
    builder: Metadata.Builder,
  ) {
    val chapters = mp4ChapterExtractor.extractChapters(file.uri)
    builder.chapters += chapters
  }

  private fun visitMdta(
    entry: MdtaMetadataEntry,
    builder: Metadata.Builder,
  ) {
    when (entry.key) {
      "com.apple.quicktime.title" -> {
        builder.title = entry.value.toString(Charsets.UTF_8)
      }
      "com.apple.quicktime.artist" -> {
        builder.artist = entry.value.toString(Charsets.UTF_8)
      }
      "com.apple.quicktime.album" -> {
        builder.album = entry.value.toString(Charsets.UTF_8)
      }
    }
  }

  private fun visitVorbis(
    entry: VorbisComment,
    builder: Metadata.Builder,
  ) {
    val key = entry.key
    val value = entry.value
    when {
      key == "ARTIST" -> builder.artist = value
      key == "ALBUM" -> builder.album = value
      key == "TITLE" -> builder.title = value
      key.startsWith("CHAPTER") -> {
        val withoutPrefix = key.removePrefix("CHAPTER")
        val isName = withoutPrefix.endsWith("NAME")
        if (isName) {
          val index = withoutPrefix.removeSuffix("NAME").toIntOrNull()
          if (index != null) {
            builder.vorbisChapterNames[index] = value
          }
        } else {
          val index = withoutPrefix.toIntOrNull()
          if (index != null) {
            val duration = parseVorbisDuration(value)
            if (duration != null) {
              builder.vorbisChapterStarts[index] = duration.inWholeMilliseconds
            }
          }
        }
      }
      else -> Logger.d("Unknown comment name: ${entry.key}, value: $value")
    }
  }

  private fun visitChapter(
    entry: ChapterFrame,
    builder: Metadata.Builder,
  ) {
    repeat(entry.subFrameCount) { subFrameIndex ->
      val subFrame = entry.getSubFrame(subFrameIndex)
      if (subFrame is TextInformationFrame) {
        builder.chapters.add(MarkData(startMs = entry.startTimeMs.toLong(), name = subFrame.values.first()))
      }
    }
  }

  private fun visitText(
    entry: TextInformationFrame,
    builder: Metadata.Builder,
  ) {
    val value = entry.values.first()
    when (entry.id) {
      "TIT2" -> builder.title = value
      "TPE1" -> builder.artist = value
      "TALB" -> builder.album = value
      "TCON" -> builder.genre = value
      "TCOM" -> builder.narrator = value
      "TXXX" -> when (entry.description) {
        "MVNM" -> builder.series = value
        "MVIN" -> builder.part = if (builder.part.isNullOrBlank()) value else builder.part
        "TXXX:PART" -> builder.part = value
        "TXXX:NARRATOR" -> builder.narrator = if (builder.narrator.isNullOrBlank()) value else builder.narrator
        else -> Logger.v("Unknown TXXX frame description:  ${entry.description}, value: $value")
      }
      "TRCK", "TYER", "TSSE" -> {}
      else -> Logger.v("Unknown frame ID: ${entry.id}, value: $value")
    }
  }

  private suspend fun retrieveMetadataAndDuration(uri: Uri): Pair<TrackGroupArray, Duration>? {
    return try {
      MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .use {
          val trackGroups = it.retrieveTrackGroups().await()
          val duration = it.retrieveDurationUs().await().microseconds
          trackGroups to duration
        }
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      Logger.w(e, "Error retrieving metadata")
      null
    }
  }
}
