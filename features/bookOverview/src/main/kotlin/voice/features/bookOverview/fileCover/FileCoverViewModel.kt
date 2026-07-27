package voice.features.bookOverview.fileCover

import android.net.Uri
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.scanner.CoverSaver
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope
import voice.navigation.Navigator

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class FileCoverViewModel(
  private val coverSaver: CoverSaver,
  dispatcherProvider: DispatcherProvider,
) : BottomSheetItemViewModel {

  private val scope = MainScope(dispatcherProvider)
  private var bookId: BookId? = null

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    return listOf(BottomSheetItem.FileCover)
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    if (item == BottomSheetItem.FileCover) {
      this.bookId = bookId
    }
  }

  fun onImagePicked(uri: Uri) {
    val bookId = bookId ?: return
    scope.launch {
      coverSaver.save(bookId, uri)
    }
  }
}
