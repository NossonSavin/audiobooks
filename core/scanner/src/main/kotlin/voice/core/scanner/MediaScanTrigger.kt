package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.folders.AudiobookFolders
import voice.core.data.repo.BookRepository
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger

public data class ScanProgress(
  val scanned: Int,
  val total: Int,
)

@SingleIn(AppScope::class)
@Inject
public class MediaScanTrigger
internal constructor(
  private val audiobookFolders: AudiobookFolders,
  private val scanner: MediaScanner,
  private val coverScanner: CoverScanner,
  private val bookRepo: BookRepository,
  private val documentFileFactory: CachedDocumentFileFactory,
) {

  public val scannerActive: Flow<Boolean>
    field = MutableStateFlow(false)

  public val scanProgress: Flow<ScanProgress?>
    field = MutableStateFlow<ScanProgress?>(null)

  private val scope = CoroutineScope(Dispatchers.IO)
  private var scanningJob: Job? = null

  public fun triggerScan(restartIfScanning: Boolean = false) {
    Logger.i("scanForFiles with restartIfScanning=$restartIfScanning")
    if (scanningJob?.isActive == true && !restartIfScanning) {
      return
    }
    val oldJob = scanningJob
    scanningJob = scope.launch {
      scannerActive.value = true
      oldJob?.cancelAndJoin()

      val startTime = System.currentTimeMillis()
      val folders: List<CachedDocumentFile> = audiobookFolders.all()
        .first()
        .map {
          documentFileFactory.create(it.documentFile.uri)
        }
      val onProgress: (Int, Int) -> Unit = { scanned, total ->
        scanProgress.value = ScanProgress(scanned = scanned, total = total)
      }
      scanner.performScan(folders, onProgress)
      val duration = System.currentTimeMillis() - startTime
      Logger.i("scan took ${duration}ms")
      scanProgress.value = null
      scannerActive.value = false

      val books = bookRepo.all()
      coverScanner.scan(books)
    }
  }
}
