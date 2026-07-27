package voice.app.navigation

import androidx.datastore.core.DataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.repo.BookRepository
import voice.core.playback.PlayerController
import voice.navigation.Destination
import kotlin.test.assertEquals

class StartDestinationProviderTest {

  private val onboardingCompletedStore = mockk<DataStore<Boolean>>()
  private val audiobookFolders = mockk<AudiobookFolders>()
  private val currentBookStore = mockk<DataStore<BookId?>>()
  private val bookRepository = mockk<BookRepository>()
  private val playerController = mockk<PlayerController>()

  private val provider = StartDestinationProvider(
    onboardingCompletedStore = onboardingCompletedStore,
    audiobookFolders = audiobookFolders,
    currentBookStore = currentBookStore,
    bookRepository = bookRepository,
    playerController = playerController,
  )

  @Test
  fun `resumes last played book if exists`() {
    val bookId = BookId("1")
    every { onboardingCompletedStore.data } returns flowOf(true)
    every { currentBookStore.data } returns flowOf(bookId)
    coEvery { bookRepository.get(bookId) } returns mockk<Book>()

    val result = provider(mockk(relaxed = true))

    assertEquals(listOf(Destination.BookOverview, Destination.Playback(bookId)), result)
  }

  @Test
  fun `defaults to book overview if no last played book`() {
    every { onboardingCompletedStore.data } returns flowOf(true)
    every { currentBookStore.data } returns flowOf(null)

    val result = provider(mockk(relaxed = true))

    assertEquals(listOf(Destination.BookOverview), result)
  }

  @Test
  fun `defaults to book overview if last played book does not exist`() {
    val bookId = BookId("1")
    every { onboardingCompletedStore.data } returns flowOf(true)
    every { currentBookStore.data } returns flowOf(bookId)
    coEvery { bookRepository.get(bookId) } returns null

    val result = provider(mockk(relaxed = true))

    assertEquals(listOf(Destination.BookOverview), result)
  }

  @Test
  fun `shows onboarding if not completed and no folders`() {
    every { onboardingCompletedStore.data } returns flowOf(false)
    coEvery { audiobookFolders.hasAnyFolders() } returns false

    val result = provider(mockk(relaxed = true))

    assertEquals(listOf(Destination.OnboardingWelcome), result)
  }
}
