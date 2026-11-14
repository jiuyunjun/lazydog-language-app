package com.lazydog.english.domain.production

import com.lazydog.english.domain.practice.GrammarErrorTag
import com.lazydog.english.domain.practice.MistakeSummary
import kotlin.random.Random

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
    /** 这一批句子各自落在什么场合（[TranslationSituation.pick]）；空表示不限定。 */
    val situations: List<String> = emptyList(),
    /** 学习者的兴趣，场合里的具体内容可以往这边靠。 */
    val topics: List<String> = emptyList(),
)

/**
 * 中译英句子的场合。
 *
 * 不给场合的话，模型每天出的都是同一类句子："我在这里住了三年""他昨天去了超市"。
 * 形式难点由错题画像定，场合由这里随机抽——两者正交，同一个时态可以落在道歉里，
 * 也可以落在吐槽里，而后者才是人真会想说出口的话。
 */
object TranslationSituation {
    val all = listOf(
        "跟朋友吐槽一件小事",
        "给同事发消息说明进度",
        "向陌生人问路或求助",
        "委婉地拒绝一个邀请",
        "为迟到或失误道歉并解释",
        "在店里退换东西或投诉",
        "向别人推荐一样东西并说理由",
        "讲一件自己刚搞砸的事",
        "约时间、改时间",
        "提醒家人或室友做一件事",
        "讲一个自己坚持了很久的习惯",
        "对一部电影、一顿饭或一次旅行下评语",
        "跟别人解释一个东西怎么用",
        "回忆小时候的一件事",
        "说一个还没实现的打算",
        "替别人转述一句话",
        "面试或自我介绍里的一句",
        "安慰一个心情不好的朋友",
        "讨价还价或商量条件",
        "发一条朋友圈式的感慨",
    )

    /** 抽 [count] 个互不相同的场合，一句一个。 */
    fun pick(count: Int, random: Random = Random.Default): List<String> =
        all.shuffled(random).take(count.coerceIn(0, all.size))
}

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
