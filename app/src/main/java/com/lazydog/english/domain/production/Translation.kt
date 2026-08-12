package com.lazydog.english.domain.production

import com.lazydog.english.domain.practice.GrammarErrorTag
import com.lazydog.english.domain.practice.MistakeSummary

/**
 * 中译英产出练习。
 *
 * 存在的理由：认词、读讲解、做选择题都是识别，写不出来的人照样写不出来。
 * 每天两三句自己写，错在哪一类形式由判定返回，直接喂回错题画像决定明天讲什么。
 */
data class TranslationRequest(
    val learnerLevel: String,
    val count: Int = 2,
    /** 最近学的语法点，让句子有的放矢。 */
    val targetGrammar: List<String> = emptyList(),
    /** 最近复习过的词，顺便在产出里再遇见一次。 */
    val targetVocabulary: List<String> = emptyList(),
    /** 最近错得最多的形式，优先设计成必须用上这些形式的句子。 */
    val weakSpots: List<MistakeSummary> = emptyList(),
)

data class TranslationTask(
    /** 要表达的中文。 */
    val promptZh: String,
    /** 参考答案，提交后才给看。 */
    val referenceEn: String,
    /** 卡壳时的提示：结构或关键词，不给整句。 */
    val hintZh: String,
    /** 这句主要练什么形式，见 [GrammarErrorTag]。 */
    val errorTag: String,
)

/** 判定结果：意思对不对、形式对不对，分开说。 */
enum class TranslationVerdict(val value: String, val labelZh: String) {
    Ok("ok", "写对了"),
    Minor("minor", "意思到了，形式还差点"),
    Wrong("wrong", "这句没写对"),
    ;

    companion object {
        fun from(raw: String): TranslationVerdict =
            entries.firstOrNull { it.value == raw.trim().lowercase() } ?: Wrong
    }
}

data class TranslationFeedback(
    val verdict: TranslationVerdict,
    /** 在用户原句基础上改对的版本，不是推翻重写。 */
    val correctedEn: String,
    val noteZh: String,
    /** 错在哪几类形式；写对时为空。 */
    val errorTags: List<String>,
)

object TranslationValidation {

    const val MAX_TASKS = 5
    const val MAX_ANSWER_LENGTH = 400

    /** 逐条校验，丢掉坏题；一条不剩时调用方按失败处理。 */
    fun validateTasks(tasks: List<TranslationTask>, maxCount: Int): List<TranslationTask> =
        tasks.mapNotNull { task ->
            val clean = TranslationTask(
                promptZh = task.promptZh.trim(),
                referenceEn = task.referenceEn.trim(),
                hintZh = task.hintZh.trim(),
                errorTag = GrammarErrorTag.normalize(task.errorTag),
            )
            clean.takeIf { problem(it) == null }
        }.take(maxCount.coerceAtMost(MAX_TASKS))

    /** null 表示通过。 */
    fun problem(task: TranslationTask): String? = when {
        task.promptZh.isBlank() -> "没给要表达的中文"
        task.referenceEn.isBlank() -> "没给参考答案"
        // 参考答案必须是英文句子，不能又是一句中文。
        task.referenceEn.none { it in 'A'..'Z' || it in 'a'..'z' } -> "参考答案不是英文"
        task.promptZh.length > 120 -> "中文太长，一句话就够"
        else -> null
    }

    fun validateFeedback(feedback: TranslationFeedback): String? = when {
        feedback.correctedEn.isBlank() -> "没给改好的句子"
        feedback.noteZh.isBlank() -> "没说错在哪"
        else -> null
    }

    /**
     * 判定返回的错误标签归一化；写对时不记错题，
     * 免得"意思到了"也被算成一次形式错误污染画像。
     */
    fun mistakeTags(feedback: TranslationFeedback): List<String> =
        if (feedback.verdict == TranslationVerdict.Ok) {
            emptyList()
        } else {
            feedback.errorTags.map { GrammarErrorTag.normalize(it) }.distinct().take(2)
        }
}
