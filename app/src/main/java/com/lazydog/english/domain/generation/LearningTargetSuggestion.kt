package com.lazydog.english.domain.generation

/**
 * 用中文说"我想学什么"时，AI 给回来的候选（`UI_BRIEF.md` §4.3）。
 *
 * 存在的理由：想学的东西经常是知道中文、不知道英文的那一类——「深思熟虑」「表示后悔的说法」
 * 「已经做完了怎么说」。要求用户先自己译成英文，等于把最难的一步留给他，然后才让他学。
 *
 * 这一步只挑目标，不生成学习卡：挑中之后走的仍然是原来那条
 * 「英文原形 / 语法名称 → 完整学习卡 → 添加」的路，一个词条也不会因为这一步提前进记录。
 */
data class LearningTargetSuggestion(
    /**
     * 挑中后交给生成的那个目标。
     *
     * 单词是英文原形；语法是英文结构公式或中文语法名称——语法生成本来就两种都收，
     * 这里不强行统一成一种，模型说得清楚哪个就给哪个。
     */
    val target: String,
    /** 中文标签：单词写"形容词 深思熟虑的"，语法写"现在完成时"。 */
    val labelZh: String,
    /** 一句话说清它和用户那句中文的关系，或者它跟旁边那条候选差在哪。 */
    val noteZh: String,
)

/** 一次候选请求。[queryZh] 是用户输入的那句中文，不做任何改写就发出去。 */
data class LearningTargetRequest(
    val queryZh: String,
    val isVocab: Boolean,
    val learnerLevel: String,
    val topics: List<String> = emptyList(),
)

/** 候选最多给这么多条。再多用户就不是在挑，是在读列表了。 */
const val MAX_LEARNING_TARGETS = 6

/**
 * 逐条过滤候选：留下站得住的，一条都不剩就让整次调用失败。
 *
 * 单词候选必须是英文——模型偶尔会把用户那句中文原样退回来当作"候选"，
 * 那一条挑中之后只会拿中文去生成英文单词卡，错得很安静。语法不做这个限制：
 * 「现在完成时」本来就是合法的语法目标。
 */
fun filterLearningTargets(
    suggestions: List<LearningTargetSuggestion>,
    isVocab: Boolean,
): List<LearningTargetSuggestion> = suggestions
    .map {
        LearningTargetSuggestion(it.target.trim(), it.labelZh.trim(), it.noteZh.trim())
    }
    .filter { it.target.isNotEmpty() && it.target.length <= 80 && it.labelZh.isNotEmpty() }
    .filter { !isVocab || it.target.isLatinTerm() }
    .distinctBy { it.target.lowercase() }
    .take(MAX_LEARNING_TARGETS)

/** 只由拉丁字母、空格和词内标点组成：英文单词或短语该有的样子。 */
private fun String.isLatinTerm(): Boolean =
    isNotEmpty() && all { it.isLetter() && it.code < 0x80 || it == ' ' || it == '-' || it == '\'' } &&
        any { it.isLetter() }
