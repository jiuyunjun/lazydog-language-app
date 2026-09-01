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
        SpellingProgressEntity::class,
        SpellingAttemptEntity::class,
        VocabularyMemoryHintEntity::class,
    ],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 8, to = 9),
        // v10 只是多一张 vocabulary_memory_hints，没有改动老表，交给自动迁移。
        AutoMigration(from = 9, to = 10),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun readingDao(): ReadingDao

    abstract fun scenarioSessionDao(): ScenarioSessionDao

    abstract fun drillMistakeDao(): DrillMistakeDao

    abstract fun spellingDao(): SpellingDao

    abstract fun memoryHintDao(): MemoryHintDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lazydog.db")
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
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

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spelling_progress` (" +
                        "`itemId` INTEGER NOT NULL, `stage` TEXT NOT NULL, " +
                        "`recognitionScore` REAL NOT NULL, `partialRecallScore` REAL NOT NULL, " +
                        "`chunkRecallScore` REAL NOT NULL, `phonemeGraphemeScore` REAL NOT NULL, " +
                        "`freeRecallScore` REAL NOT NULL, `retentionScore` REAL NOT NULL, " +
                        "`successStreak` INTEGER NOT NULL, `failureStreak` INTEGER NOT NULL, " +
                        "`stageSuccessCount` INTEGER NOT NULL, `freeRecallSuccessCount` INTEGER NOT NULL, " +
                        "`successfulRecallDatesJson` TEXT NOT NULL, " +
                        "`longestSuccessfulIntervalDays` INTEGER NOT NULL, `currentIntervalDays` INTEGER NOT NULL, " +
                        "`weakSegmentsJson` TEXT NOT NULL, `lastAttemptAt` INTEGER, " +
                        "PRIMARY KEY(`itemId`), FOREIGN KEY(`itemId`) REFERENCES `knowledge_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spelling_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                        "`questionType` TEXT NOT NULL, `expected` TEXT NOT NULL, `answer` TEXT NOT NULL, " +
                        "`correct` INTEGER NOT NULL, `hintLevel` INTEGER NOT NULL, " +
                        "`responseTimeMillis` INTEGER NOT NULL, `errorTypesJson` TEXT NOT NULL, " +
                        "`weakSegment` TEXT NOT NULL, `weakStart` INTEGER, `weakEndExclusive` INTEGER, " +
                        "`masteryCredit` REAL NOT NULL, `occurredAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `knowledge_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spelling_attempts_itemId` ON `spelling_attempts` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spelling_attempts_occurredAt` ON `spelling_attempts` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spelling_attempts_questionType` ON `spelling_attempts` (`questionType`)")
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
