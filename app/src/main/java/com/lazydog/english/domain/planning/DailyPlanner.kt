package com.lazydog.english.domain.planning

/**
 * 今日计划：按每日时长预算和到期量排步骤（ARCHITECTURE.md §6 优先级：
 * 到期复习最优先，然后新知识，再阅读和朗读）。纯函数，便于单测。
 */
enum class DailyStep(val id: String, val title: String, val minutes: Int) {
    Words("words", "单词：复习 + 新词", 5),
    // 语法这一步不只是读讲解，还要当场做几道填空题，所以比原来多给两分钟。
    Grammar("grammar", "语法：讲一条 + 做几道题", 4),
    Reading("reading", "读一篇定制短文", 4),
    Speaking("speaking", "朗读几句", 2),
}

data class PlannedStep(
    val step: DailyStep,
    val note: String,
)

object DailyPlanner {

    fun plan(dailyMinutes: Int, dueVocabCount: Int, dueGrammarCount: Int): List<PlannedStep> {
        val result = mutableListOf<PlannedStep>()
        var budget = dailyMinutes.coerceAtLeast(DailyStep.Words.minutes)

        // 单词永远排第一：有到期先还债，没到期就上新。
        result.add(
            PlannedStep(
                DailyStep.Words,
                if (dueVocabCount > 0) "$dueVocabCount 个词到期 · 还完可上新" else "没有到期 · 直接学新词",
            ),
        )
        budget -= DailyStep.Words.minutes

        if (budget >= DailyStep.Grammar.minutes) {
            result.add(
                PlannedStep(
                    DailyStep.Grammar,
                    if (dueGrammarCount > 0) "$dueGrammarCount 个语法点到期 · 直接做题" else "让 AI 挑一个合适的",
                ),
            )
            budget -= DailyStep.Grammar.minutes
        }
        if (budget >= DailyStep.Reading.minutes) {
            result.add(PlannedStep(DailyStep.Reading, "到期词会编进文章里"))
            budget -= DailyStep.Reading.minutes
        }
        if (budget >= DailyStep.Speaking.minutes) {
            result.add(PlannedStep(DailyStep.Speaking, "从你的例句里挑"))
            budget -= DailyStep.Speaking.minutes
        }
        return result
    }
}
