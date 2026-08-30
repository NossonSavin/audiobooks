package voice.core.scanner

import android.content.Context
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import voice.core.data.Book
import voice.core.data.toUri
import voice.core.logging.api.Logger
import voice.core.documentfile.CachedDocumentFileFactory
import java.io.File
import java.io.IOException

@Inject
internal class CoverScanner(
  private val context: Context,
  private val coverSaver: CoverSaver,
  private val coverExtractor: CoverExtractor,
  private val documentFileFactory: CachedDocumentFileFactory,
) {

  suspend fun scan(books: List<Book>) = coroutineScope {
    val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(6, 12)
    val semaphore = Semaphore(concurrency)
    books.map { book ->
      async {
        semaphore.withPermit {
          findCoverForBook(book)
        }
      }
    }.awaitAll()
    Unit
  }

  private suspend fun findCoverForBook(book: Book) {
    val coverFile = book.content.cover
    if (coverFile != null && coverFile.exists() && coverFile.length() > 0L && isValidImageFile(coverFile)) {
      return
    }

    val foundOnDisc = findAndSaveCoverFromDisc(book)
    if (foundOnDisc) {
      return
    }

    scanForEmbeddedCover(book)
  }

  private suspend fun findAndSaveCoverFromDisc(book: Book): Boolean = withContext(Dispatchers.IO) {
    val documentFile = try {
      documentFileFactory.create(book.id.toUri())
    } catch (_: Exception) {
      null
    } ?: return@withContext false

    if (!documentFile.isDirectory) {
      return@withContext false
    }

    documentFile.children.forEach { child ->
      val name = child.name?.lowercase() ?: ""
      val isImage = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
      if (child.isFile && isImage) {
        val coverFile = coverSaver.newBookCoverFile()
        val worked = try {
          val bytesCopied = context.contentResolver.openInputStream(child.uri)?.use { input ->
            coverFile.outputStream().use { output ->
              input.copyTo(output)
            }
          } ?: 0L
          bytesCopied > 0L && isValidImageFile(coverFile)
        } catch (e: IOException) {
          Logger.w(e, "Error while copying the cover from ${child.uri}")
          false
        } catch (e: IllegalStateException) {
          // On some Samsung Devices, openInputStream throws this exception, though it should not.
          Logger.w(e, "Error while copying the cover from ${child.uri}")
          false
        }
        if (worked) {
          coverSaver.setBookCover(coverFile, book.id)
          return@withContext true
        } else {
          coverFile.delete()
        }
      }
    }

    false
  }

  private suspend fun scanForEmbeddedCover(book: Book) {
    val coverFile = coverSaver.newBookCoverFile()
    book.chapters
      .take(5).forEach { chapter ->
        val success = coverExtractor.extractCover(
          input = chapter.id.toUri(),
          outputFile = coverFile,
        )
        if (success && coverFile.exists() && coverFile.length() > 0L && isValidImageFile(coverFile)) {
          coverSaver.setBookCover(coverFile, bookId = book.id)
          return
        } else {
          coverFile.delete()
        }
      }
    coverFile.delete()
  }

  private fun isValidImageFile(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return options.outWidth > 0 && options.outHeight > 0
  }
}
