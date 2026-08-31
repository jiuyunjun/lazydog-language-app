package com.lazydog.english.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        KnowledgeItemEntity::class,
        VocabularyDetailEntity::class,
        GrammarDetailEntity::class,
        LearningEventEntity::class,
        ReadingMaterialEntity::class,
        ScenarioSessionEntity::class,
        DrillMistakeEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun readingDao(): ReadingDao

    abstract fun scenarioSessionDao(): ScenarioSessionDao

    abstract fun drillMistakeDao(): DrillMistakeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lazydog.db")
                .addMigrations(MIGRATION_6_7)
                .build()

        /**
         * 两条开发分支都曾发布过 version 6，但 schema 不同：
         * main 的 v6 有 drill_mistakes，recovered-wip 的 v6 有 memoryHintZh。
         * 合并后的 v7 必须补齐缺少的那一半，不能假定任一对象一定不存在。
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("vocabulary_details", "memoryHintZh")) {
                    db.execSQL(
                        "ALTER TABLE `vocabulary_details` " +
                            "ADD COLUMN `memoryHintZh` TEXT NOT NULL DEFAULT ''",
                    )
                }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drill_mistakes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`itemId` INTEGER, `patternEn` TEXT NOT NULL, " +
                        "`errorTag` TEXT NOT NULL, `sentenceEn` TEXT NOT NULL, " +
                        "`chosen` TEXT NOT NULL, `answer` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drill_mistakes_occurredAt` " +
                        "ON `drill_mistakes` (`occurredAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drill_mistakes_errorTag` " +
                        "ON `drill_mistakes` (`errorTag`)",
                )
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return@use true
                }
                false
            }
    }
}
