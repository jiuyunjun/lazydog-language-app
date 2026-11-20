package com.lazydog.english.core.data

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.generation.NewWordPickRequest
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.generation.SenseChoiceItem
import com.lazydog.english.domain.vocabulary.DictionaryAlignment
import com.lazydog.english.domain.vocabulary.DictionaryEntry
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.DictionarySense
import com.lazydog.english.domain.vocabulary.EnrichedWord
import com.lazydog.english.domain.vocabulary.EnrichmentResult
import com.lazydog.english.domain.vocabulary.QuickCard
import com.lazydog.english.domain.vocabulary.QuickCardEnrichment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 每日新词走词典的那条路（D-095）：
 *
 * 1. [pick]：本地候选 → 词典词条 → 模型一次小调用挑词并选义项（只回序号）；
 * 2. 卡片立刻用词典里的身份显示；
 * 3. [enrich]：每个词单独走指定词生成，补例句、搭配、记忆提示、拼写材料，按速记卡补全的规则合并——
 *    词性、释义、美式音标都以词典为准，模型不许改。
 *
 * 页面只管状态；挑词、补全、合并、生成信息都在这里。
 */
class DictionaryNewWords(
    private val dictionary: DictionaryLookup,
    private val generator: LearningContentGenerator,
) {

    /** 一个挑定的词：词典词条和要教的那一项。 */
    data class Draft(val entry: DictionaryEntry, val sense: DictionarySense) {
        val term: String get() = entry.lemma
        val pos: String get() = sense.pos.wire

        /** 卡片先显示的音标：词典有就用词典的。 */
        val ipa: String get() = entry.ipa

        internal fun toQuickCard() = QuickCard(
            term = term,
            pos = pos,
            meaningZh = sense.meaningZh,
            // 只有 CMUdict 的美式音标锁死；退回来的 ECDICT 原音标偏英式，让模型写的那份优先。
            ipa = entry.ipa.takeIf { entry.ipaSource == DictionaryAlignment.IPA_SOURCE_US }.orEmpty(),
            exampleEn = "",
            exampleZh = "",
            memoryHintZh = "",
            forms = entry.irregularForms,
            collocations = emptyList(),
        )
    }

    /** 补全的结果：合并后的教学材料，和那次生成用的模型、提示词版本（写生成信息要用）。 */
    data class Filled(val word: EnrichedWord, val model: String, val promptVersion: Int)

    /**
     * 词典不可用、或者候选在词典里一个都查不到时返回 null：调用方退回原来"模型直接写整张卡"的路。
     * 返回 Failure 是挑词这一步本身失败（没网、没配密钥、模型答得不对）。
     */
    suspend fun pick(
        candidates: List<String>,
        count: Int,
        learnerLevel: String,
        topics: List<String>,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<List<Draft>>? {
        if (dictionary.pack.version == 0) return null
        val entries = withContext(Dispatchers.IO) {
            candidates.mapNotNull { dictionary.entry(it) }.distinctBy { it.lemma.lowercase() }
        }
        if (entries.isEmpty()) return null
        val byTerm = entries.associateBy { it.lemma.lowercase() }
        val result = generator.pickNewWords(
            NewWordPickRequest(
                count = count,
                learnerLevel = learnerLevel,
                topics = topics,
                candidates = entries.map { SenseChoiceItem(key = it.lemma, term = it.lemma, options = it.senses) },
            ),
            onStage = onStage,
        )
        return when (result) {
            is GenerationResult.Failure -> result
            is GenerationResult.Success -> GenerationResult.Success(
                result.data.mapNotNull { pick -> byTerm[pick.term.lowercase()]?.let { Draft(it, pick.sense) } },
                result.model,
                result.promptVersion,
                result.droppedNotes,
            )
        }
    }

    /** 补一个词的教学材料。失败（没网、模型换了词或词性）返回 null，卡片照样能学，入库后交给后台补全。 */
    suspend fun enrich(draft: Draft, learnerLevel: String, topics: List<String>): Filled? {
        val result = try {
            generator.generateNewWords(
                NewWordsRequest(
                    count = 1,
                    learnerLevel = learnerLevel,
                    topics = topics,
                    knownTerms = emptyList(),
                    targetTerm = draft.term,
                    confirmedSenseZh = draft.sense.labelZh,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val success = result as? GenerationResult.Success ?: return null
        val generated = success.data.firstOrNull() ?: return null
        return when (val merged = QuickCardEnrichment.merge(draft.toQuickCard(), generated)) {
            is EnrichmentResult.Rejected -> null
            is EnrichmentResult.Merged -> Filled(
                word = merged.word.copy(ipa = merged.word.ipa.ifBlank { draft.entry.ipa }),
                model = success.model,
                promptVersion = success.promptVersion,
            )
        }
    }

    /**
     * 入库时的生成信息：对齐信息总是写；补全过的再加上模型、提示词版本这些。
     * 没补全的那张只有对齐信息，按 `isQuickCard` 仍是速记卡，后台会接着补。
     */
    fun metadataJson(draft: Draft, filled: Filled?, learnerLevel: String, topics: List<String>): String {
        val tag = DictionarySenseTag(
            pack = dictionary.pack.id,
            version = dictionary.pack.version,
            pos = draft.pos,
            order = draft.sense.order,
        )
        val generated = filled?.let {
            Json.encodeToString(
                LearningCardMetadata(
                    input = draft.term,
                    context = "",
                    learnerLevel = learnerLevel,
                    topics = topics,
                    model = it.model,
                    promptVersion = it.promptVersion,
                    generatedAt = System.currentTimeMillis(),
                    origin = "dictionary",
                ),
            )
        }.orEmpty()
        return GenerationMetadata.withDictionarySense(generated, tag)
    }
}
