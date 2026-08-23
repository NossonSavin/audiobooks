package voice.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.HideCoverFromSystemStore
import voice.core.logging.api.Logger
import voice.core.playback.misc.Decibel
import voice.core.playback.session.CustomCommand
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.PlaybackService
import voice.core.playback.session.bookId
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.sendCustomCommand
import voice.core.playback.session.toMediaIdOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Inject
class PlayerController(
  private val context: Context,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @HideCoverFromSystemStore
  private val hideCoverFromSystemStore: DataStore<Boolean>,
  private val bookRepository: BookRepository,
  private val mediaItemProvider: MediaItemProvider,
) {

  private var _controller: Deferred<MediaController> = newControllerAsync()

  private fun newControllerAsync() = MediaController
    .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
    .buildAsync()
    .asDeferred()

  private val controller: Deferred<MediaController>
    get() {
      if (_controller.isCompleted) {
        val completedController = _controller.getCompleted()
        if (!completedController.isConnected) {
          completedController.release()
          _controller = newControllerAsync()
        }
      }
      return _controller
    }
  private val scope = CoroutineScope(Dispatchers.Main.immediate)

  fun setPosition(
    time: Long,
    id: ChapterId,
  ) = executeAfterPrepare { controller ->
    val bookId = currentBookStoreId.data.first() ?: return@executeAfterPrepare
    val book = bookRepository.get(bookId) ?: return@executeAfterPrepare
    val playbackItem = book.playbackItemForPosition(
      chapterId = id,
      positionInChapterMs = time,
    )
    if (playbackItem != null) {
      controller.seekTo(playbackItem.index, playbackItem.positionInMediaItem(time))
    }
  }

  fun prewarm() {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (maybePrepare(controller)) {
        android.util.Log.i("VOICE_PERF", "[PlayerController] Prewarm completed successfully")
      }
    }
  }

  fun pauseIfCurrentBookDifferentFrom(id: BookId) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      val currentBookId = controller.currentBookId()
      if (currentBookId != null && currentBookId != id) {
        controller.pause()
      }
    }
  }

  fun pause() = executeAfterPrepare { controller ->
    controller.pause()
  }

  fun skipSilence(skip: Boolean) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetSkipSilence(skip))
  }

  fun refreshMediaItem() = executeAfterPrepare { controller ->
    val bookId = currentBookStoreId.data.first() ?: return@executeAfterPrepare
    val book = bookRepository.get(bookId) ?: return@executeAfterPrepare
    val hideCoverFromSystem = hideCoverFromSystemStore.data.first()

    val currentMediaItem = controller.currentMediaItem
    if (currentMediaItem != null) {
      val newItem = mediaItemProvider.mediaItem(book, hideCoverFromSystem)
      controller.replaceMediaItem(controller.currentMediaItemIndex, newItem)
    }
  }

  fun fastForward() = executeAfterPrepare { controller ->
    controller.seekForward()
  }

  fun rewind() = executeAfterPrepare { controller ->
    controller.seekBack()
  }

  fun previous() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToPrevious)
  }

  fun next() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToNext)
  }

  fun play() = executeAfterPrepare { controller ->
    controller.play()
  }

  fun playPause() = executeAfterPrepare { controller ->
    if (controller.isPlaying) {
      controller.pause()
    } else {
      controller.play()
    }
  }

  private suspend fun maybePrepare(controller: MediaController): Boolean {
    val t0 = System.currentTimeMillis()
    val bookId = currentBookStoreId.data.first() ?: return false
    val currentBookId = controller.currentBookId()
    android.util.Log.i("VOICE_PERF", "[PlayerController] maybePrepare: targetBookId=$bookId, controllerBookId=$currentBookId, state=${controller.playbackState}")
    if (currentBookId == bookId &&
      controller.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
    ) {
      android.util.Log.i("VOICE_PERF", "[PlayerController] maybePrepare: already ready/buffering!")
      return true
    }
    val tRepo = System.currentTimeMillis()
    val book = bookRepository.get(bookId) ?: return false
    android.util.Log.i("VOICE_PERF", "[PlayerController] maybePrepare: bookRepo.get took ${System.currentTimeMillis() - tRepo}ms")
    val hideCoverFromSystem = hideCoverFromSystemStore.data.first()
    val item = mediaItemProvider.mediaItem(book, hideCoverFromSystem)
    controller.setMediaItem(item)
    controller.prepare()
    android.util.Log.i("VOICE_PERF", "[PlayerController] maybePrepare: setMediaItem+prepare sent via IPC in ${System.currentTimeMillis() - t0}ms")
    return true
  }

  private fun MediaController.currentBookId(): BookId? {
    val currentMediaItem = currentMediaItem ?: return null
    val mediaId = currentMediaItem.mediaId.toMediaIdOrNull() ?: return null
    return mediaId.bookId
  }

  fun pauseWithRewind(rewind: Duration) = executeAfterPrepare { controller ->
    controller.pause()
    controller.seekBackBy(
      rewind = rewind,
      crossMediaItems = false,
    )
  }

  private fun MediaController.seekBackBy(
    rewind: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?: return
    var remaining = rewind
    var mediaItemIndex = currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        seekTo(0)
        return
      }
      currentPosition = getMediaItemAt(previousMediaItemIndex).mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  fun setSpeed(speed: Float) = executeAfterPrepare { controller ->
    controller.setPlaybackSpeed(speed)
  }

  fun setGain(gain: Decibel) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetGain(gain))
  }

  fun setVolume(volume: Float) = executeAfterPrepare {
    require(volume in 0F..1F)
    it.volume = volume
  }

  suspend fun livePlaybackState(bookId: BookId? = null): LivePlaybackState? {
    val controller = awaitConnect() ?: return null
    return controller.livePlaybackStateSnapshot(bookId)
  }

  fun livePlaybackStateFlow(bookId: BookId? = null): Flow<LivePlaybackState?> = callbackFlow {
    val controller = awaitConnect()
    if (controller == null) {
      trySend(null)
      close()
      return@callbackFlow
    }

    fun emitSnapshot() {
      trySend(controller.livePlaybackStateSnapshot(bookId))
    }

    var tickJob: Job? = null
    fun updateTicking() {
      if (!controller.isPlaying) {
        tickJob?.cancel()
        return
      }
      if (tickJob?.isActive == true) {
        return
      }
      tickJob = launch {
        while (isActive) {
          delay(250.milliseconds)
          emitSnapshot()
        }
      }
    }

    val listener = object : Player.Listener {
      override fun onEvents(
        player: Player,
        events: Player.Events,
      ) {
        if (events.containsAny(
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
          )
        ) {
          emitSnapshot()
          updateTicking()
        }
        if (events.containsAny(
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          )
        ) {
          emitSnapshot()
        }
      }
    }

    controller.addListener(listener)
    emitSnapshot()
    updateTicking()
    awaitClose {
      tickJob?.cancel()
      controller.removeListener(listener)
    }
  }

  private inline fun executeAfterPrepare(crossinline action: suspend (MediaController) -> Unit) {
    scope.launch {
      val t0 = System.currentTimeMillis()
      android.util.Log.i("VOICE_PERF", "[PlayerController] executeAfterPrepare started")
      val controller = awaitConnect()
      if (controller == null) {
        android.util.Log.w("VOICE_PERF", "[PlayerController] awaitConnect returned null!")
        return@launch
      }
      android.util.Log.i("VOICE_PERF", "[PlayerController] awaitConnect took ${System.currentTimeMillis() - t0}ms")
      val tPrep = System.currentTimeMillis()
      if (maybePrepare(controller)) {
        android.util.Log.i("VOICE_PERF", "[PlayerController] maybePrepare took ${System.currentTimeMillis() - tPrep}ms. Calling action...")
        action(controller)
        android.util.Log.i("VOICE_PERF", "[PlayerController] action completed (Total executeAfterPrepare=${System.currentTimeMillis() - t0}ms)")
      }
    }
  }

  @IgnorableReturnValue
  suspend fun awaitConnect(): MediaController? {
    val t0 = System.currentTimeMillis()
    return try {
      val res = controller.await()
      android.util.Log.i("VOICE_PERF", "[PlayerController] controller.await() completed in ${System.currentTimeMillis() - t0}ms")
      res
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      Logger.w(e, "Error while connecting to media controller")
      null
    }
  }
}
