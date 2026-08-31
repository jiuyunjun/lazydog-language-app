package com.lazydog.english.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        AutoMigration(from = 6, to = 7),
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
                .build()
    }
}
