package voice.app.di

import android.app.Application
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import voice.core.common.rootGraph
import voice.core.initializer.AppInitializer

@HasMemberInjections
open class App : Application() {

  @Inject
  lateinit var appInitializers: Set<AppInitializer>

  override fun onCreate() {
    val t0 = System.currentTimeMillis()
    android.util.Log.i("VOICE_PERF", "[App] onCreate started")
    super.onCreate()

    val tGraph = System.currentTimeMillis()
    rootGraph = createGraph()
      .also { graph ->
        graph.inject(this)
      }
    android.util.Log.i("VOICE_PERF", "[App] Graph creation took ${System.currentTimeMillis() - tGraph}ms")

    val tInit = System.currentTimeMillis()
    appInitializers.forEach {
      val tEach = System.currentTimeMillis()
      it.onAppStart(this)
      android.util.Log.i("VOICE_PERF", "[App] Initializer ${it::class.simpleName} took ${System.currentTimeMillis() - tEach}ms")
    }
    android.util.Log.i("VOICE_PERF", "[App] All initializers took ${System.currentTimeMillis() - tInit}ms (Total App.onCreate=${System.currentTimeMillis() - t0}ms)")
  }

  open fun createGraph(): AppGraph {
    return createGraphFactory<ProductionAppGraph.Factory>().create(this)
  }
}
