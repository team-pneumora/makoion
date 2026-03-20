package io.makoion.mobileclaw

import android.app.Application
import android.util.Log
import androidx.work.Configuration

class MobileClawApplication : Application(), Configuration.Provider {
    val appContainer: ShellAppContainer by lazy {
        ShellAppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setDefaultProcessName(packageName)
            .setInitializationExceptionHandler { throwable ->
                Log.e(workManagerTag, "WorkManager initialization failed.", throwable)
            }
            .build()

    override fun onCreate() {
        super.onCreate()
    }

    companion object {
        private const val workManagerTag = "MakoionWorkManager"
    }
}
