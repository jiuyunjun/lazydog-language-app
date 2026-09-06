package com.lazydog.english.feature.grammar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.*
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.generation.GeneratedGrammarLesson

/** Shared lesson content; saving is owned by the hosting screen. */
@Composable
fun GrammarLearningContent(lesson: GeneratedGrammarLesson) {
    val extended = LazyDogTheme.extendedColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        InteractiveEnglishText(
            text = lesson.patternEn,
            style = MaterialTheme.typography.headlineSmall,
        )
        GrammarPatternZhLine(lesson.patternEn)
        InteractiveEnglishText(
            text = lesson.labelZh,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    InteractiveEnglishText(
        text = lesson.summaryZh,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("怎么用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        // 讲解正文里少不了夹英文（`have gone`、`will`），那些正是要查的词。
        InteractiveEnglishText(
            text = lesson.explanationZh,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // 公式和讲解里出现的英语术语逐个解释一次，不然"过去分词"四个字本身也是天书。
    GrammarTermsCard(lesson.patternEn, lesson.explanationZh, lesson.tipZh)
    ExampleBlock(
        icon = Icons.Outlined.CheckCircle,
        tint = extended.correct,
        label = "这样说",
        sentence = lesson.goodExampleEn,
        note = lesson.goodExampleZh,
        speakSource = PlaybackSource.sentence(lesson.goodExampleEn),
    )
    if (lesson.badExampleEn.isNotBlank()) {
        ExampleBlock(
            icon = Icons.Outlined.Cancel,
            tint = MaterialTheme.colorScheme.error,
            label = "容易说错",
            sentence = lesson.badExampleEn,
            note = lesson.badExampleNoteZh,
            speakSource = null,
        )
    }
    if (lesson.tipZh.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("易混提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                InteractiveEnglishText(
                    text = lesson.tipZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExampleBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    sentence: String,
    note: String,
    speakSource: PlaybackSource?,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                if (speakSource != null) {
                    SpeakButton(speakSource, contentDescription = "朗读例句", iconSize = 20.dp)
                }
            }
            InteractiveEnglishText(
                text = sentence,
                style = MaterialTheme.typography.bodyLarge,
                // 反面例句故意是错的，读出来只会把错的读法记进耳朵。
                speakOnSingleTap = speakSource != null,
            )
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InteractiveTextHint(speakOnSingleTap = speakSource != null)
        }
    }
}
