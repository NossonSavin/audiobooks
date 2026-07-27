package voice.features.bookOverview.editBookCategory

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import java.time.Instant
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.playback.PlayerController
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.category

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class EditBookCategoryViewModel(
  private val repo: BookRepository,
  private val playerController: PlayerController,
) : BottomSheetItemViewModel {

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    val book = repo.get(bookId) ?: return emptyList()
    return when (book.category) {
      BookOverviewCategory.CURRENT -> listOf(
        BottomSheetItem.BookCategoryMarkAsNotStarted,
        BottomSheetItem.BookCategoryMarkAsCompleted,
      )
      BookOverviewCategory.NOT_STARTED -> listOf(
        BottomSheetItem.BookCategoryMarkAsCurrent,
        BottomSheetItem.BookCategoryMarkAsCompleted,
      )
      BookOverviewCategory.FINISHED -> listOf(
        BottomSheetItem.BookCategoryMarkAsCurrent,
        BottomSheetItem.BookCategoryMarkAsNotStarted,
      )
    }
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    val book = repo.get(bookId) ?: return

    when (item) {
      BottomSheetItem.BookCategoryMarkAsCurrent -> {
        repo.updateBook(book.id) {
          it.copy(
            currentChapter = book.chapters.first().id,
            positionInChapter = 1L,
          )
        }
      }
      BottomSheetItem.BookCategoryMarkAsNotStarted -> {
        val firstChapterId = book.chapters.first().id
        repo.updateBook(book.id) {
          it.copy(
            currentChapter = firstChapterId,
            positionInChapter = 0L,
            playbackSpeed = 1F,
            lastPlayedAt = Instant.EPOCH,
          )
        }
        playerController.pause()
        playerController.setPosition(0L, firstChapterId)
      }
      BottomSheetItem.BookCategoryMarkAsCompleted -> {
        val lastChapter = book.chapters.last()
        repo.updateBook(book.id) {
          it.copy(
            currentChapter = lastChapter.id,
            positionInChapter = lastChapter.duration,
          )
        }
      }
      else -> return
    }
  }
}
