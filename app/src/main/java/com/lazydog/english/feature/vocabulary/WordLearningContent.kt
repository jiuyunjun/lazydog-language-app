package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.*
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.generation.*
import com.lazydog.english.domain.spelling.SpellingFacts
import com.lazydog.english.domain.vocabulary.SenseKey
import com.lazydog.english.domain.vocabulary.posLabelZh

/** Shared content for study and unsaved learning-card previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordLearningContent(
    card: GeneratedWord,
    revealed: Boolean = true,
    isNew: Boolean = true,
    itemId: Long? = null,
    facts: SpellingFacts = SpellingFacts(card.chunks, card.trickyPart, card.misspellings),
    memoryAssistance: GenerationResult.Success<MemoryAssistance>? = null,
    onMemoryHint: (GenerationResult.Success<MemoryAssistance>) -> Unit,
) {
    val copy = appCopy
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InteractiveEnglishText(
            text = card.term,
            style = if (revealed) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
        )
        SpeakButton(PlaybackSource.word(card.term), contentDescription = "再读一遍")
    }
    if (card.ipa.isNotBlank()) {
        Text(
            text = card.ipa,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (isNew && !revealed) {
        Text(
            text = copy.wordNewCardHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (revealed) {
        Text(
            text = if (card.pos.isNotBlank()) "${posLabelZh(card.pos)} ${card.meaningZh}" else card.meaningZh,
            style = MaterialTheme.typography.titleMedium,
        )
        // 视觉记忆图片放在释义之后而不是词头之上：它可能没有、也可能加载失败，
        // 放最上面时收起图片区会让词头每次开卡都跳位（`单词视觉记忆图片DESIGN.md` §45/§63）。
        // 草稿卡也有稳定的 senseKey，所以预览时找到的图，按下「添加」之后不用重搜。
        WordImagePanel(
            senseKey = itemId?.let { SenseKey.of(it) }
                ?: SenseKey.ofDraft(card.term, card.pos, card.meaningZh),
            term = card.term,
            meaningZh = card.meaningZh,
            pos = card.pos,
            exampleEn = card.exampleEn,
        )
        if (card.collocations.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.collocations.forEach { collocation -> CollocationChip(collocation) }
            }
        }
        // S0 接触（设计稿 62 屏）：新词第一次露面就把词块拆开摆着。
        // 拼写练习后面所有阶段都按这套词块出题，第一眼见到的结构和后面练的是同一套。
        if (isNew) SpellingChunks(card.term, facts)
        if (itemId != null) {
            MemoryHintPanel(itemId = itemId, fallbackHintZh = card.memoryHintZh)
        } else {
            MemoryHintPanel(
                term = card.term,
                meaningZh = card.meaningZh,
                pos = card.pos,
                hint = memoryAssistance?.data,
                onGenerated = onMemoryHint,
                fallbackHintZh = card.memoryHintZh,
            )
        }
        if (card.exampleEn.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        InteractiveEnglishText(
                            text = card.exampleEn,
                            style = MaterialTheme.typography.bodyLarge,
                            speakOnSingleTap = true,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        SpeakButton(
                            source = PlaybackSource.sentence(card.exampleEn),
                            contentDescription = "朗读例句",
                        )
                    }
                    if (card.exampleZh.isNotBlank()) {
                        Text(
                            text = card.exampleZh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 例句里的生词一样能双击查、三击讲，但双击三击是看不见的交互——
                    // 不写这一行，这块英文和一张图片没区别。
                    InteractiveTextHint(speakOnSingleTap = true)
                }
            }
        }
    }
}
