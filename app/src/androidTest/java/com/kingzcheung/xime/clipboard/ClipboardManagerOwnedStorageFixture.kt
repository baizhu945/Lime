package com.kingzcheung.xime.clipboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import java.io.File
import java.util.UUID

/** Real production stores, owned by one test invocation; never installs application singletons. */
internal class ClipboardManagerOwnedStorageFixture(base: Context) {
    private val storage = base.applicationContext
    private val namespace = "clipboard_manager_owned_${UUID.randomUUID()}"
    private val root = File(storage.cacheDir, namespace).apply { check(mkdirs()) }
    private val preferences = mutableSetOf<String>()

    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${namespace}_$name"
            synchronized(preferences) { preferences.add(isolatedName) }
            return storage.getSharedPreferences(isolatedName, mode)
        }

        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getNoBackupFilesDir(): File = directory("no_backup")
        override fun getCodeCacheDir(): File = directory("code_cache")
        override fun getDir(name: String, mode: Int): File = directory("dirs/$name")
        override fun getDatabasePath(name: String): File = File(directory("databases"), File(name).name)
    }

    // An absolute Room name also isolates the AndroidSQLiteDriver path independently of Context.
    val database = ClipboardDatabase.create(context, File(directory("databases"), "clipboard.db").absolutePath)
    private val imageStore = lazy { ClipboardImages.create(context) }
    private val scopedProvider = lazy { ClipboardManager.Provider(context, database, imageStore.value) }
    val provider: ClipboardManager.Provider get() = scopedProvider.value
    private val scopedManager = lazy { provider.getInstance() }
    val manager: ClipboardManager get() = scopedManager.value

    private fun directory(name: String): File = File(root, name).apply {
        mkdirs()
        check(isDirectory)
    }

    suspend fun close() {
        // No database is closed until the manager's collectors/writers/listeners have stopped.
        if (scopedManager.isInitialized()) scopedManager.value.close()
        if (imageStore.isInitialized()) imageStore.value.close()
        database.close()
        synchronized(preferences) {
            preferences.forEach { name ->
                // Flush earlier apply() writes before removing this unique preference file.
                check(storage.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                check(storage.deleteSharedPreferences(name))
            }
        }
        check(root.deleteRecursively())
    }
}
