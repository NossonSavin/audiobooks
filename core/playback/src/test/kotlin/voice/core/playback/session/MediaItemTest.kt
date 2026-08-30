package voice.core.playback.session

import android.net.Uri
import androidx.media3.common.MediaMetadata
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

import voice.core.data.BookId
import voice.core.data.ChapterId

@RunWith(RobolectricTestRunner::class)
class MediaItemTest {

  @Test
  fun `does not set empty artworkData when imageUri is null`() {
    val mediaItem = MediaItem(
      title = "Test Chapter",
      mediaId = MediaId.Chapter(
        bookId = BookId("book1"),
        chapterId = ChapterId("chap1"),
      ),
      isPlayable = true,
      browsable = false,
      imageUri = null,
      mediaType = MediaType.AudioBookChapter,
    )

    assertNull(mediaItem.mediaMetadata.artworkData)
    assertNull(mediaItem.mediaMetadata.artworkUri)
  }

  @Test
  fun `sets artworkUri when provided`() {
    val uri = Uri.parse("content://media/external/images/media/1")
    val mediaItem = MediaItem(
      title = "Test Chapter",
      mediaId = MediaId.Chapter(
        bookId = BookId("book1"),
        chapterId = ChapterId("chap1"),
      ),
      isPlayable = true,
      browsable = false,
      imageUri = uri,
      mediaType = MediaType.AudioBookChapter,
    )

    assertNull(mediaItem.mediaMetadata.artworkData)
    assertEquals(uri, mediaItem.mediaMetadata.artworkUri)
  }
}
