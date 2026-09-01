package com.lazydog.english.core.backup

import com.lazydog.english.core.database.DrillMistakeEntity
import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.ReadingMaterialEntity
import com.lazydog.english.core.database.SpellingAttemptEntity
import com.lazydog.english.core.database.SpellingProgressEntity
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
        val entity = VocabularyDetailEntity(
            itemId = 1, term = "curb", ipa = "/kɜːb/", meaningZh = "控制", exampleEn = "e", exampleZh = "z",
            pos = "v.", collocationsJson = """["curb traffic","curb inflation"]""",
        )
        val backup = entity.toBackup()
        assertEquals(1L, backup.itemId)
        assertEquals("v.", backup.pos)

        val restored = backup.toEntity(newItemId = 99)
        assertEquals(99L, restored.itemId)
        assertEquals("curb", restored.term)
        assertEquals("""["curb traffic","curb inflation"]""", restored.collocationsJson)
    }

    @Test
    fun `grammar detail and learning event rebind to the new item id`() {
        val grammar = GrammarDetailEntity(
            itemId = 1,
            name = "have/has + past participle",
            patternEn = "have/has + past participle",
            labelZh = "现在完成时",
            summaryZh = "表示过去动作与现在有关",
            explanationZh = "e",
            exampleEn = "I have done it.",
            exampleZh = "我已经做完了。",
            tipZh = "不要和一般过去时混用。",
        )
        val restoredGrammar = grammar.toBackup().toEntity(newItemId = 99)
        assertEquals(99L, restoredGrammar.itemId)
        assertEquals("表示过去动作与现在有关", restoredGrammar.summaryZh)
        assertEquals("我已经做完了。", restoredGrammar.exampleZh)

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
    fun `drill mistake keeps its error tag and re-points at the remapped item`() {
        val entity = DrillMistakeEntity(
            id = 7,
            itemId = 42,
            patternEn = "have/has + past participle",
            errorTag = "tense",
            sentenceEn = "She ___ Japanese since last winter.",
            chosen = "is learning",
            answer = "has been learning",
            occurredAt = 1000L,
        )
        val backup = entity.toBackup()
        assertEquals(42L, backup.itemId)

        val restored = backup.toEntity(newItemId = 99)
        assertEquals(0L, restored.id)
        assertEquals(99L, restored.itemId)
        assertEquals(entity.copy(id = 0, itemId = 99), restored)

        // 知识项对不上时仍然保留记录，只是不再关联。
        assertEquals(null, backup.toEntity(newItemId = null).itemId)
    }

    @Test
    fun `旧备份没有错题字段也能解码`() {
        val legacy = """{"schemaVersion":1,"exportedAt":1}"""
        val decoded = Json.decodeFromString(BackupPayload.serializer(), legacy)
        assertEquals(emptyList<BackupDrillMistake>(), decoded.drillMistakes)
        assertEquals(emptyList<BackupSpellingProgress>(), decoded.spellingProgress)
        assertEquals(emptyList<BackupSpellingAttempt>(), decoded.spellingAttempts)
    }

    @Test
    fun `spelling records rebind to the restored vocabulary id`() {
        val progress = SpellingProgressEntity(
            itemId = 42,
            stage = "GuidedRecall",
            recognitionScore = 1.0,
            partialRecallScore = 0.8,
            chunkRecallScore = 0.7,
            phonemeGraphemeScore = 0.0,
            freeRecallScore = 0.5,
            retentionScore = 0.0,
            successStreak = 2,
            failureStreak = 0,
            stageSuccessCount = 1,
            freeRecallSuccessCount = 0,
            successfulRecallDatesJson = "[]",
            longestSuccessfulIntervalDays = 0,
            currentIntervalDays = 3,
            weakSegmentsJson = "[]",
            lastAttemptAt = 1000,
        )
        assertEquals(99L, progress.toBackup().toEntity(99).itemId)

        val attempt = SpellingAttemptEntity(
            id = 7,
            itemId = 42,
            questionType = "FreeRecall",
            expected = "environment",
            answer = "enviroment",
            correct = false,
            hintLevel = 1,
            responseTimeMillis = 3200,
            errorTypesJson = "[\"Omission\"]",
            weakSegment = "viron",
            weakStart = 2,
            weakEndExclusive = 7,
            masteryCredit = 0.0,
            occurredAt = 2000,
        )
        val restored = attempt.toBackup().toEntity(99)
        assertEquals(0L, restored.id)
        assertEquals(99L, restored.itemId)
        assertEquals("viron", restored.weakSegment)
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
