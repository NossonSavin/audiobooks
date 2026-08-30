package voice.features.settings

internal sealed interface SettingsViewEffect {
  data object DeveloperMenuUnlocked : SettingsViewEffect
  data class ShowSnackbar(val message: String) : SettingsViewEffect
}
