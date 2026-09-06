package com.lazydog.english.domain.generation

import com.lazydog.english.domain.vocabulary.PartOfSpeech

/** 完整学习卡必须具备双语例句和可判重的词性。 */
fun validateWordCard(word: GeneratedWord): String? = when {
    word.term.isBlank() || word.term.length > 80 -> "没有拿到有效词形"
    PartOfSpeech.parse(word.pos) == null -> "没有拿到有效词性"
    word.meaningZh.isBlank() -> "缺少中文释义"
    word.exampleEn.isBlank() || word.exampleZh.isBlank() -> "缺少例句或例句翻译，请重新生成"
    else -> null
}
