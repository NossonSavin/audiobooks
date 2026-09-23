package voice.core.playback.session

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PlaybackDataSourceFactoryTest {

  @Test
  fun `delegates content URI to seekableContentDataSource and falls back to default on error`() {
    val context = mockk<Context>(relaxed = true)
    val defaultDataSource = mockk<DataSource>(relaxed = true)
    val defaultFactory = mockk<DataSource.Factory> {
      every { createDataSource() } returns defaultDataSource
    }

    val factory = PlaybackDataSourceFactory(context, defaultFactory)
    val dataSource = factory.createDataSource()

    val fileUri = Uri.parse("file:///storage/emulated/0/book.mp3")
    val fileSpec = DataSpec(fileUri)
    every { defaultDataSource.open(fileSpec) } returns 100L

    val opened = dataSource.open(fileSpec)
    assertEquals(100L, opened)
    verify(exactly = 1) { defaultDataSource.open(fileSpec) }
  }
}
