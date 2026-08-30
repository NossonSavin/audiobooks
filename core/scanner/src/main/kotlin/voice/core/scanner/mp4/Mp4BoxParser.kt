package voice.core.scanner.mp4

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.ExtractorInput
import dev.zacsweers.metro.Inject
import voice.core.logging.api.Logger
import voice.core.scanner.mp4.visitor.ChapVisitor
import voice.core.scanner.mp4.visitor.ChplVisitor
import voice.core.scanner.mp4.visitor.MdhdVisitor
import voice.core.scanner.mp4.visitor.StcoVisitor
import voice.core.scanner.mp4.visitor.StscVisitor
import voice.core.scanner.mp4.visitor.SttsVisitor

import voice.core.scanner.mp4.visitor.Co64Visitor

@Inject
internal class Mp4BoxParser(
  stscVisitor: StscVisitor,
  mdhdVisitor: MdhdVisitor,
  sttsVisitor: SttsVisitor,
  stcoVisitor: StcoVisitor,
  co64Visitor: Co64Visitor,
  chplVisitor: ChplVisitor,
  chapVisitor: ChapVisitor,
) {

  private val visitors = listOf(
    stscVisitor,
    mdhdVisitor,
    sttsVisitor,
    stcoVisitor,
    co64Visitor,
    chplVisitor,
    chapVisitor,
  )
  private val visitorByPath = visitors.associateBy { it.path }

  operator fun invoke(input: ExtractorInput): Mp4ChpaterExtractorOutput {
    val scratch = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
    val parseOutput = Mp4ChpaterExtractorOutput()
    parseBoxes(
      input = input,
      path = emptyList(),
      parentEnd = Long.MAX_VALUE,
      scratch = scratch,
      parseOutput = parseOutput,
    )
    return parseOutput
  }

  private fun parseBoxes(
    input: ExtractorInput,
    path: List<String>,
    parentEnd: Long,
    scratch: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  ) {
    while (input.position < parentEnd) {
      scratch.reset(Mp4Box.HEADER_SIZE)
      if (!input.readFully(scratch.data, 0, Mp4Box.HEADER_SIZE, true)) {
        return
      }

      var atomSize = scratch.readUnsignedInt()
      val atomType = scratch.readString(4)
      var headerSize = Mp4Box.HEADER_SIZE

      if (atomSize == 1L) {
        input.readFully(
          scratch.data,
          Mp4Box.HEADER_SIZE,
          Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE,
        )
        scratch.setPosition(Mp4Box.HEADER_SIZE)
        atomSize = scratch.readUnsignedLongToLong()
        headerSize = Mp4Box.LONG_HEADER_SIZE
      }

      val payloadSize: Long = if (atomSize == 0L) {
        parentEnd - input.position
      } else {
        atomSize - headerSize
      }
      val payloadEnd = input.position + payloadSize
      Logger.d("Current path: $path + $atomType, payloadSize: $payloadSize")

      val currentPath = path + atomType
      val visitor = visitorByPath[currentPath]

      when {
        visitor != null -> {
          Logger.v("Found ${visitor.path.last()}!")
          val intPayloadSize = payloadSize.toInt()
          scratch.reset(intPayloadSize)
          if (!input.readFully(scratch.data, 0, intPayloadSize, true)) {
            return
          }
          visitor.visit(scratch, parseOutput)

          if (parseOutput.chplChapters.isNotEmpty()) {
            return
          }
        }
        visitors.any { it.path.startsWith(currentPath) } -> {
          parseBoxes(
            input = input,
            path = currentPath,
            parentEnd = payloadEnd,
            scratch = scratch,
            parseOutput = parseOutput,
          )

          if (parseOutput.chplChapters.isNotEmpty()) {
            return
          }
        }
        else -> {
          var remaining = payloadSize
          while (remaining > 0) {
            val toSkip = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            if (!input.skipFully(toSkip, true)) {
              return
            }
            remaining -= toSkip
          }
        }
      }

      if (input.position < payloadEnd) {
        var remaining = payloadEnd - input.position
        while (remaining > 0) {
          val toSkip = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
          if (!input.skipFully(toSkip, true)) {
            return
          }
          remaining -= toSkip
        }
      }
    }
  }

  private fun List<String>.startsWith(other: List<String>): Boolean {
    return take(other.size) == other
  }
}
