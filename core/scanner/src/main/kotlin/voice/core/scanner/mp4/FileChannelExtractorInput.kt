package voice.core.scanner.mp4

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class FileChannelExtractorInput(
  private val channel: FileChannel,
  private val totalLength: Long,
) : ExtractorInput {

  private var peekPosition = 0L

  override fun getPosition(): Long = channel.position()

  override fun getLength(): Long = totalLength

  override fun getPeekPosition(): Long = peekPosition

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val buf = ByteBuffer.wrap(buffer, offset, length)
    val bytesRead = channel.read(buf)
    peekPosition = channel.position()
    return if (bytesRead == -1) C.RESULT_END_OF_INPUT else bytesRead
  }

  override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
    var bytesRead = 0
    while (bytesRead < length) {
      val count = read(target, offset + bytesRead, length - bytesRead)
      if (count == C.RESULT_END_OF_INPUT) {
        if (allowEndOfInput && bytesRead == 0) return false
        throw EOFException("End of input reached while reading $length bytes (read $bytesRead)")
      }
      bytesRead += count
    }
    return true
  }

  override fun readFully(target: ByteArray, offset: Int, length: Int) {
    readFully(target, offset, length, false)
  }

  override fun skip(length: Int): Int {
    val current = channel.position()
    val toSkip = minOf(length.toLong(), totalLength - current)
    val newPos = current + toSkip
    channel.position(newPos)
    peekPosition = newPos
    return toSkip.toInt()
  }

  override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
    val current = channel.position()
    val newPos = current + length
    if (newPos > totalLength) {
      if (allowEndOfInput) return false
      throw EOFException("Cannot skip $length bytes from $current (total length: $totalLength)")
    }
    channel.position(newPos)
    peekPosition = newPos
    return true
  }

  override fun skipFully(length: Int) {
    skipFully(length, false)
  }

  override fun peek(target: ByteArray, offset: Int, length: Int): Int {
    val current = channel.position()
    channel.position(peekPosition)
    val buf = ByteBuffer.wrap(target, offset, length)
    val bytesRead = channel.read(buf)
    peekPosition = channel.position()
    channel.position(current)
    return if (bytesRead == -1) C.RESULT_END_OF_INPUT else bytesRead
  }

  override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
    val current = channel.position()
    channel.position(peekPosition)
    val buf = ByteBuffer.wrap(target, offset, length)
    var bytesRead = 0
    while (bytesRead < length) {
      val count = channel.read(buf)
      if (count == -1) {
        channel.position(current)
        if (allowEndOfInput && bytesRead == 0) return false
        throw EOFException("End of input reached while peeking $length bytes")
      }
      bytesRead += count
    }
    peekPosition = channel.position()
    channel.position(current)
    return true
  }

  override fun peekFully(target: ByteArray, offset: Int, length: Int) {
    peekFully(target, offset, length, false)
  }

  override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
    val newPeek = peekPosition + length
    if (newPeek > totalLength) {
      if (allowEndOfInput) return false
      throw EOFException("Cannot advance peek $length bytes from $peekPosition")
    }
    peekPosition = newPeek
    return true
  }

  override fun advancePeekPosition(length: Int) {
    advancePeekPosition(length, false)
  }

  override fun resetPeekPosition() {
    peekPosition = channel.position()
  }

  override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
    channel.position(position)
    peekPosition = position
    throw e
  }
}
