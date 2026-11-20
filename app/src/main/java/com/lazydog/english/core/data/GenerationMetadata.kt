package com.lazydog.english.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 这张卡和哪个词典义项对上了（D-095）。存在 `knowledge_items.generationMetadataJson` 的
 * `dictionarySense` 键下，不加列、不迁移。
 *
 * [order] 为 null 表示这一版词典里对不上（未对齐）：同一个 [pack] + [version] 下不再重试，
 * 词典升级后再试一次。
 */
@Serializable
data class DictionarySenseTag(
    val pack: String,
    val version: Int,
    val pos: String = "",
    val order: Int? = null,
) {
    val matched: Boolean get() = order != null
}

/**
 * 读写知识项上那段生成信息 JSON。
 *
 * 那段 JSON 原来只装 [LearningCardMetadata]（模型、提示词版本、生成时间……），现在多了一个
 * `dictionarySense` 键。两者是独立的：补全写回生成信息时要把对齐信息带上，回填写对齐信息时
 * 也不能把生成信息冲掉。
 */
object GenerationMetadata {

    private const val KEY_DICTIONARY_SENSE = "dictionarySense"
    private const val KEY_MODEL = "model"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun parse(raw: String): JsonObject? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /**
     * 这张卡有没有过一次模型生成（`isQuickCard` 的判据）。
     *
     * 原来的判据是"生成信息非空"。回填之后，一张还没补全的速记卡也会带上 `dictionarySense`，
     * 再按非空判断它就成了"完整卡"，从此不会再补全。所以改成看有没有 `model`。
     * 解析不了的非空内容按"有"处理，和改之前的行为一致。
     */
    fun hasGenerationInfo(raw: String): Boolean {
        if (raw.isBlank()) return false
        val obj = parse(raw) ?: return true
        return KEY_MODEL in obj
    }

    fun dictionarySenseOf(raw: String): DictionarySenseTag? =
        parse(raw)?.get(KEY_DICTIONARY_SENSE)?.let {
            runCatching { json.decodeFromJsonElement<DictionarySenseTag>(it) }.getOrNull()
        }

    /** 在 [raw] 上写入（或替换）对齐信息，其余键原样保留。 */
    fun withDictionarySense(raw: String, tag: DictionarySenseTag): String {
        val base = parse(raw) ?: JsonObject(emptyMap())
        return JsonObject(base + (KEY_DICTIONARY_SENSE to json.encodeToJsonElement(tag))).toString()
    }

    /** 用 [replacement] 整段替换生成信息时，把 [previous] 里的对齐信息带过去。 */
    fun carryDictionarySense(previous: String, replacement: String): String {
        val tag = dictionarySenseOf(previous) ?: return replacement
        if (dictionarySenseOf(replacement) != null) return replacement
        return withDictionarySense(replacement, tag)
    }
}
