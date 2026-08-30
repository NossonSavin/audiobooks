package voice.core.playback.session

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class MediaType {
  AudioBook,
  AudioBookChapter,
  AudioBookRoot,
}

internal fun MediaItem(
  title: String,
  mediaId: MediaId,
  isPlayable: Boolean,
  browsable: Boolean,
  album: String? = null,
  artist: String? = null,
  genre: String? = null,
  sourceUri: Uri? = null,
  imageUri: Uri? = null,
  durationMs: Long? = null,
  clippingConfiguration: ClippingConfiguration = ClippingConfiguration.UNSET,
  mediaType: MediaType,
): MediaItem {
  val metadataBuilder =
    MediaMetadata.Builder()
      .setAlbumTitle(album)
      .setTitle(title)
      .setArtist(artist)
      .setGenre(genre)
      .setIsBrowsable(browsable)
      .setIsPlayable(isPlayable)
      .setDurationMs(durationMs)
      .setMediaType(
        when (mediaType) {
          MediaType.AudioBook -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
          MediaType.AudioBookChapter -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
          MediaType.AudioBookRoot -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
        },
      )

  if (imageUri != null) {
    metadataBuilder.setArtworkUri(imageUri)
  }

  val metadata = metadataBuilder.build()

  val mimeType = when {
    sourceUri != null -> {
      val path = sourceUri.toString().lowercase()
      when {
        path.contains(".m4b") || path.contains(".m4a") || path.contains(".mp4") -> androidx.media3.common.MimeTypes.AUDIO_MP4
        path.contains(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        path.contains(".ogg") || path.contains(".opus") -> androidx.media3.common.MimeTypes.AUDIO_OGG
        path.contains(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
        path.contains(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
        path.contains(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
        else -> null
      }
    }
    else -> null
  }

  val builder = MediaItem.Builder()
    .setMediaId(Json.encodeToString(MediaId.serializer(), mediaId))
    .setMediaMetadata(metadata)
    .setUri(sourceUri)
    .setClippingConfiguration(clippingConfiguration)

  if (mimeType != null) {
    builder.setMimeType(mimeType)
  }

  return builder.build()
}

fun String.toMediaIdOrNull(): MediaId? = try {
  Json.decodeFromString(MediaId.serializer(), this)
} catch (_: SerializationException) {
  null
}
