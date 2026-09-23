package voice.core.playback.session

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SeekableContentDataSourceTest {

  @Test
  fun `reads sequential data correctly`() {
    val tempFile = File.createTempFile("test_audio", ".dat").apply {
      deleteOnExit()
      writeBytes(ByteArray(100) { it.toByte() })
    }
    val uri = Uri.parse("content://test.authority/audio")
    val resolver = mockk<ContentResolver> {
      every { openAssetFileDescriptor(uri, "r") } answers {
        AssetFileDescriptor(
          ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY),
          0,
          tempFile.length(),
        )
      }
    }
    val context = mockk<Context> {
      every { contentResolver } returns resolver
    }

    val dataSource = SeekableContentDataSource(context)
    val dataSpec = DataSpec(uri)
    val length = dataSource.open(dataSpec)
    assertEquals(100L, length)

    val buffer = ByteArray(50)
    val bytesRead = dataSource.read(buffer, 0, 50)
    assertEquals(50, bytesRead)
    assertEquals(0.toByte(), buffer[0])
    assertEquals(49.toByte(), buffer[49])

    dataSource.close()
  }

  @Test
  fun `seeks to non-zero position correctly`() {
    val tempFile = File.createTempFile("test_audio", ".dat").apply {
      deleteOnExit()
      writeBytes(ByteArray(100) { it.toByte() })
    }
    val uri = Uri.parse("content://test.authority/audio")
    val resolver = mockk<ContentResolver> {
      every { openAssetFileDescriptor(uri, "r") } answers {
        AssetFileDescriptor(
          ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY),
          0,
          tempFile.length(),
        )
      }
    }
    val context = mockk<Context> {
      every { contentResolver } returns resolver
    }

    val dataSource = SeekableContentDataSource(context)
    val dataSpec = DataSpec.Builder().setUri(uri).setPosition(20).setLength(30).build()
    val length = dataSource.open(dataSpec)
    assertEquals(30L, length)

    val buffer = ByteArray(30)
    val bytesRead = dataSource.read(buffer, 0, 30)
    assertEquals(30, bytesRead)
    assertEquals(20.toByte(), buffer[0])
    assertEquals(49.toByte(), buffer[29])

    dataSource.close()
  }

  @Test
  fun `returns END_OF_INPUT when reaching end of file`() {
    val tempFile = File.createTempFile("test_audio", ".dat").apply {
      deleteOnExit()
      writeBytes(ByteArray(10) { it.toByte() })
    }
    val uri = Uri.parse("content://test.authority/audio")
    val resolver = mockk<ContentResolver> {
      every { openAssetFileDescriptor(uri, "r") } answers {
        AssetFileDescriptor(
          ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY),
          0,
          tempFile.length(),
        )
      }
    }
    val context = mockk<Context> {
      every { contentResolver } returns resolver
    }

    val dataSource = SeekableContentDataSource(context)
    val dataSpec = DataSpec(uri)
    dataSource.open(dataSpec)

    val buffer = ByteArray(10)
    assertEquals(10, dataSource.read(buffer, 0, 10))
    assertEquals(C.RESULT_END_OF_INPUT, dataSource.read(buffer, 0, 10))

    dataSource.close()
  }
}
