package voice.features.folderPicker.folderPicker

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.documentfile.nameWithoutExtension
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

@Inject
class FolderPickerViewModel(
  private val audiobookFolders: AudiobookFolders,
  private val navigator: Navigator,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
) {

  @Composable
  fun viewState(): FolderPickerViewState {
    val kioskMode = remember { kioskModeFeatureFlag.get() }
    if (kioskMode) {
      return FolderPickerViewState(
        items = kioskModeItems,
        showActions = false,
      )
    }

    val folders: List<FolderPickerViewState.Item> by remember {
      items()
    }.collectAsState(initial = emptyList())
    return FolderPickerViewState(folders)
  }

  private fun items(): Flow<List<FolderPickerViewState.Item>> {
    return audiobookFolders.all().map { folders ->
      withContext(Dispatchers.IO) {
        folders.map { (documentFile, uri) ->
          FolderPickerViewState.Item(
            name = documentFile.nameWithoutExtension(),
            id = uri,
            folderType = if (documentFile.isFile) FolderType.File else FolderType.Folder,
          )
        }.sortedDescending()
      }
    }
  }

  internal fun onCloseClick() {
    navigator.goBack()
  }

  internal fun add() {
    navigator.goTo(
      Destination.AddContent(
        Origin.Default,
      ),
    )
  }

  private val scope = kotlinx.coroutines.MainScope()

  fun removeFolder(item: FolderPickerViewState.Item) {
    scope.launch {
      audiobookFolders.remove(item.id, item.folderType)
    }
  }

  private companion object {
    val kioskModeItems = listOf(
      FolderPickerViewState.Item(
        name = "Audiobooks",
        id = Uri.EMPTY,
        folderType = FolderType.Folder,
      ),
      FolderPickerViewState.Item(
        name = "Sci-Fi",
        id = Uri.EMPTY,
        folderType = FolderType.Folder,
      ),
      FolderPickerViewState.Item(
        name = "Non-Fiction",
        id = Uri.EMPTY,
        folderType = FolderType.Folder,
      ),
    )
  }
}
