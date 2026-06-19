package app.dogrouter

import android.app.Application
import android.content.Context
import app.dogrouter.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.osmdroid.config.Configuration

class DogRouterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@DogRouterApp)
            modules(appModule)
        }
        configureOsmdroid()
    }

    private fun configureOsmdroid() {
        val prefs = getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        Configuration.getInstance().apply {
            load(this@DogRouterApp, prefs)
            // OSM tile server policy requires a recognisable user agent.
            // Identifies our app to upstream operators and avoids the
            // default value, which is throttled on tile.openstreetmap.org.
            userAgentValue = packageName
        }
    }
}
