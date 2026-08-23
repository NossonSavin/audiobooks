package voice.core.playback.session

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class FastContentDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ false) {

  private val resolver: ContentResolver = context.contentResolver
  private var uri: Uri? = null
  private var assetFileDescriptor: AssetFileDescriptor? = null
  private var inputStream: FileInputStream? = null
  private var bytesRemaining: Long = 0
  private var opened = false

  private var startOffset: Long = 0
  private var currentPosition: Long = 0
  private var fastSkipEndOffset: Long = -1

  // 512 KB buffer to batch small 4KB reads from DefaultExtractorInput into single disk reads
  private val readBuffer = ByteArray(512 * 1024)
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
      val afd = resolver.openAssetFileDescriptor(dataSpec.uri, "r")
        ?: throw IOException("Could not open asset file descriptor for ${dataSpec.uri}")
      assetFileDescriptor = afd

      val fd = afd.fileDescriptor
      val stream = FileInputStream(fd)
      inputStream = stream

      startOffset = afd.startOffset
      val targetOffset = startOffset + dataSpec.position
      currentPosition = dataSpec.position
      fastSkipEndOffset = -1
      readBufferPos = 0
      readBufferLimit = 0

      // Only track atom boundaries when starting at position 0
      expectedAtomOffset = if (dataSpec.position == 0L) 0L else -1L
      atomHeaderBytesRead = 0

      try {
        Os.lseek(fd, targetOffset, OsConstants.SEEK_SET)
      } catch (_: Exception) {
        stream.channel.position(targetOffset)
      }
      stream.channel.position(targetOffset)

      val afdLength = afd.length
      val totalFileSize = if (afdLength != AssetFileDescriptor.UNKNOWN_LENGTH) {
        afdLength
      } else {
        stream.channel.size()
      }

      bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
        dataSpec.length
      } else if (totalFileSize > 0) {
        val remaining = totalFileSize - dataSpec.position
        if (remaining < 0) throw EOFException()
        remaining
      } else {
        C.LENGTH_UNSET.toLong()
      }

      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      android.util.Log.i("VOICE_PERF", "[FastContentDataSource] open(position=${dataSpec.position}, length=${dataSpec.length}) took ${elapsedMs}ms")
    } catch (e: Exception) {
      throw IOException("Error opening FastContentDataSource for ${dataSpec.uri}", e)
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

    val stream = inputStream ?: return C.RESULT_END_OF_INPUT

    // If we are currently in an active fast-skip over non-moov atom payloads (mdat, cover art, etc.)
    if (fastSkipEndOffset > 0 && currentPosition < fastSkipEndOffset) {
      val remainingToSkip = fastSkipEndOffset - currentPosition
      val count = Math.min(bytesToRead.toLong(), remainingToSkip).toInt()
      currentPosition += count

      if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
        bytesRemaining -= count
      }

      // When fast-skip finishes, seek the physical file descriptor to fastSkipEndOffset
      if (currentPosition >= fastSkipEndOffset) {
        readBufferPos = 0
        readBufferLimit = 0
        val fd = assetFileDescriptor?.fileDescriptor
        val targetPhysicalOffset = startOffset + fastSkipEndOffset
        if (fd != null) {
          try {
            Os.lseek(fd, targetPhysicalOffset, OsConstants.SEEK_SET)
          } catch (_: Exception) {}
        }
        try {
          stream.channel.position(targetPhysicalOffset)
        } catch (_: Exception) {}
        android.util.Log.i("VOICE_PERF", "[FastContentDataSource] Fast-skip completed! Jumped file to $fastSkipEndOffset")
      }

      bytesTransferred(count)
      return count
    }

    // Refill readBuffer from disk in 512KB chunks if empty
    if (readBufferPos >= readBufferLimit) {
      readBufferPos = 0
      val count = stream.read(readBuffer, 0, readBuffer.size)
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

    // Track exact MP4 atom boundaries at expectedAtomOffset
    if (expectedAtomOffset >= 0 && currentPosition + bytesRead > expectedAtomOffset && currentPosition < expectedAtomOffset + 8) {
      val headerOffsetInRead = (expectedAtomOffset - currentPosition).toInt()
      for (i in 0 until bytesRead) {
        val streamPos = currentPosition + i
        if (streamPos >= expectedAtomOffset && streamPos < expectedAtomOffset + 8) {
          val idx = (streamPos - expectedAtomOffset).toInt()
          atomHeaderBuffer[idx] = buffer[offset + i]
          atomHeaderBytesRead++

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
                // Large non-moov atom (mdat, covr, free, skip, etc.) -> fast-skip payload in 0ms!
                fastSkipEndOffset = expectedAtomOffset + atomSize
                expectedAtomOffset = fastSkipEndOffset
                atomHeaderBytesRead = 0
                readBufferPos = 0
                readBufferLimit = 0
                android.util.Log.i(
                  "VOICE_PERF",
                  "[FastContentDataSource] Intercepted '$typeStr' atom at ${expectedAtomOffset - atomSize}! Skipping ${atomSize / 1024 / 1024}MB (${atomSize}B) to $fastSkipEndOffset in 0ms!",
                )
              } else {
                expectedAtomOffset += atomSize
                atomHeaderBytesRead = 0
                android.util.Log.i("VOICE_PERF", "[FastContentDataSource] Atom '$typeStr' at size $atomSize. Next atom at $expectedAtomOffset")
              }
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
    try {
      inputStream?.close()
    } finally {
      inputStream = null
      try {
        assetFileDescriptor?.close()
      } finally {
        assetFileDescriptor = null
        if (opened) {
          opened = false
          transferEnded()
        }
      }
    }
  }

  internal class Factory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = FastContentDataSource(context)
  }
}
