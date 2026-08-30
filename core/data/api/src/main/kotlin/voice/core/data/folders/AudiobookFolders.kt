package voice.core.data.folders

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import voice.core.documentfile.CachedDocumentFile

public interface AudiobookFolders {
  public fun all(): Flow<List<DocumentFileWithUri>>

  public suspend fun add(
    uri: Uri,
    type: FolderType,
  )

  public suspend fun remove(
    uri: Uri,
    type: FolderType,
  )

  public suspend fun hasAnyFolders(): Boolean
}

public data class DocumentFileWithUri(
  val documentFile: CachedDocumentFile,
  val uri: Uri,
)
