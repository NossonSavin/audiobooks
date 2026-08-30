package voice.features.onboarding.explanation

import dev.zacsweers.metro.Inject
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

@Inject
class OnboardingExplanationViewModel(
  private val navigator: Navigator,
) {

  fun viewState(): OnboardingExplanationViewState {
    return OnboardingExplanationViewState(
      askForAnalytics = false,
    )
  }

  fun onContinueWithAnalytics() {
    navigator.goTo(Destination.AddContent(origin = Origin.Onboarding))
  }

  fun onContinueWithoutAnalytics() {
    navigator.goTo(Destination.AddContent(origin = Origin.Onboarding))
  }

  fun onPrivacyPolicyClick() {
    navigator.goTo(Destination.Website("https://voice.woitaschek.de/privacy-policy"))
  }

  fun onClose() {
    navigator.setRoot(Destination.BookOverview)
  }
}
