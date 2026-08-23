package voice.core.playback.session

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener

internal class FastMediaDataSource(
  private val context: Context,
  private val defaultDataSource: DataSource,
  private val fastContentDataSource: FastContentDataSource,
  private val fileDataSource: FastFileDataSource,
) : DataSource {

  private var currentDataSource: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    defaultDataSource.addTransferListener(transferListener)
    fastContentDataSource.addTransferListener(transferListener)
    fileDataSource.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    val scheme = dataSpec.uri.scheme
    val dataSource = when (scheme) {
      "content" -> fastContentDataSource
      "file" -> fileDataSource
      else -> defaultDataSource
    }
    currentDataSource = dataSource
    android.util.Log.i("VOICE_PERF", "[FastMediaDataSource] Delegating ${dataSpec.uri} to ${dataSource::class.simpleName} at position=${dataSpec.position}")
    return dataSource.open(dataSpec)
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return currentDataSource?.read(buffer, offset, length) ?: -1
  }

  override fun getUri() = currentDataSource?.uri

  override fun close() {
    try {
      currentDataSource?.close()
    } finally {
      currentDataSource = null
    }
  }

  internal class Factory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource {
      return FastMediaDataSource(
        context = context,
        defaultDataSource = DefaultDataSource.Factory(context).createDataSource(),
        fastContentDataSource = FastContentDataSource(context),
        fileDataSource = FastFileDataSource(),
      )
    }
  }
}
