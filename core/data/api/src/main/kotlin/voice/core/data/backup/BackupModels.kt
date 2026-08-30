package voice.core.data.backup

import kotlinx.serialization.Serializable

@Serializable
public data class VoiceBackup(
  val version: Int = 1,
  val timestamp: Long = 0L,
  val books: List<BookBackup> = emptyList(),
  val bookmarks: List<BookmarkBackup> = emptyList(),
  val folderUris: List<String> = emptyList(),
  val fileUris: List<String> = emptyList(),
  val currentBookId: String? = null,
)

@Serializable
public data class BookBackup(
  val id: String,
  val name: String,
  val author: String? = null,
  val genre: String? = null,
  val narrator: String? = null,
  val series: String? = null,
  val part: String? = null,
  val currentChapter: String,
  val positionInChapter: Long,
  val playbackSpeed: Float = 1.0f,
  val skipSilence: Boolean = false,
  val gain: Float = 0.0f,
  val isActive: Boolean = true,
  val lastPlayedAtEpochMs: Long = 0L,
  val addedAtEpochMs: Long = 0L,
  val chapters: List<String> = emptyList(),
)

@Serializable
public data class BookmarkBackup(
  val id: String,
  val bookId: String,
  val chapterId: String,
  val title: String? = null,
  val time: Long,
  val addedAtEpochMs: Long,
  val setBySleepTimer: Boolean = false,
)

public data class ImportResult(
  val booksCount: Int,
  val bookmarksCount: Int,
)

