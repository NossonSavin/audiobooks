package voice.app.navigation

import android.content.Intent
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import voice.app.MainActivity
import voice.core.data.BookId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.OnboardingCompletedStore
import voice.core.playback.PlayerController
import voice.navigation.Destination

@Inject
class StartDestinationProvider(
  @OnboardingCompletedStore
  private val onboardingCompletedStore: DataStore<Boolean>,
  private val audiobookFolders: AudiobookFolders,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  private val playerController: PlayerController,
) {

  operator fun invoke(intent: Intent): List<Destination.Compose> {
    val t0 = System.currentTimeMillis()
    android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] invoke started")
    val showOnboarding = runBlocking { showOnboarding() }
    if (showOnboarding) {
      android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] showOnboarding returned true (${System.currentTimeMillis() - t0}ms)")
      return listOf(Destination.OnboardingWelcome)
    }

    val goToBook = intent.getBooleanExtra(MainActivity.Companion.NI_GO_TO_BOOK, false)
    if (goToBook) {
      val bookId = runBlocking { currentBookStore.data.first() }
      if (bookId != null) {
        android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] goToBook with bookId=$bookId (${System.currentTimeMillis() - t0}ms)")
        return listOf(Destination.BookOverview, Destination.Playback(bookId))
      }
    }

    if (intent.action == "playCurrent") {
      val bookId = runBlocking { currentBookStore.data.first() }
      if (bookId != null) {
        android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] playCurrent with bookId=$bookId (${System.currentTimeMillis() - t0}ms)")
        playerController.playPause()
        return listOf(Destination.BookOverview, Destination.Playback(bookId))
      }
    }

    val lastBookId = runBlocking { currentBookStore.data.first() }
    if (lastBookId != null) {
      val tRepo = System.currentTimeMillis()
      val bookExists = runBlocking { bookRepository.get(lastBookId) != null }
      android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] bookRepository.get took ${System.currentTimeMillis() - tRepo}ms")
      if (bookExists) {
        android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] returning Playback($lastBookId) (Total=${System.currentTimeMillis() - t0}ms)")
        return listOf(Destination.BookOverview, Destination.Playback(lastBookId))
      }
    }

    android.util.Log.i("VOICE_PERF", "[StartDestinationProvider] returning BookOverview (Total=${System.currentTimeMillis() - t0}ms)")
    return listOf(Destination.BookOverview)
  }

  private suspend fun showOnboarding(): Boolean {
    return when {
      onboardingCompletedStore.data.first() -> false
      audiobookFolders.hasAnyFolders() -> false
      else -> true
    }
  }
}
