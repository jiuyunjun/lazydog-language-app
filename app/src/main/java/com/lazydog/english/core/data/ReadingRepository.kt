package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.ReadingMaterialEntity
import com.lazydog.english.domain.generation.GeneratedReading
import com.lazydog.english.domain.generation.ReadingQuestion
import com.lazydog.english.domain.generation.ReadingTargetGrammar
import com.lazydog.english.domain.generation.ReadingTargetWord
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 阅读材料的读写入口。目标词/语法/题目在实体里以 JSON 存储，这里负责编解码。 */
class ReadingRepository(
    database: AppDatabase,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.readingDao()

    val recent: Flow<List<ReadingMaterialEntity>> = dao.observeRecent()

    suspend fun saveGenerated(
        reading: GeneratedReading,
        topic: String,
        archetype: String,
        model: String,
        promptVersion: Int,
        schemaVersion: Int,
        validationNotes: List<String>,
    ): Long = dao.insert(
        ReadingMaterialEntity(
            title = reading.title,
            body = reading.body,
            source = SOURCE_AI,
            topic = topic,
            readerPayoff = reading.readerPayoff,
            archetype = archetype,
            teaser = reading.teaser,
            category = reading.category.ifBlank { topic },
            estimatedCefr = reading.estimatedCefr,
            targetWordsJson = ReadingJson.encodeWords(reading.targetVocabulary),
            grammarJson = ReadingJson.encodeGrammar(reading.targetGrammar),
            questionsJson = ReadingJson.encodeQuestions(reading.comprehensionQuestions),
            model = model,
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            validationNotes = validationNotes.joinToString("\n"),
            createdAt = now().toEpochMilli(),
        ),
    )

    suspend fun savePasted(title: String, body: String): Long = dao.insert(
        ReadingMaterialEntity(
            title = title.trim(),
            body = body.trim(),
            source = SOURCE_PASTED,
            topic = "",
            estimatedCefr = "",
            targetWordsJson = "[]",
            grammarJson = "[]",
            questionsJson = "[]",
            model = "",
            promptVersion = 0,
            schemaVersion = 0,
            validationNotes = "",
            createdAt = now().toEpochMilli(),
        ),
    )

    /**
     * 最近几篇的标题和写法（§20）。
     *
     * 不只按主题去重：真正让人腻的是**结构和句式**一模一样——
     * 十篇不同主题的"Why X is actually Y"仍然是十篇同一个东西。
     */
    suspend fun recentShape(limit: Int = 12): RecentReadingShape {
        val recent = dao.recentGenerated(limit)
        return RecentReadingShape(
            titles = recent.map { it.title },
            archetypes = recent.map { it.archetype }.filter { it.isNotBlank() },
        )
    }

    suspend fun get(id: Long): ReadingMaterialEntity? = dao.getById(id)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun markCompleted(id: Long) = dao.markCompleted(id)

    suspend fun setLiked(id: Long, liked: Boolean) = dao.setLiked(id, liked)

    suspend fun setSaved(id: Long, saved: Boolean) = dao.setSaved(id, saved)

    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_PASTED = "pasted"
    }
}

/** 最近读过的"形状"：标题和写法，生成下一篇时用来避开雷同。 */
data class RecentReadingShape(
    val titles: List<String>,
    val archetypes: List<String>,
)

/** 实体内嵌 JSON 的编解码。解码失败返回空列表，坏数据不炸页面。 */
object ReadingJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeWords(words: List<ReadingTargetWord>): String =
        json.encodeToString(ListSerializer(ReadingTargetWord.serializer()), words)

    fun decodeWords(raw: String): List<ReadingTargetWord> =
        runCatching { json.decodeFromString(ListSerializer(ReadingTargetWord.serializer()), raw) }
            .getOrDefault(emptyList())

    fun encodeGrammar(grammar: List<ReadingTargetGrammar>): String =
        json.encodeToString(ListSerializer(ReadingTargetGrammar.serializer()), grammar)

    fun decodeGrammar(raw: String): List<ReadingTargetGrammar> =
        runCatching { json.decodeFromString(ListSerializer(ReadingTargetGrammar.serializer()), raw) }
            .getOrDefault(emptyList())

    fun encodeQuestions(questions: List<ReadingQuestion>): String =
        json.encodeToString(ListSerializer(ReadingQuestion.serializer()), questions)

    fun decodeQuestions(raw: String): List<ReadingQuestion> =
        runCatching { json.decodeFromString(ListSerializer(ReadingQuestion.serializer()), raw) }
            .getOrDefault(emptyList())
}
