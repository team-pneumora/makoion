package io.makoion.mobileclaw

import android.app.Application

class MobileClawApplication : Application() {
    val appContainer: ShellAppContainer by lazy {
        ShellAppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        appContainer.shellRecoveryCoordinator.start()
    }
}
