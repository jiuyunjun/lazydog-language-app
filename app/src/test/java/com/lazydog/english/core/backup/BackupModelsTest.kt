package com.lazydog.english.core.backup

import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.ReadingMaterialEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 备份结构的纯逻辑：实体<->备份结构映射、JSON 往返。
 * BackupRepository 本身依赖 Room，这个项目还没有 Robolectric/instrumented 测试基础设施，
 * 所以那部分的事务/id 重映射逻辑目前只能靠人工验证（见 HANDOFF）。
 */
class BackupModelsTest {

    @Test
    fun `knowledge item round trips through backup mapping`() {
        val entity = KnowledgeItemEntity(
            id = 42,
            type = "Vocabulary",
            stage = "Learning",
            stability = 1.5,
            difficulty = 3.0,
            reviewCount = 2,
            lapseCount = 1,
            lastReviewedAt = 1000L,
            nextReviewAt = 2000L,
            createdAt = 500L,
            updatedAt = 1500L,
        )
        val backup = entity.toBackup()
        assertEquals(42L, backup.id)

        // 恢复时 id 固定为 0，交给 Room 重新分配；其余字段原样保留。
        val restored = backup.toEntity()
        assertEquals(0L, restored.id)
        assertEquals(entity.copy(id = 0), restored)
    }

    @Test
    fun `vocabulary detail is rebound to the new item id on restore`() {
        val entity = VocabularyDetailEntity(itemId = 1, term = "curb", ipa = "/kɜːb/", meaningZh = "控制", exampleEn = "e", exampleZh = "z")
        val backup = entity.toBackup()
        assertEquals(1L, backup.itemId)

        val restored = backup.toEntity(newItemId = 99)
        assertEquals(99L, restored.itemId)
        assertEquals("curb", restored.term)
    }

    @Test
    fun `grammar detail and learning event rebind to the new item id`() {
        val grammar = GrammarDetailEntity(itemId = 1, name = "现在完成时", explanationZh = "e", exampleEn = "I have done it.")
        assertEquals(99L, grammar.toBackup().toEntity(newItemId = 99).itemId)

        val event = LearningEventEntity(id = 5, itemId = 1, source = "card", activity = "review", rating = "Good", responseMillis = 800, occurredAt = 123)
        val restoredEvent = event.toBackup().toEntity(newItemId = 99)
        assertEquals(99L, restoredEvent.itemId)
        assertEquals(0L, restoredEvent.id) // 事件 id 也重新分配
        assertEquals("Good", restoredEvent.rating)
    }

    @Test
    fun `reading material round trips without carrying the old id`() {
        val entity = ReadingMaterialEntity(
            id = 7,
            title = "T",
            body = "B",
            source = "ai",
            topic = "科技",
            estimatedCefr = "B1",
            targetWordsJson = "[]",
            grammarJson = "[]",
            questionsJson = "[]",
            model = "gpt-test",
            promptVersion = 1,
            schemaVersion = 1,
            validationNotes = "",
            createdAt = 123,
        )
        val restored = entity.toBackup().toEntity()
        assertEquals(0L, restored.id)
        assertEquals("T", restored.title)
    }

    @Test
    fun `backup payload survives a json round trip`() {
        val payload = BackupPayload(
            exportedAt = 123456789L,
            knowledgeItems = listOf(
                BackupKnowledgeItem(1, "Vocabulary", "Learning", 1.0, 2.0, 1, 0, null, 2000L, 100L, 200L),
            ),
            vocabularyDetails = listOf(BackupVocabularyDetail(1, "curb", "/kɜːb/", "控制", "e", "z")),
            preferences = BackupPreferences(learningGoal = "日常口语", topics = setOf("科技", "旅行"), dailyMinutes = 12),
        )
        val json = Json.encodeToString(BackupPayload.serializer(), payload)
        val decoded = Json.decodeFromString(BackupPayload.serializer(), json)
        assertEquals(payload, decoded)
    }

    @Test
    fun `preferences without secrets keep the ai key and base url out of the payload`() {
        // 编译期保证：BackupPreferences 根本没有 aiApiKey / aiBaseUrl / speechKey 字段。
        val fieldNames = BackupPreferences::class.java.declaredFields.map { it.name }
        assertEquals(false, fieldNames.any { it.contains("ApiKey", ignoreCase = true) })
        assertEquals(false, fieldNames.any { it.contains("SpeechKey", ignoreCase = true) })
    }
}
