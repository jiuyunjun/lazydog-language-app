package com.lazydog.english.core.data

import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.WordExplanation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 仅在用户确认时随知识项一起保存，生成阶段不写数据库。 */
@Serializable
data class LearningCardMetadata(
    val input: String,
    val context: String,
    val learnerLevel: String,
    val topics: List<String>,
    val model: String,
    val promptVersion: Int,
    val generatedAt: Long,
    val schemaVersion: Int = 1,
    val validationStatus: String = "validated",
)

suspend fun KnowledgeRepository.saveWordCard(
    result: GenerationResult.Success<WordExplanation>,
    metadata: LearningCardMetadata,
): Long? = result.data.let { word ->
    addVocabulary(
        term = word.headword, meaningZh = word.meaningZh, ipa = word.ipa,
        exampleEn = word.exampleEn, exampleZh = word.exampleZh, pos = word.pos,
        memoryHintZh = word.memoryHintZh, seenAs = word.term, forms = word.forms,
        generationMetadataJson = Json.encodeToString(metadata),
    )
}

suspend fun KnowledgeRepository.saveGrammarCard(
    result: GenerationResult.Success<GeneratedGrammarLesson>,
    metadata: LearningCardMetadata,
): Long? = result.data.let { lesson ->
    addGrammar(
        patternEn = lesson.patternEn, category = lesson.category, labelZh = lesson.labelZh,
        summaryZh = lesson.summaryZh, explanationZh = lesson.explanationZh,
        exampleEn = lesson.goodExampleEn, exampleZh = lesson.goodExampleZh,
        badExampleEn = lesson.badExampleEn, badExampleNoteZh = lesson.badExampleNoteZh,
        tipZh = lesson.tipZh, generationMetadataJson = Json.encodeToString(metadata),
    )
}
