package voice.core.playback.session

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.net.URLDecoder

internal fun Uri.toFastPlaybackUri(): Uri {
  if (scheme == "file") return this
  if (scheme == "content" && authority == "com.android.externalstorage.documents") {
    try {
      val docId = try {
        DocumentsContract.getDocumentId(this)
      } catch (_: Exception) {
        val decoded = URLDecoder.decode(toString(), "UTF-8")
        decoded.substringAfterLast("/document/")
          .takeIf { it.isNotEmpty() && it != decoded }
          ?: decoded.substringAfterLast("/tree/")
      }
      if (!docId.isNullOrEmpty()) {
        val file = if (docId.startsWith("primary:", ignoreCase = true)) {
          val relativePath = docId.substringAfter(":")
          File(Environment.getExternalStorageDirectory(), relativePath)
        } else if (docId.contains(":")) {
          val split = docId.split(":", limit = 2)
          File("/storage/${split[0]}", split[1])
        } else {
          null
        }
        if (file != null && file.exists() && file.canRead()) {
          android.util.Log.i("VOICE_PERF", "[FastUriResolver] Converted SAF URI to direct file URI: ${file.absolutePath}")
          return Uri.fromFile(file)
        } else {
          android.util.Log.i("VOICE_PERF", "[FastUriResolver] File path ${file?.absolutePath} exists=${file?.exists()} canRead=${file?.canRead()}")
        }
      }
    } catch (e: Exception) {
      android.util.Log.w("VOICE_PERF", "[FastUriResolver] Failed to resolve fast URI: $e")
    }
  }
  return this
}
