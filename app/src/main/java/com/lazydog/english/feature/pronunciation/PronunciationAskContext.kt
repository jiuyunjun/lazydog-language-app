package com.lazydog.english.feature.pronunciation

import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.pronunciation.PerceptionOption
import com.lazydog.english.domain.pronunciation.PerceptionQuestion
import com.lazydog.english.domain.pronunciation.Phoneme

internal fun Phoneme.toAskContext(word: String? = null): AskContext = AskContext(
    kind = AskContextKind.Pronunciation,
    title = "/$ipa/ · $shortDescriptionZh",
    details = buildList {
        add(AskDetail("发音动作", articulation.keyActionZh))
        add(AskDetail("例词", word ?: exampleWords.joinToString { it.word }))
        add(AskDetail("常见误读", commonErrors.joinToString("\n") {
            "${it.exampleEn} 误读成 ${it.soundsLikeEn}；${it.fixZh}"
        }))
    },
    suggestions = listOf("这个音的发音动作怎么做？", "怎么区分这些易混的读音？"),
)

/** 未提交答案时完全不提供本题上下文，避免把原音频对应的词交给 AI。 */
internal fun perceptionAskContext(question: PerceptionQuestion?, chosen: PerceptionOption?): AskContext? {
    if (question == null || chosen == null) return null
    return AskContext(
        kind = AskContextKind.Question,
        title = "听辨 · ${question.promptWord}",
        details = listOf(
            AskDetail("播放的词", "${question.promptWord} /${question.promptIpa}/"),
            AskDetail("选项", question.options.joinToString { "${it.label} /${it.ipa}/" }),
            AskDetail("我的选择", chosen.label),
            AskDetail("正确答案", question.correctOption.label),
        ),
        suggestions = listOf("这两个音有什么区别？", "下次听的时候该注意什么？"),
    )
}
