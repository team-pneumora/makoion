package io.makoion.mobileclaw

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

class WorkManagerBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        if (!WorkManager.isInitialized()) {
            val configuration = (appContext as? Configuration.Provider)?.workManagerConfiguration
                ?: Configuration.Builder()
                    .setMinimumLoggingLevel(Log.DEBUG)
                    .setDefaultProcessName(appContext.packageName)
                    .build()
            Log.i(logTag, "Initializing WorkManager from bootstrap provider.")
            WorkManager.initialize(appContext, configuration)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val logTag = "MakoionWorkManager"
    }
}
