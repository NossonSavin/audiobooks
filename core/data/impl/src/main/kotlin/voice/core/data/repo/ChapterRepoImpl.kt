package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.internals.dao.ChapterDao
import voice.core.data.runForMaxSqlVariableNumber

@ContributesBinding(AppScope::class)
public class ChapterRepoImpl(private val dao: ChapterDao) : ChapterRepo {

  private val mutex = Mutex()
  private val cache = mutableMapOf<ChapterId, Chapter?>()

  override suspend fun get(id: ChapterId): Chapter? {
    mutex.withLock {
      if (cache.containsKey(id)) {
        return cache[id]
      }
    }
    val chapter = dao.chapter(id)
    mutex.withLock {
      cache[id] = chapter
    }
    return chapter
  }

  override suspend fun warmup(ids: List<ChapterId>) {
    val missing = mutex.withLock {
      ids.filter { it !in cache }
    }
    if (missing.isEmpty()) return
    val loaded = missing.runForMaxSqlVariableNumber {
      dao.chapters(it)
    }
    mutex.withLock {
      loaded.forEach { cache[it.id] = it }
    }
  }

  override suspend fun put(chapter: Chapter) {
    dao.insert(chapter)
    mutex.withLock {
      cache[chapter.id] = chapter
    }
  }
}
