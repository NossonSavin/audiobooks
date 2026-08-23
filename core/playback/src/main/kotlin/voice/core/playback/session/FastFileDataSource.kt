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
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class FastFileDataSource : BaseDataSource(/* isNetwork = */ false) {

  private var uri: Uri? = null
  private var file: RandomAccessFile? = null
  private var bytesRemaining: Long = 0
  private var opened = false

  private var currentPosition: Long = 0
  private var fastSkipEndOffset: Long = -1

  // 8 MB buffer for reading the full header/moov atom in 1 syscall on position 0
  private val headerBuffer = ByteArray(8 * 1024 * 1024)
  // 64 KB low-latency buffer for instant audio streaming when seeking
  private val audioBuffer = ByteArray(64 * 1024)
  
  private var isHeaderPhase = false
  private var readBufferPos = 0
  private var readBufferLimit = 0

  // Exact atom hierarchy tracking (only at discrete atom boundaries)
  private var expectedAtomOffset: Long = 0
  private val atomHeaderBuffer = ByteArray(8)
  private var atomHeaderBytesRead = 0

  override fun open(dataSpec: DataSpec): Long {
    try {
      uri = dataSpec.uri
      transferInitializing(dataSpec)

      val startNs = System.nanoTime()
      val path = dataSpec.uri.path ?: throw IOException("Invalid file path in URI: ${dataSpec.uri}")
      val raf = RandomAccessFile(File(path), "r")
      file = raf

      raf.seek(dataSpec.position)
      currentPosition = dataSpec.position
      fastSkipEndOffset = -1
      readBufferPos = 0
      readBufferLimit = 0

      isHeaderPhase = (dataSpec.position == 0L)
      expectedAtomOffset = if (isHeaderPhase) 0L else -1L
      atomHeaderBytesRead = 0

      // If opening from beginning, pre-fetch up to 8MB header in 1 single disk read
      if (isHeaderPhase) {
        val count = raf.read(headerBuffer, 0, headerBuffer.size)
        if (count > 0) {
          readBufferLimit = count
          readBufferPos = 0
        }
      }

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

    // If we are currently in an active fast-skip over the mdat audio payload
    if (fastSkipEndOffset > 0 && currentPosition < fastSkipEndOffset) {
      val remainingToSkip = fastSkipEndOffset - currentPosition
      val count = Math.min(bytesToRead.toLong(), remainingToSkip).toInt()
      currentPosition += count

      if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
        bytesRemaining -= count
      }

      // When fast-skip finishes, seek the physical file to fastSkipEndOffset
      if (currentPosition >= fastSkipEndOffset) {
        readBufferPos = 0
        readBufferLimit = 0
        try {
          raf.seek(fastSkipEndOffset)
        } catch (_: Exception) {}
        android.util.Log.i("VOICE_PERF", "[FastFileDataSource] Fast-skip completed! Jumped file to $fastSkipEndOffset")
      }

      bytesTransferred(count)
      return count
    }

    val activeBuffer = if (isHeaderPhase) headerBuffer else audioBuffer

    // Refill buffer from disk if empty
    if (readBufferPos >= readBufferLimit) {
      readBufferPos = 0
      val count = raf.read(activeBuffer, 0, activeBuffer.size)
      if (count <= 0) {
        readBufferLimit = 0
        return C.RESULT_END_OF_INPUT
      }
      readBufferLimit = count
    }

    val available = readBufferLimit - readBufferPos
    val bytesRead = Math.min(bytesToRead, available)
    System.arraycopy(activeBuffer, readBufferPos, buffer, offset, bytesRead)
    readBufferPos += bytesRead

    // Direct zero-overhead atom boundary inspection (Only during header phase)
    if (isHeaderPhase && expectedAtomOffset in currentPosition until currentPosition + bytesRead) {
      val offsetInBuffer = (expectedAtomOffset - currentPosition).toInt()
      val bytesToCopy = Math.min(8 - atomHeaderBytesRead, bytesRead - offsetInBuffer)
      if (bytesToCopy > 0) {
        System.arraycopy(buffer, offset + offsetInBuffer, atomHeaderBuffer, atomHeaderBytesRead, bytesToCopy)
        atomHeaderBytesRead += bytesToCopy

        if (atomHeaderBytesRead == 8) {
          val bb = ByteBuffer.wrap(atomHeaderBuffer).order(ByteOrder.BIG_ENDIAN)
          val atomSize = bb.int.toLong() and 0xFFFFFFFFL
          val b4 = atomHeaderBuffer[4].toInt() and 0xFF
          val b5 = atomHeaderBuffer[5].toInt() and 0xFF
          val b6 = atomHeaderBuffer[6].toInt() and 0xFF
          val b7 = atomHeaderBuffer[7].toInt() and 0xFF
          val typeStr = "${b4.toChar()}${b5.toChar()}${b6.toChar()}${b7.toChar()}"

          val isMoov = typeStr == "moov"
          val isFtyp = typeStr == "ftyp"

          if (atomSize > 0) {
            if (!isMoov && !isFtyp && atomSize > 50_000L) {
              // Large non-moov atom (mdat) -> fast-skip payload in 0ms!
              fastSkipEndOffset = expectedAtomOffset + atomSize
              expectedAtomOffset = fastSkipEndOffset
              atomHeaderBytesRead = 0
              readBufferPos = 0
              readBufferLimit = 0
              android.util.Log.i(
                "VOICE_PERF",
                "[FastFileDataSource] Intercepted '$typeStr' atom at ${expectedAtomOffset - atomSize}! Skipping ${atomSize / 1024 / 1024}MB to $fastSkipEndOffset in 0ms!",
              )
            } else {
              expectedAtomOffset += atomSize
              atomHeaderBytesRead = 0
              android.util.Log.i("VOICE_PERF", "[FastFileDataSource] Atom '$typeStr' at size $atomSize. Next atom at $expectedAtomOffset")
            }
          }
        }
      }
    }

    currentPosition += bytesRead
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
    isHeaderPhase = false
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
