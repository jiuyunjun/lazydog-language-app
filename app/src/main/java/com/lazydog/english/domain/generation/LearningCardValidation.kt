package com.lazydog.english.domain.generation

import com.lazydog.english.domain.vocabulary.PartOfSpeech

/** 查词允许简短解释，记录中的完整学习卡还必须具备例句和可判重的词性。 */
fun validateWordCard(word: WordExplanation): String? = when {
    word.headword.isBlank() || word.headword.length > 80 -> "没有拿到有效词形"
    PartOfSpeech.parse(word.pos) == null -> "没有拿到有效词性"
    word.meaningZh.isBlank() -> "缺少中文释义"
    word.exampleEn.isBlank() || word.exampleZh.isBlank() -> "缺少例句或例句翻译，请重新生成"
    else -> null
}
