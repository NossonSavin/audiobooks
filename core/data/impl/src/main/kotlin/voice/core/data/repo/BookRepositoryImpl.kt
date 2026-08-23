package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.logging.api.Logger

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
public class BookRepositoryImpl(
  private val chapterRepo: ChapterRepoImpl,
  private val contentRepo: BookContentRepo,
) : BookRepository {

  private var warmedUp = false
  private val mutex = Mutex()

  private suspend fun warmUp() {
    if (warmedUp) return
    mutex.withLock {
      if (warmedUp) return@withLock
      val t0 = System.currentTimeMillis()
      val chapters = contentRepo.all()
        .filter { it.isActive }
        .flatMap { it.chapters }
      chapterRepo.warmup(chapters)
      warmedUp = true
      android.util.Log.i("VOICE_PERF", "[BookRepositoryImpl] warmUp took ${System.currentTimeMillis() - t0}ms for ${chapters.size} chapters")
    }
  }

  override fun flow(): Flow<List<Book>> {
    return contentRepo.flow()
      .map { contents ->
        warmUp()
        contents.filter { it.isActive }
          .mapNotNull { content ->
            content.book()
          }
      }
  }

  override suspend fun all(): List<Book> {
    warmUp()
    return contentRepo.all()
      .filter { it.isActive }
      .mapNotNull { it.book() }
  }

  override fun flow(id: BookId): Flow<Book?> {
    return contentRepo.flow(id)
      .map { it?.book() }
  }

  override suspend fun get(id: BookId): Book? {
    val t0 = System.currentTimeMillis()
    val res = contentRepo.get(id)?.book()
    android.util.Log.i("VOICE_PERF", "[BookRepositoryImpl] get($id) took ${System.currentTimeMillis() - t0}ms")
    return res
  }

  override suspend fun updateBook(
    id: BookId,
    update: (BookContent) -> BookContent,
  ) {
    mutex.withLock {
      val content = contentRepo.get(id) ?: return
      val updated = update(content)
      if (updated != content) {
        contentRepo.put(updated)
      }
    }
  }

  private suspend fun BookContent.book(): Book? {
    val t0 = System.currentTimeMillis()
    chapterRepo.warmup(chapters)
    val b = Book(
      content = this,
      chapters = chapters.map { chapterId ->
        val chapter = chapterRepo.get(chapterId)
        if (chapter == null) {
          Logger.w("Missing chapter with id=$chapterId for $this")
          return null
        }
        chapter
      },
    )
    android.util.Log.i("VOICE_PERF", "[BookRepositoryImpl] BookContent.book() for ${this.id} took ${System.currentTimeMillis() - t0}ms (${chapters.size} chapters)")
    return b
  }
}
