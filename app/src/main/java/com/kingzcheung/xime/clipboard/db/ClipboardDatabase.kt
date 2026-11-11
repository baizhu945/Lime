package com.kingzcheung.xime.clipboard.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [ClipboardEntry::class],
    version = 6,
    exportSchema = false
)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        private const val DATABASE_NAME = "clipboard.db"

        /** v1 → v2：新增 consumed 列（候选栏"已消费"剪贴板项过滤）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN consumed INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /** v2 → v3：新增 code 列（快捷发送触发编码）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN code TEXT NOT NULL DEFAULT ''"
                ).step()
            }
        }

        /** v3 → v4：旧快捷发送原地成为固定记录，保留全部内容、编码及身份。 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "UPDATE clipboard_entries SET isPinned = 1, isQuickSend = 0 WHERE isQuickSend = 1"
                ).use { it.step() }
            }
        }

        /** v4 → v5：新增 searchKey 列（拼音首字母，供搜索）；旧记录由后台分批补齐。 */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN searchKey TEXT NOT NULL DEFAULT ''"
                ).use { it.step() }
            }
        }

        /** v5 → v6：新增 fromDevice 列（别的设备同步来的记录另算容量）；已有记录都算本机复制。 */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN fromDevice INTEGER NOT NULL DEFAULT 0"
                ).use { it.step() }
            }
        }

        @Volatile
        private var instance: ClipboardDatabase? = null

        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: create(context, DATABASE_NAME).also { instance = it }
            }
        }

        /** Opens an independent database with the production schema/migrations. Caller owns closing it. */
        fun create(context: Context, name: String): ClipboardDatabase =
            Room.databaseBuilder<ClipboardDatabase>(context.applicationContext, name)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        fun scope(): CoroutineScope {
            return CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }
}
