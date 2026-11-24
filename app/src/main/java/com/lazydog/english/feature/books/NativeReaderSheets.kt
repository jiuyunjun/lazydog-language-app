package com.lazydog.english.feature.books

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.WordEnrichmentWorker
import com.lazydog.english.core.database.BookEntity
import com.lazydog.english.core.database.BookGlossaryEntity
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.books.EnglishDose
import com.lazydog.english.domain.books.RenderedReplacement
import com.lazydog.english.domain.books.ScaffoldStage
import com.lazydog.english.domain.vocabulary.posLabelZh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 点了一处替换（设计文档 §5.7、§5.8）：看中文、听发音、加进生词本、纠错。
 * [onChanged] 在纠错之后调，阅读器重新渲染这一章。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplacementSheet(
    bookId: Long,
    chapterIndex: Int,
    replacement: RenderedReplacement,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val service = app.nativeBookService
    val glossary by remember(bookId) { app.bookRepository.observeGlossary(bookId) }.collectAsState(initial = emptyList())
    val row = glossary.firstOrNull { it.zh == replacement.zh }
    var meaning by remember { mutableStateOf("") }
    var ipa by remember { mutableStateOf("") }
    LaunchedEffect(replacement.lemma) {
        withContext(Dispatchers.IO) { app.dictionary.entry(replacement.lemma) }?.let { entry ->
            ipa = entry.ipa
            meaning = entry.summaryLines(perPos = 4).joinToString("\n")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InteractiveEnglishText(replacement.lemma, style = MaterialTheme.typography.headlineSmall)
                if (ipa.isNotBlank()) Text(ipa, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SpeakButton(PlaybackSource.word(replacement.lemma), contentDescription = "朗读 ${replacement.lemma}")
            }
            Text("这里是「${replacement.zh}」", style = MaterialTheme.typography.titleMedium)
            if (meaning.isNotBlank()) {
                Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row?.let {
                Text(
                    text = "在这本书里见过 ${it.totalExposures} 次 · 当前${ScaffoldStage.entries.getOrElse(it.stage) { ScaffoldStage.Introduced }.labelZh}阶段",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val target = row ?: return@OutlinedButton
                        app.applicationScope.launch {
                            val id = service.addToVocabulary(target)
                            withContext(Dispatchers.Main) {
                                if (id != null) WordEnrichmentWorker.enqueue(context, id)
                                Toast.makeText(context, if (id != null) "已加入生词本" else "生词本里已经有这个词了", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = row != null,
                ) { Text("加入生词本") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    app.applicationScope.launch {
                        service.excludeHere(bookId, replacement.zh, chapterIndex, replacement.sourceStart)
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                    onDismiss()
                }) { Text("这里不该换") }
                TextButton(onClick = {
                    app.applicationScope.launch {
                        service.setDisabled(bookId, replacement.zh, true)
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                    onDismiss()
                }) { Text("这本书里都别换") }
            }
        }
    }
}

/**
 * 母语阅读面板（设计文档 §5.6、§5.10）：开关、英文量、本书词表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeSheet(book: BookEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val glossary by remember(book.id) { app.bookRepository.observeGlossary(book.id) }.collectAsState(initial = emptyList())

    fun set(enabled: Boolean, dose: Int) {
        app.applicationScope.launch {
            app.bookRepository.setNative(book.id, enabled, dose)
            // 打开时在后台把全书分一遍词，数出每个词跨了多少章，之后挑词优先反复出现的。
            if (enabled) app.nativeBookService.spread(book.id)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("母语阅读", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "把你不熟悉的英文词换进中文里，同一个词全书反复出现",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = book.nativeEnabled, onCheckedChange = { set(it, book.englishDose) })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text("英文量", style = MaterialTheme.typography.titleSmall)
                EnglishDose.entries.forEach { dose ->
                    FilterChip(
                        selected = book.englishDose == dose.ordinal,
                        onClick = { set(book.nativeEnabled, dose.ordinal) },
                        label = { Text("${dose.labelZh} · 每章 ${dose.newWordsPerChapter} 个新词") },
                        enabled = book.nativeEnabled,
                    )
                }
            }
            Text("本书词表 · ${glossary.size} 个词", style = MaterialTheme.typography.titleSmall)
            if (glossary.isEmpty()) {
                Text(
                    text = "打开母语阅读后，读到哪章就从哪章挑词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                items(glossary, key = { it.zh }) { word -> GlossaryRow(book.id, word) }
            }
        }
    }
}

@Composable
private fun GlossaryRow(bookId: Long, word: BookGlossaryEntity) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            InteractiveEnglishText(
                text = "${word.lemma} · ${word.zh}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (word.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOf(
                    posLabelZh(word.pos),
                    "第 ${word.introducedChapter + 1} 章引入",
                    "见过 ${word.totalExposures} 次",
                    "看过中文 ${word.reveals} 次",
                    if (word.disabled) "已停用" else ScaffoldStage.entries.getOrElse(word.stage) { ScaffoldStage.Introduced }.labelZh,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            app.applicationScope.launch {
                val id = app.nativeBookService.addToVocabulary(word)
                withContext(Dispatchers.Main) {
                    if (id != null) WordEnrichmentWorker.enqueue(context, id)
                    Toast.makeText(context, if (id != null) "已加入生词本" else "生词本里已经有这个词了", Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("加入生词本") }
        TextButton(onClick = {
            app.applicationScope.launch { app.nativeBookService.setDisabled(bookId, word.zh, !word.disabled) }
        }) { Text(if (word.disabled) "恢复" else "停用") }
    }
}
