package voice.core.playback.session

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal class FastFileDataSource : BaseDataSource(/* isNetwork = */ false) {

  private var uri: Uri? = null
  private var file: RandomAccessFile? = null
  private var bytesRemaining: Long = 0
  private var opened = false

  // 1 MB buffer to read large blocks from flash storage in a single syscall
  private val readBuffer = ByteArray(1024 * 1024)
  private var readBufferPos = 0
  private var readBufferLimit = 0

  override fun open(dataSpec: DataSpec): Long {
    try {
      uri = dataSpec.uri
      transferInitializing(dataSpec)

      val startNs = System.nanoTime()
      val path = dataSpec.uri.path ?: throw IOException("Invalid file path in URI: ${dataSpec.uri}")
      val raf = RandomAccessFile(File(path), "r")
      file = raf

      raf.seek(dataSpec.position)
      readBufferPos = 0
      readBufferLimit = 0

      val fileLength = raf.length()
      bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
        dataSpec.length
      } else {
        val remaining = fileLength - dataSpec.position
        if (remaining < 0) throw EOFException()
        remaining
      }

      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      android.util.Log.i("VOICE_PERF", "[FastFileDataSource] open(position=${dataSpec.position}, length=${dataSpec.length}) took ${elapsedMs}ms")
    } catch (e: Exception) {
      throw IOException("Error opening FastFileDataSource for ${dataSpec.uri}", e)
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

    val raf = file ?: return C.RESULT_END_OF_INPUT

    // Refill 1MB buffer from disk if empty
    if (readBufferPos >= readBufferLimit) {
      readBufferPos = 0
      val count = raf.read(readBuffer, 0, readBuffer.size)
      if (count <= 0) {
        readBufferLimit = 0
        return C.RESULT_END_OF_INPUT
      }
      readBufferLimit = count
    }

    val available = readBufferLimit - readBufferPos
    val bytesRead = Math.min(bytesToRead, available)
    System.arraycopy(readBuffer, readBufferPos, buffer, offset, bytesRead)
    readBufferPos += bytesRead

    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
      bytesRemaining -= bytesRead
    }
    bytesTransferred(bytesRead)
    return bytesRead
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    uri = null
    readBufferPos = 0
    readBufferLimit = 0
    try {
      file?.close()
    } finally {
      file = null
      if (opened) {
        opened = false
        transferEnded()
      }
    }
  }

  internal class Factory : DataSource.Factory {
    override fun createDataSource(): DataSource = FastFileDataSource()
  }
}
