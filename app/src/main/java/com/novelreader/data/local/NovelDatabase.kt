package com.novelreader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelreader.data.model.Book
import com.novelreader.data.model.Chapter

@Database(
    entities = [Book::class, Chapter::class],
    version = 2,
    exportSchema = false
)
abstract class NovelDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        @Volatile
        private var INSTANCE: NovelDatabase? = null

        // 版本1→2：chapters表的startOffset/endOffset含义从字符偏移改为UTF-8字节偏移，
        // 直接清空重建比迁移数据更安全
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS chapters")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS chapters (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        `index` INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        startOffset INTEGER NOT NULL,
                        endOffset INTEGER NOT NULL,
                        charCount INTEGER NOT NULL
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_bookId ON chapters (bookId)")
            }
        }

        fun getDatabase(context: Context): NovelDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NovelDatabase::class.java,
                    "novel_database"
                ).addMigrations(MIGRATION_1_2)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
