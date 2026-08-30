package voice.features.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.GridMode
import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import voice.core.data.backup.BackupManager
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.AnalyticsConsentStore
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.DefaultPlaybackSpeedStore
import voice.core.data.store.DeveloperMenuUnlockedStore
import voice.core.data.store.GridModeStore
import voice.core.data.store.HideCoverFromSystemStore
import voice.core.data.store.ResumeOtherMediaStore
import voice.core.data.store.SeekTimeStore
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.data.store.ThemeColorSchemeStore
import voice.core.data.store.ThemeModeStore
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.scanner.MediaScanTrigger
import voice.core.ui.DynamicColorAvailability
import voice.core.ui.GridCount
import voice.navigation.Destination
import voice.navigation.Navigator
import java.time.LocalTime
import voice.core.strings.R as StringsR

@Inject
class SettingsViewModel(
  @ThemeModeStore
  private val themeModeStore: DataStore<ThemeMode>,
  @ThemeColorSchemeStore
  private val themeColorSchemeStore: DataStore<ThemeColorScheme>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @DefaultPlaybackSpeedStore
  private val defaultPlaybackSpeedStore: DataStore<Float>,
  private val navigator: Navigator,
  private val appInfoProvider: AppInfoProvider,
  @GridModeStore
  private val gridModeStore: DataStore<GridMode>,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  @AnalyticsConsentStore
  private val analyticsConsentStore: DataStore<Boolean>,
  private val gridCount: GridCount,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
  @DeveloperMenuUnlockedStore
  private val developerMenuUnlockedStore: DataStore<Boolean>,
  @HideCoverFromSystemStore
  private val hideCoverFromSystemStore: DataStore<Boolean>,
  @ResumeOtherMediaStore
  private val resumeOtherMediaStore: DataStore<Boolean>,
  private val player: PlayerController,
  private val dynamicColorAvailability: DynamicColorAvailability,
  private val context: Context,
  private val backupManager: BackupManager,
  private val mediaScanTrigger: MediaScanTrigger,
  dispatcherProvider: DispatcherProvider,
) : SettingsListener {

  private val mainScope = MainScope(dispatcherProvider)
  internal val viewEffects: SharedFlow<SettingsViewEffect>
    field = MutableSharedFlow<SettingsViewEffect>(extraBufferCapacity = 1)
  private val dialog = mutableStateOf<SettingsViewState.Dialog?>(null)
  private var appVersionTapCount = 0

  @Composable
  fun viewState(): SettingsViewState {
    val themeMode by remember { themeModeStore.data }.collectAsState(initial = ThemeMode.FollowSystem)
    val themeColorScheme by remember { themeColorSchemeStore.data }.collectAsState(initial = ThemeColorScheme.VoiceBlue)
    val autoRewindAmount by remember { autoRewindAmountStore.data }.collectAsState(initial = 0)
    val seekTime by remember { seekTimeStore.data }.collectAsState(initial = 0)
    val defaultPlaybackSpeed by remember { defaultPlaybackSpeedStore.data }.collectAsState(initial = 1F)
    val gridMode by remember { gridModeStore.data }.collectAsState(initial = GridMode.GRID)
    val autoSleepTimer by remember { sleepTimerPreferenceStore.data }.collectAsState(
      initial = SleepTimerPreference.Default,
    )
    val analyticsEnabled by remember { analyticsConsentStore.data }.collectAsState(initial = false)
    val kioskMode = remember {
      kioskModeFeatureFlag.get()
    }
    val showDeveloperMenu by remember { developerMenuUnlockedStore.data }.collectAsState(initial = false)
    val hideCoverFromSystem by remember { hideCoverFromSystemStore.data }.collectAsState(initial = false)
    val resumeOtherMedia by remember { resumeOtherMediaStore.data }.collectAsState(initial = false)
    val showThemeColorSchemePref = remember {
      dynamicColorAvailability.isSupported()
    }
    return SettingsViewState(
      themeMode = themeMode,
      themeColorScheme = themeColorScheme,
      showThemeColorSchemePref = showThemeColorSchemePref,
      seekTimeInSeconds = seekTime,
      autoRewindInSeconds = autoRewindAmount,
      defaultPlaybackSpeed = defaultPlaybackSpeed,
      dialog = dialog.value,
      appVersion = appInfoProvider.versionName,
      useGrid = when (gridMode) {
        GridMode.LIST -> false
        GridMode.GRID -> true
        GridMode.FOLLOW_DEVICE -> gridCount.useGridAsDefault()
      },
      autoSleepTimer = SettingsViewState.AutoSleepTimerViewState(
        enabled = autoSleepTimer.autoSleepTimerEnabled,
        startTime = autoSleepTimer.autoSleepStartTime,
        endTime = autoSleepTimer.autoSleepEndTime,
      ),
      analyticsEnabled = analyticsEnabled,
      showAnalyticSetting = false,
      showDeveloperMenu = showDeveloperMenu,
      showSupportDevelopment = appInfoProvider.supportDevelopmentIncluded,
      kioskMode = kioskMode,
      hideCoverFromSystem = hideCoverFromSystem,
      resumeOtherMedia = resumeOtherMedia,
    )
  }

  override fun close() {
    navigator.goBack()
  }

  override fun onThemeModeRowClick() {
    dialog.value = SettingsViewState.Dialog.Theme
  }

  override fun onThemeColorSchemeRowClick() {
    dialog.value = SettingsViewState.Dialog.ColorScheme
  }

  override fun setThemeMode(themeMode: ThemeMode) {
    mainScope.launch {
      themeModeStore.updateData { themeMode }
    }
    dialog.value = null
  }

  override fun setThemeColorScheme(themeColorScheme: ThemeColorScheme) {
    mainScope.launch {
      themeColorSchemeStore.updateData { themeColorScheme }
    }
    dialog.value = null
  }

  override fun toggleGrid() {
    mainScope.launch {
      gridModeStore.updateData { currentMode ->
        when (currentMode) {
          GridMode.LIST -> GridMode.GRID
          GridMode.GRID -> GridMode.LIST
          GridMode.FOLLOW_DEVICE -> if (gridCount.useGridAsDefault()) {
            GridMode.LIST
          } else {
            GridMode.GRID
          }
        }
      }
    }
  }

  override fun seekAmountChanged(seconds: Int) {
    mainScope.launch {
      seekTimeStore.updateData { seconds }
    }
  }

  override fun onSeekAmountRowClick() {
    dialog.value = SettingsViewState.Dialog.SeekTime
  }

  override fun setDefaultPlaybackSpeed(speed: Float) {
    mainScope.launch {
      defaultPlaybackSpeedStore.updateData { speed }
    }
  }

  override fun onDefaultPlaybackSpeedRowClick() {
    dialog.value = SettingsViewState.Dialog.DefaultPlaybackSpeed
  }

  override fun autoRewindAmountChang(seconds: Int) {
    mainScope.launch {
      autoRewindAmountStore.updateData { seconds }
    }
  }

  override fun onAutoRewindRowClick() {
    dialog.value = SettingsViewState.Dialog.AutoRewindAmount
  }

  override fun dismissDialog() {
    dialog.value = null
  }

  override fun getSupport() {
    navigator.goTo(Destination.Website("https://github.com/PaulWoitaschek/Voice/discussions/categories/q-a"))
  }

  override fun suggestIdea() {
    navigator.goTo(Destination.Website("https://github.com/PaulWoitaschek/Voice/discussions/categories/ideas"))
  }

  override fun openBugReport() {
    val url = "https://github.com/PaulWoitaschek/Voice/issues/new".toUri()
      .buildUpon()
      .appendQueryParameter("template", "bug.yml")
      .appendQueryParameter("version", appInfoProvider.versionName)
      .appendQueryParameter("androidversion", Build.VERSION.SDK_INT.toString())
      .appendQueryParameter("device", Build.MODEL)
      .toString()
    navigator.goTo(Destination.Website(url))
  }

  override fun openTranslations() {
    dismissDialog()
    navigator.goTo(Destination.Website("https://hosted.weblate.org/engage/voice/"))
  }

  override fun openFaq() {
    navigator.goTo(Destination.Website("https://voice.woitaschek.de/faq/"))
  }

  override fun openSupportVoice() {
    navigator.goTo(Destination.SupportVoice)
  }

  override fun openFolderPicker() {
    navigator.goTo(Destination.FolderPicker)
  }

  override fun setAutoSleepTimer(checked: Boolean) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepTimerEnabled = checked)
      }
    }
  }

  override fun setAutoSleepTimerStart(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepStartTime = time)
      }
    }
  }

  override fun setAutoSleepTimerEnd(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepEndTime = time)
      }
    }
  }

  override fun toggleAnalytics() {
    mainScope.launch {
      analyticsConsentStore.updateData { !it }
    }
  }

  override fun toggleHideCoverFromSystem() {
    mainScope.launch {
      val newValue = !hideCoverFromSystemStore.data.first()
      hideCoverFromSystemStore.updateData { newValue }
      player.refreshMediaItem()
    }
  }

  override fun toggleResumeOtherMedia() {
    mainScope.launch {
      resumeOtherMediaStore.updateData { !it }
    }
  }

  override fun onAppVersionClick() {
    mainScope.launch {
      if (developerMenuUnlockedStore.data.first()) {
        return@launch
      }
      if (++appVersionTapCount >= 13) {
        developerMenuUnlockedStore.updateData { true }
        viewEffects.emit(SettingsViewEffect.DeveloperMenuUnlocked)
      }
    }
  }

  override fun onExportBackup(uri: Uri) {
    mainScope.launch {
      try {
        val backup = backupManager.createBackup()
        val jsonString = backupManager.serializeBackup(backup)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
          outputStream.writer().use { writer ->
            writer.write(jsonString)
          }
        } ?: error("Could not open output stream for $uri")
        viewEffects.emit(SettingsViewEffect.ShowSnackbar(context.getString(StringsR.string.settings_backup_export_success)))
      } catch (e: Exception) {
        Logger.e(e, "Export backup failed")
        viewEffects.emit(SettingsViewEffect.ShowSnackbar(context.getString(StringsR.string.settings_backup_export_error)))
      }
    }
  }

  override fun onImportBackup(uri: Uri) {
    mainScope.launch {
      try {
        val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
          inputStream.reader().use { reader ->
            reader.readText()
          }
        } ?: error("Could not open input stream for $uri")

        val result = backupManager.restoreBackupFromJson(jsonString)
        mediaScanTrigger.triggerScan(restartIfScanning = true)
        val message = context.getString(
          StringsR.string.settings_backup_import_success,
          result.booksCount,
          result.bookmarksCount,
        )
        viewEffects.emit(SettingsViewEffect.ShowSnackbar(message))
      } catch (e: Exception) {
        Logger.e(e, "Import backup failed")
        viewEffects.emit(SettingsViewEffect.ShowSnackbar(context.getString(StringsR.string.settings_backup_import_error)))
      }
    }
  }

  override fun openDeveloperMenu() {
    navigator.goTo(Destination.DeveloperSettings)
  }
}
