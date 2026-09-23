package voice.core.playback.session

import android.content.ContentResolver
import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

internal class PlaybackDataSourceFactory(
  private val context: Context,
  private val defaultDataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
) : DataSource.Factory {

  override fun createDataSource(): DataSource {
    val defaultDataSource = defaultDataSourceFactory.createDataSource()
    val seekableContentDataSource = SeekableContentDataSource(context)
    return PlaybackDataSource(defaultDataSource, seekableContentDataSource)
  }

  private class PlaybackDataSource(
    private val defaultDataSource: DataSource,
    private val seekableContentDataSource: SeekableContentDataSource,
  ) : DataSource {

    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
      defaultDataSource.addTransferListener(transferListener)
      seekableContentDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
      val scheme = dataSpec.uri.scheme
      val dataSource = if (scheme == ContentResolver.SCHEME_CONTENT) {
        seekableContentDataSource
      } else {
        defaultDataSource
      }
      activeDataSource = dataSource
      return try {
        dataSource.open(dataSpec)
      } catch (e: Exception) {
        if (dataSource === seekableContentDataSource) {
          activeDataSource = defaultDataSource
          defaultDataSource.open(dataSpec)
        } else {
          throw e
        }
      }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      return activeDataSource?.read(buffer, offset, length) ?: -1
    }

    override fun getUri() = activeDataSource?.uri

    override fun close() {
      try {
        activeDataSource?.close()
      } finally {
        activeDataSource = null
      }
    }
  }
}
