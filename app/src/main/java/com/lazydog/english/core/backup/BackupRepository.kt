package com.lazydog.english.core.backup

import androidx.room.withTransaction
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.database.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * 把知识库 + 学习偏好整体导出成一份 [BackupPayload]，或反过来整体恢复。
 * 恢复是覆盖式的：先清空本地学习数据，再按备份重建（id 会重新分配）。
 */
class BackupRepository(
    private val database: AppDatabase,
    private val prefs: UserPreferences,
) {

    suspend fun export(): BackupPayload {
        val knowledgeDao = database.knowledgeDao()
        val readingDao = database.readingDao()
        val scenarioDao = database.scenarioSessionDao()
        return BackupPayload(
            exportedAt = System.currentTimeMillis(),
            knowledgeItems = knowledgeDao.getAllItems().map { it.toBackup() },
            vocabularyDetails = knowledgeDao.getAllVocabularyDetails().map { it.toBackup() },
            grammarDetails = knowledgeDao.getAllGrammarDetails().map { it.toBackup() },
            learningEvents = knowledgeDao.getAllEvents().map { it.toBackup() },
            readingMaterials = readingDao.getAllMaterials().map { it.toBackup() },
            scenarioSessions = scenarioDao.getAll().map { it.toBackup() },
            preferences = BackupPreferences(
                learningGoal = prefs.learningGoal.first(),
                topics = prefs.topics.first(),
                dailyMinutes = prefs.dailyMinutes.first(),
                maxNewWords = prefs.maxNewWords.first(),
                learnerLevel = prefs.learnerLevel.first(),
                learnerLevelConfidence = prefs.learnerLevelConfidence.first(),
                reminderTime = prefs.reminderTime.first(),
                themeMode = prefs.themeMode.first(),
                ttsVoice = prefs.ttsVoice.first(),
                autoReadWords = prefs.autoReadWords.first(),
                speechRateName = prefs.speechRate.first().name,
            ),
        )
    }

    suspend fun restore(payload: BackupPayload) {
        val knowledgeDao = database.knowledgeDao()
        val readingDao = database.readingDao()
        val scenarioDao = database.scenarioSessionDao()
        database.withTransaction {
            knowledgeDao.clearAll()
            readingDao.clearAll()
            scenarioDao.clearAll()

            val idMap = mutableMapOf<Long, Long>()
            for (item in payload.knowledgeItems) {
                idMap[item.id] = knowledgeDao.insertItem(item.toEntity())
            }
            for (detail in payload.vocabularyDetails) {
                val newId = idMap[detail.itemId] ?: continue
                knowledgeDao.insertVocabularyDetail(detail.toEntity(newId))
            }
            for (detail in payload.grammarDetails) {
                val newId = idMap[detail.itemId] ?: continue
                knowledgeDao.insertGrammarDetail(detail.toEntity(newId))
            }
            for (event in payload.learningEvents) {
                val newId = idMap[event.itemId] ?: continue
                knowledgeDao.insertEvent(event.toEntity(newId))
            }
            for (material in payload.readingMaterials) {
                readingDao.insert(material.toEntity())
            }
            for (session in payload.scenarioSessions) {
                scenarioDao.save(session.toEntity())
            }
        }

        val p = payload.preferences
        prefs.restoreFromBackup(
            learningGoal = p.learningGoal,
            topics = p.topics,
            dailyMinutes = p.dailyMinutes,
            maxNewWords = p.maxNewWords,
            learnerLevel = p.learnerLevel,
            learnerLevelConfidence = p.learnerLevelConfidence,
            reminderTime = p.reminderTime,
            themeMode = p.themeMode,
            ttsVoice = p.ttsVoice,
            autoReadWords = p.autoReadWords,
            speechRateName = p.speechRateName,
        )
    }
}
