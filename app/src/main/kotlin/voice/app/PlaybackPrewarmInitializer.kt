package voice.app

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import voice.core.initializer.AppInitializer
import voice.core.playback.PlayerController

@Inject
@ContributesIntoSet(AppScope::class)
class PlaybackPrewarmInitializer(
  private val playerController: PlayerController,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    android.util.Log.i("VOICE_PERF", "[PlaybackPrewarmInitializer] Prewarming playback in background...")
    playerController.prewarm()
  }
}
