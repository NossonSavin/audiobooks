package voice.core.playback.session

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class SeekableContentDataSource(
  private val context: Context,
) : BaseDataSource(/* isNetwork = */ false) {

  private val resolver: ContentResolver = context.contentResolver
  private var uri: Uri? = null
  private var assetFileDescriptor: AssetFileDescriptor? = null
  private var inputStream: FileInputStream? = null
  private var channel: FileChannel? = null
  private var bytesRemaining: Long = 0
  private var opened = false

  private val readBuffer = ByteArray(1024 * 1024)
  private var readBufferStart: Long = -1L
  private var readBufferEnd: Long = -1L
  private var currentChannelPosition: Long = 0L

  override fun open(dataSpec: DataSpec): Long {
    if (opened) {
      close()
    }
    readBufferStart = -1L
    readBufferEnd = -1L
    try {
      uri = dataSpec.uri
      transferInitializing(dataSpec)

      val afd = resolver.openAssetFileDescriptor(dataSpec.uri, "r")
        ?: throw DataSourceException(
          FileNotFoundException("Could not open asset file descriptor for ${dataSpec.uri}"),
          PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
      assetFileDescriptor = afd

      val stream = FileInputStream(afd.fileDescriptor)
      inputStream = stream
      val fileChannel = stream.channel
      channel = fileChannel

      val startOffset = afd.startOffset
      val targetOffset = startOffset + dataSpec.position
      currentChannelPosition = targetOffset

      val afdLength = afd.length
      val totalSize = if (afdLength != AssetFileDescriptor.UNKNOWN_LENGTH) {
        afdLength
      } else {
        val channelSize = fileChannel.size()
        if (channelSize > 0) channelSize - startOffset else -1L
      }

      if (totalSize in 0..dataSpec.position) {
        throw DataSourceException(
          EOFException("Position ${dataSpec.position} exceeds totalSize $totalSize"),
          PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
      }

      fileChannel.position(targetOffset)

      bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
        dataSpec.length
      } else if (totalSize >= 0) {
        val remaining = totalSize - dataSpec.position
        if (remaining < 0) {
          throw DataSourceException(
            EOFException(),
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
          )
        }
        remaining
      } else {
        C.LENGTH_UNSET.toLong()
      }
    } catch (e: DataSourceException) {
      throw e
    } catch (e: IOException) {
      throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    opened = true
    transferStarted(dataSpec)
    return bytesRemaining
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

    val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
      length
    } else {
      Math.min(bytesRemaining, length.toLong()).toInt()
    }
    if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

    val fileChannel = channel ?: return C.RESULT_END_OF_INPUT

    // Serve from readBuffer if hit
    if (currentChannelPosition in readBufferStart until readBufferEnd) {
      val available = (readBufferEnd - currentChannelPosition).toInt()
      val toCopy = Math.min(bytesToRead, available)
      val bufferOffset = (currentChannelPosition - readBufferStart).toInt()
      System.arraycopy(readBuffer, bufferOffset, buffer, offset, toCopy)
      currentChannelPosition += toCopy
      if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
        bytesRemaining -= toCopy
      }
      bytesTransferred(toCopy)
      return toCopy
    }

    // Buffer miss: if requesting more than buffer size, read directly
    if (bytesToRead >= readBuffer.size) {
      readBufferStart = -1L
      readBufferEnd = -1L
      fileChannel.position(currentChannelPosition)
      val byteBuffer = ByteBuffer.wrap(buffer, offset, bytesToRead)
      val bytesRead = try {
        fileChannel.read(byteBuffer)
      } catch (e: IOException) {
        throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
      }
      if (bytesRead <= 0) return C.RESULT_END_OF_INPUT
      currentChannelPosition += bytesRead
      if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
        bytesRemaining -= bytesRead
      }
      bytesTransferred(bytesRead)
      return bytesRead
    }

    // Otherwise, fill readBuffer
    fileChannel.position(currentChannelPosition)
    val byteBuffer = ByteBuffer.wrap(readBuffer)
    val bytesRead = try {
      fileChannel.read(byteBuffer)
    } catch (e: IOException) {
      throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }
    if (bytesRead <= 0) {
      readBufferStart = -1L
      readBufferEnd = -1L
      return C.RESULT_END_OF_INPUT
    }

    readBufferStart = currentChannelPosition
    readBufferEnd = currentChannelPosition + bytesRead

    val toCopy = Math.min(bytesToRead, bytesRead)
    System.arraycopy(readBuffer, 0, buffer, offset, toCopy)
    currentChannelPosition += toCopy
    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
      bytesRemaining -= toCopy
    }
    bytesTransferred(toCopy)
    return toCopy
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    uri = null
    readBufferStart = -1L
    readBufferEnd = -1L
    currentChannelPosition = 0L
    try {
      channel?.close()
    } catch (_: IOException) {
    } finally {
      channel = null
      try {
        inputStream?.close()
      } catch (_: IOException) {
      } finally {
        inputStream = null
        try {
          assetFileDescriptor?.close()
        } catch (_: IOException) {
        } finally {
          assetFileDescriptor = null
          if (opened) {
            opened = false
            transferEnded()
          }
        }
      }
    }
  }

  class Factory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = SeekableContentDataSource(context)
  }
}
