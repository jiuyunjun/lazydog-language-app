package com.lazydog.english.core.model

/** 知识项类型。数据库里存 name 字符串。 */
enum class KnowledgeType { Vocabulary, Grammar }

/** 知识项掌握阶段，五档，允许回退（ARCHITECTURE.md KnowledgeItem.stage）。 */
enum class KnowledgeStage(val label: String) {
    Unseen("未接触"),
    Exposed("见过"),
    Learning("学习中"),
    Familiar("基本掌握"),
    Mastered("稳定掌握"),
}

/** 四档自评，固定顺序，后续映射 FSRS Again / Hard / Good / Easy。 */
/**
 * 复习评分。同一个档位在两种场景下问的不是一件事，所以有两套文案：
 * [label] 用于复习（"刚才想起来了吗"），[newLabel] 用于第一次见到的新词
 * （"这个词你原来认识吗"）——新词从没记过，说"忘了"不成立。
 */
enum class ReviewGrade(val label: String, val newLabel: String, val nextHint: String) {
    Forgot("忘了", "完全没见过", "1 分钟后"),
    Hard("有点印象", "有点眼熟", "10 分钟后"),
    Good("想起来了", "大概知道", "2 天后"),
    Easy("很熟", "早就会了", "6 天后"),
}

/** 今日任务清单里一行的类型，用于 UI 选图标。 */
enum class TaskKind { Review, NewWords, Grammar, Reading, Speaking, Quiz }

data class TodayTaskPreview(
    val kind: TaskKind,
    val name: String,
    val note: String,
    val minutes: String,
)

data class WordCard(
    val word: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    val encounterNote: String,
)

data class QuizQuestion(
    val tag: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
)

data class VocabEntry(
    val word: String,
    val meaningZh: String,
    val stage: KnowledgeStage,
    val dueText: String,
    val dueToday: Boolean,
)

data class GrammarEntry(
    val name: String,
    val stage: KnowledgeStage,
    val note: String,
)

data class RecentMaterial(
    val name: String,
    val note: String,
)
