package voice.features.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.data.GridMode
import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import voice.core.data.backup.BackupManager
import voice.core.data.backup.ImportResult
import voice.core.data.backup.VoiceBackup
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.featureflag.MemoryFeatureFlag
import voice.core.ui.DynamicColorAvailability
import voice.core.ui.GridCount
import voice.core.playback.PlayerController
import voice.core.scanner.MediaScanTrigger
import voice.navigation.Destination
import voice.navigation.Navigator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class SettingsViewModelTest {

  private val scope = TestScope()
  private val themeModeStore = MemoryDataStore(ThemeMode.FollowSystem)
  private val themeColorSchemeStore = MemoryDataStore(ThemeColorScheme.VoiceBlue)
  private val autoRewindAmountStore = MemoryDataStore(10)
  private val seekTimeStore = MemoryDataStore(30)
  private val defaultPlaybackSpeedStore = MemoryDataStore(1F)
  private val gridModeStore = MemoryDataStore(GridMode.GRID)
  private val sleepTimerPreferenceStore = MemoryDataStore(SleepTimerPreference.Default)
  private val analyticsConsentStore = MemoryDataStore(false)
  private val developerMenuUnlockedStore = MemoryDataStore(false)
  private val hideCoverFromSystemStore = MemoryDataStore(false)
  private val resumeOtherMediaStore = MemoryDataStore(false)
  private val player = mockk<PlayerController>(relaxed = true)
  private val navigator = mockk<Navigator> {
    every { goTo(any()) } just Runs
  }
  private val appInfoProvider = mockk<AppInfoProvider> {
    every { versionName } returns "1.2.3"
    every { analyticsIncluded } returns true
    every { supportDevelopmentIncluded } returns true
    every { installTime } returns Instant.parse("2026-06-01T00:00:00Z")
  }
  private val gridCount = mockk<GridCount> {
    every { useGridAsDefault() } returns true
  }
  private val kioskModeFeatureFlag = MemoryFeatureFlag(false)
  private val dynamicColorAvailability = mockk<DynamicColorAvailability> {
    every { isSupported() } returns true
  }
  private val contentResolver = mockk<ContentResolver>(relaxed = true)
  private val context = mockk<Context>(relaxed = true) {
    every { this@mockk.contentResolver } returns this@SettingsViewModelTest.contentResolver
    every { getString(any()) } returns "Message"
    every { getString(any(), any(), any()) } returns "Restored 1 books and 2 bookmarks"
  }
  private val backupManager = mockk<BackupManager>(relaxed = true)
  private val mediaScanTrigger = mockk<MediaScanTrigger>(relaxed = true)

  private val viewModel = SettingsViewModel(
    themeModeStore = themeModeStore,
    themeColorSchemeStore = themeColorSchemeStore,
    autoRewindAmountStore = autoRewindAmountStore,
    seekTimeStore = seekTimeStore,
    defaultPlaybackSpeedStore = defaultPlaybackSpeedStore,
    navigator = navigator,
    appInfoProvider = appInfoProvider,
    gridModeStore = gridModeStore,
    sleepTimerPreferenceStore = sleepTimerPreferenceStore,
    analyticsConsentStore = analyticsConsentStore,
    gridCount = gridCount,
    kioskModeFeatureFlag = kioskModeFeatureFlag,
    developerMenuUnlockedStore = developerMenuUnlockedStore,
    hideCoverFromSystemStore = hideCoverFromSystemStore,
    resumeOtherMediaStore = resumeOtherMediaStore,
    player = player,
    dynamicColorAvailability = dynamicColorAvailability,
    context = context,
    backupManager = backupManager,
    mediaScanTrigger = mediaScanTrigger,
    dispatcherProvider = DispatcherProvider(scope.coroutineContext, scope.coroutineContext, scope.coroutineContext),
  )


  @Test
  fun `view state defaults to follow system and voice blue`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem().let {
        assertEquals(expected = ThemeMode.FollowSystem, actual = it.themeMode)
        assertEquals(expected = ThemeColorScheme.VoiceBlue, actual = it.themeColorScheme)
      }
    }
  }

  @Test
  fun `theme mode changes update view state`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = ThemeMode.FollowSystem, actual = awaitItem().themeMode)

      viewModel.setThemeMode(ThemeMode.Dark)
      assertEquals(expected = ThemeMode.Dark, actual = awaitItem().themeMode)

      viewModel.setThemeMode(ThemeMode.Light)
      assertEquals(expected = ThemeMode.Light, actual = awaitItem().themeMode)

      viewModel.setThemeMode(ThemeMode.FollowSystem)
      assertEquals(expected = ThemeMode.FollowSystem, actual = awaitItem().themeMode)
    }
  }

  @Test
  fun `color scheme setting is visible when dynamic color is supported`() = scope.runTest {
    every { dynamicColorAvailability.isSupported() } returns true

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = true, actual = awaitItem().showThemeColorSchemePref)
    }
  }

  @Test
  fun `color scheme setting is hidden when dynamic color is unsupported`() = scope.runTest {
    every { dynamicColorAvailability.isSupported() } returns false

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = false, actual = awaitItem().showThemeColorSchemePref)
    }
  }

  @Test
  fun `selecting dynamic color updates view state`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = ThemeColorScheme.VoiceBlue, actual = awaitItem().themeColorScheme)

      viewModel.setThemeColorScheme(ThemeColorScheme.Dynamic)

      assertEquals(expected = ThemeColorScheme.Dynamic, actual = awaitItem().themeColorScheme)
    }
  }

  @Test
  fun `developer menu is hidden until app version tapped 13 times`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = false, actual = awaitItem().showDeveloperMenu)

      repeat(13) {
        viewModel.onAppVersionClick()
      }

      assertEquals(expected = true, actual = awaitItem().showDeveloperMenu)
    }
  }

  @Test
  fun `developer menu unlock emits snackbar effect`() = scope.runTest {
    viewModel.viewEffects.test {
      repeat(13) {
        viewModel.onAppVersionClick()
      }

      assertIs<SettingsViewEffect.DeveloperMenuUnlocked>(awaitItem())
    }
  }

  @Test
  fun `openDeveloperMenu navigates to developer settings`() {
    viewModel.openDeveloperMenu()

    verify(exactly = 1) {
      navigator.goTo(Destination.DeveloperSettings)
    }
  }

  @Test
  fun `openSupportVoice navigates to support screen`() {
    viewModel.openSupportVoice()

    verify(exactly = 1) {
      navigator.goTo(Destination.SupportVoice)
    }
  }

  @Test
  fun `openFolderPicker navigates to folder picker`() {
    viewModel.openFolderPicker()

    verify(exactly = 1) {
      navigator.goTo(Destination.FolderPicker)
    }
  }

  @Test
  fun `view state shows support development when included`() = scope.runTest {
    every { appInfoProvider.supportDevelopmentIncluded } returns true

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = true, actual = awaitItem().showSupportDevelopment)
    }
  }

  @Test
  fun `view state hides support development when not included`() = scope.runTest {
    every { appInfoProvider.supportDevelopmentIncluded } returns false

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = false, actual = awaitItem().showSupportDevelopment)
    }
  }

  @Test
  fun `analytics setting stays hidden`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem().let {
        assertEquals(expected = false, actual = it.showAnalyticSetting)
      }
    }
  }

  @Test
  fun `view state exposes kiosk mode`() = scope.runTest {
    kioskModeFeatureFlag.value = true

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem().let {
        assertEquals(expected = true, actual = it.kioskMode)
      }
    }
  }

  @Test
  fun `default playback speed updates view state`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = 1F, actual = awaitItem().defaultPlaybackSpeed)

      viewModel.setDefaultPlaybackSpeed(1.25F)

      assertEquals(expected = 1.25F, actual = awaitItem().defaultPlaybackSpeed)
    }
  }

  @Test
  fun `onExportBackup writes json and emits success snackbar`() = scope.runTest {
    val uri = mockk<Uri>()
    val outputStream = ByteArrayOutputStream()
    every { contentResolver.openOutputStream(uri) } returns outputStream

    val backup = VoiceBackup(version = 1)
    coEvery { backupManager.createBackup() } returns backup
    every { backupManager.serializeBackup(backup) } returns """{"version":1}"""

    viewModel.viewEffects.test {
      viewModel.onExportBackup(uri)
      val effect = awaitItem()
      assertIs<SettingsViewEffect.ShowSnackbar>(effect)
      assertEquals("""{"version":1}""", outputStream.toString())
    }
  }

  @Test
  fun `onImportBackup parses json, triggers scan and emits success snackbar`() = scope.runTest {
    val uri = mockk<Uri>()
    val jsonString = """{"version":1,"books":[]}"""
    val inputStream = ByteArrayInputStream(jsonString.toByteArray())
    every { contentResolver.openInputStream(uri) } returns inputStream

    coEvery { backupManager.restoreBackupFromJson(jsonString) } returns ImportResult(1, 2)

    viewModel.viewEffects.test {
      viewModel.onImportBackup(uri)
      val effect = awaitItem()
      assertIs<SettingsViewEffect.ShowSnackbar>(effect)
      verify(exactly = 1) {
        mediaScanTrigger.triggerScan(restartIfScanning = true)
      }
    }
  }

  @Test
  fun `onImportBackup failure emits error snackbar`() = scope.runTest {
    val uri = mockk<Uri>()
    every { contentResolver.openInputStream(uri) } returns null

    viewModel.viewEffects.test {
      viewModel.onImportBackup(uri)
      val effect = awaitItem()
      assertIs<SettingsViewEffect.ShowSnackbar>(effect)
    }
  }
}

private class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val value = MutableStateFlow(initial)

  override val data: Flow<T> get() = value

  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}
