package com.lazydog.english.feature.books

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.lazydog.english.core.database.ReadingWordEntity
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.books.ProgressiveRules
import com.lazydog.english.domain.books.ProgressiveSettings
import com.lazydog.english.domain.books.ReadingWordState
import com.lazydog.english.domain.books.RenderedReplacement
import com.lazydog.english.domain.books.ReplacementDensity
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import com.lazydog.english.domain.vocabulary.posLabelZh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 点了一处替换（设计文档 §12.2）：看原文和此处释义、听发音，以及「本书不换这个词」「我认识这个意思」
 * 「加入生词本」「这里换得不对」。[onChanged] 在改动之后调，阅读器重新渲染这一章。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val words by remember { service.observeWords() }.collectAsState(initial = emptyList())
    val word = words.firstOrNull { it.senseId == replacement.senseId }
    var meaning by remember { mutableStateOf("") }
    var ipa by remember { mutableStateOf("") }
    LaunchedEffect(replacement.senseId) {
        meaning = service.meaningOf(replacement.lemma, replacement.pos, replacement.senseOrder).orEmpty()
        ipa = withContext(Dispatchers.IO) { app.dictionary.entry(replacement.lemma) }?.ipa.orEmpty()
    }

    fun act(message: String?, block: suspend () -> Unit) {
        app.applicationScope.launch {
            block()
            withContext(Dispatchers.Main) {
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                onChanged()
            }
        }
        onDismiss()
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
            Text("原文「${replacement.zh}」", style = MaterialTheme.typography.titleMedium)
            if (meaning.isNotBlank()) {
                Text(
                    text = "此处释义：${posLabelZh(replacement.pos.wire)} $meaning",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            word?.let {
                Text(
                    text = "${ReadingWordState.fromOrdinal(it.state).labelZh} · 有效复现 ${it.effectiveCount} 次 · 查过 ${it.lookups} 次",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    act("这本书里不再换「${replacement.zh}」") {
                        service.setIgnoredInBook(bookId, replacement.zh, replacement.lemma, replacement.pos, replacement.senseOrder, chapterIndex, true)
                    }
                }) { Text("本书不换这个词") }
                OutlinedButton(onClick = {
                    act("记下了，这个意思不再当新词教") { service.markKnownSense(replacement) }
                }) { Text("我认识这个意思") }
                OutlinedButton(onClick = {
                    app.applicationScope.launch {
                        val id = service.addToVocabulary(replacement.lemma, replacement.pos, replacement.senseOrder, replacement.zh)
                        withContext(Dispatchers.Main) {
                            if (id != null) WordEnrichmentWorker.enqueue(context, id)
                            Toast.makeText(context, if (id != null) "已加入生词本" else "生词本里已经有这个词了", Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                    }
                    onDismiss()
                }) { Text("加入生词本") }
            }
            TextButton(onClick = {
                act("这一处已恢复原文") { service.excludeHere(bookId, chapterIndex, replacement) }
            }) { Text("这里换得不对") }
        }
    }
}

/**
 * 渐进学词面板（设计文档 §12.1、§12.2）：全局密度、额度和学习池，这本书暂停，本书词表。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NativeSheet(book: BookEntity, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val service = app.nativeBookService
    val settings by service.settings.collectAsState(initial = ProgressiveSettings())
    val glossary by remember(book.id) { app.bookRepository.observeGlossary(book.id) }.collectAsState(initial = emptyList())
    val words by remember { service.observeWords() }.collectAsState(initial = emptyList())
    val bySense = remember(words) { words.associateBy { it.senseId } }
    val today = remember(words) { words.count { it.introducedDay == java.time.LocalDate.now().toEpochDay() } }
    val active = remember(words) { words.count { it.state == ReadingWordState.Active.ordinal } }

    fun save(next: ProgressiveSettings) {
        app.applicationScope.launch {
            service.saveSettings(next)
            withContext(Dispatchers.Main) { onChanged() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text("渐进学词", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "照常读故事，偶尔把一个词换成英文；同一个意思在这本书、别的书里按间隔再出现。点英文看原文和释义。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingRow("替换密度") {
                ReplacementDensity.entries.forEach { density ->
                    FilterChip(
                        selected = settings.replacementDensity == density,
                        onClick = { save(settings.copy(density = density.ordinal)) },
                        label = { Text(density.labelZh) },
                    )
                }
            }
            SettingRow("每天新词") {
                listOf(2, 4, 6, ProgressiveRules.MAX_DAILY_NEW).forEach { n ->
                    FilterChip(
                        selected = settings.dailyNewCapped == n,
                        onClick = { save(settings.copy(dailyNew = n)) },
                        label = { Text("$n") },
                        enabled = settings.enabled,
                    )
                }
            }
            SettingRow("同时在学") {
                listOf(10, 15, 20, ProgressiveRules.MAX_POOL).forEach { n ->
                    FilterChip(
                        selected = settings.poolCapped == n,
                        onClick = { save(settings.copy(poolSize = n)) },
                        label = { Text("$n") },
                        enabled = settings.enabled,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("这本书暂停", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = book.progressivePaused,
                    onCheckedChange = { paused ->
                        app.applicationScope.launch {
                            service.setPaused(book.id, paused)
                            withContext(Dispatchers.Main) { onChanged() }
                        }
                    },
                    enabled = settings.enabled,
                )
            }
            Text(
                text = "今天新学 $today / ${settings.dailyNewCapped} · 学习池 $active / ${settings.poolCapped}（书和网页共用）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Text("本书词表 · ${glossary.size} 个词", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (glossary.isEmpty()) {
                Text(
                    text = if (settings.enabled) "读到合适的词时会出现在这里。" else "打开替换密度后开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                items(glossary, key = { it.zh }) { row ->
                    val pos = PartOfSpeech.parse(row.pos) ?: PartOfSpeech.Noun
                    GlossaryRow(book.id, row, bySense[ProgressiveRules.senseId(row.lemma, pos, row.senseOrder)], onChanged)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun GlossaryRow(bookId: Long, row: BookGlossaryEntity, word: ReadingWordEntity?, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val service = app.nativeBookService
    val pos = PartOfSpeech.parse(row.pos) ?: PartOfSpeech.Noun
    val state = word?.let { ReadingWordState.fromOrdinal(it.state) }
    val status = when {
        row.disabled -> "本书不换"
        state == null -> "还没学"
        else -> state.labelZh
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            InteractiveEnglishText(
                text = "${row.lemma} · ${row.zh}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.disabled || state != ReadingWordState.Active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOfNotNull(
                    posLabelZh(row.pos),
                    status,
                    word?.let { "有效复现 ${it.effectiveCount} 次" },
                    word?.let { "查过 ${it.lookups} 次" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state == ReadingWordState.Paused || state == ReadingWordState.KnownSelf) {
            TextButton(onClick = {
                app.applicationScope.launch {
                    service.resume(word!!.senseId)
                    withContext(Dispatchers.Main) { onChanged() }
                }
            }) { Text("重新学") }
        }
        TextButton(onClick = {
            app.applicationScope.launch {
                service.setIgnoredInBook(bookId, row.zh, row.lemma, pos, row.senseOrder, row.introducedChapter, !row.disabled)
                withContext(Dispatchers.Main) { onChanged() }
            }
        }) { Text(if (row.disabled) "恢复" else "本书不换") }
    }
}
