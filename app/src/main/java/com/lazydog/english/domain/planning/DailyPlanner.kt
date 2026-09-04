package com.lazydog.english.domain.planning

import com.lazydog.english.domain.progress.Mood

/**
 * 今日计划：按每日时长预算和到期量排步骤（ARCHITECTURE.md §6 优先级：
 * 到期复习最优先，然后新知识，再阅读和朗读）。纯函数，便于单测。
 */
enum class DailyStep(val id: String, val title: String, val minutes: Int) {
    Words("words", "单词：复习 + 新词", 5),
    // 语法这一步不只是读讲解，还要当场做几道填空题，所以比原来多给两分钟。
    Grammar("grammar", "语法：讲一条 + 做几道题", 4),
    // 排在语法后面：刚讲过的形式立刻自己写一遍，比隔天再练有用。
    Production("production", "表达：两句中译英", 2),
    Reading("reading", "读一篇定制短文", 4),
    Speaking("speaking", "朗读几句", 2),
}

data class PlannedStep(
    val step: DailyStep,
    val note: String,
)

object DailyPlanner {

    fun plan(
        dailyMinutes: Int,
        dueVocabCount: Int,
        dueGrammarCount: Int,
        mood: Mood = Mood.Normal,
    ): List<PlannedStep> {
        // 刚回来或者已经累了：今天只排一步。
        // 中断几天回来看到五步待办，人只会再关掉一次（`持续学习DESIGN.md` §26、§25）。
        // 注意这里**不提到期数量**——"你欠 74 个复习"正是这一节点名要避免的说法。
        when (mood) {
            Mood.Comeback -> return listOf(PlannedStep(DailyStep.Words, "先热几分钟身，别管积压的"))
            Mood.Tired -> return listOf(PlannedStep(DailyStep.Words, "今天已经做了不少 · 短复习就够"))
            Mood.Normal -> Unit
        }
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
        if (budget >= DailyStep.Production.minutes) {
            result.add(PlannedStep(DailyStep.Production, "把中文写成英文，判完记进错题"))
            budget -= DailyStep.Production.minutes
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
