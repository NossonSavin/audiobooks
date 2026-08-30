package voice.features.folderPicker.addcontent

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.data.store.OnboardingCompletedStore
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

@AssistedInject
class AddContentViewModel(
  private val audiobookFolders: AudiobookFolders,
  private val navigator: Navigator,
  @OnboardingCompletedStore
  private val onboardingCompletedStore: DataStore<Boolean>,
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope()

  internal fun add(uri: Uri) {
    audiobookFolders.add(
      uri = uri,
      type = FolderType.Folder,
    )
    when (origin) {
      Origin.Default -> {
        navigator.setRoot(Destination.BookOverview)
      }
      Origin.Onboarding -> {
        scope.launch {
          onboardingCompletedStore.updateData { true }
        }
        navigator.setRoot(Destination.BookOverview)
      }
    }
  }

  internal fun back() {
    when (origin) {
      Origin.Default -> navigator.goBack()
      Origin.Onboarding -> navigator.setRoot(Destination.BookOverview)
    }
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): AddContentViewModel
  }
}
