package com.lazydog.english.core.designsystem

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.WordExplanation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全局英文文本交互：快速双击查当前位置的词，快速三击讲解当前位置的句子。
 * 单击回调可选；存在时会等多击窗口结束再触发，避免把双击误当成两次单击。
 */
@Composable
fun InteractiveEnglishText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    highlightWords: Set<String> = emptySet(),
    highlightStyle: SpanStyle? = null,
    onSingleTap: (() -> Unit)? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selectedWord by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSentence by remember { mutableStateOf<String?>(null) }
    var tapCount by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val timeoutMillis = 280L
    val highlightLower = remember(highlightWords) { highlightWords.map { it.lowercase() }.toSet() }
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, highlightLower, primary, highlightStyle) {
        highlightedText(text, highlightLower, highlightStyle ?: SpanStyle(
            color = primary,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
        ))
    }

    Text(
        text = annotated,
        modifier = modifier.pointerInput(text, onSingleTap) {
            detectTapGestures { position ->
                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                tapCount = (tapCount + 1).coerceAtMost(3)
                settleJob?.cancel()
                settleJob = scope.launch {
                    delay(timeoutMillis)
                    when (tapCount) {
                        1 -> onSingleTap?.invoke()
                        2 -> wordAt(text, offset)?.let { selectedWord = it to sentenceAround(text, offset) }
                        else -> selectedSentence = sentenceAround(text, offset).takeIf { it.isNotBlank() }
                    }
                    tapCount = 0
                }
            }
        },
        style = style,
        color = color,
        fontWeight = fontWeight,
        textDecoration = textDecoration,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
    )

    selectedWord?.let { (word, sentence) ->
        GlobalWordSheet(word, sentence) { selectedWord = null }
    }
    selectedSentence?.let { sentence ->
        GlobalSentenceSheet(sentence) { selectedSentence = null }
    }
}

private fun highlightedText(text: String, highlights: Set<String>, highlightStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        if (highlights.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }
        val regex = Regex("[A-Za-z'\\-]+")
        var cursor = 0
        regex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            if (match.value.lowercase() in highlights) {
                withStyle(highlightStyle) {
                    append(match.value)
                }
            } else {
                append(match.value)
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }

internal fun wordAt(text: String, index: Int): String? =
    Regex("[A-Za-z'\\-]+").findAll(text).firstOrNull { index in it.range }?.value

internal fun sentenceAround(text: String, index: Int): String {
    if (text.isEmpty()) return ""
    val safeIndex = index.coerceIn(0, text.lastIndex)
    val ends = charArrayOf('.', '!', '?')
    var start = 0
    for (i in (safeIndex - 1) downTo 0) {
        if (text[i] in ends) {
            start = i + 1
            break
        }
    }
    var end = text.length
    for (i in safeIndex until text.length) {
        if (text[i] in ends) {
            end = i + 1
            break
        }
    }
    return text.substring(start, end).trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalWordSheet(word: String, sentence: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    var inLibrary by remember { mutableStateOf<WordExplanation?>(null) }
    var explanation by remember { mutableStateOf<WordExplanation?>(null) }
    var streamedJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    // 面板关掉就别再念了。
    DisposableEffect(Unit) {
        onDispose { app.speechController.stopSpeaking() }
    }

    LaunchedEffect(word, sentence) {
        val existing = app.knowledgeRepository.vocabulary.first()
            .firstOrNull { it.detail.term.equals(word, ignoreCase = true) }
        if (existing != null) {
            // 已经在库里就直接摊开库里存的那份，不再花一次生成。
            inLibrary = WordExplanation(
                term = existing.detail.term,
                ipa = existing.detail.ipa,
                meaningZh = existing.detail.meaningZh,
                usageNoteZh = "",
                exampleEn = existing.detail.exampleEn,
                exampleZh = existing.detail.exampleZh,
                memoryHintZh = existing.detail.memoryHintZh,
            )
        } else {
            when (val result = app.contentGenerator.explainWord(
                word,
                sentence,
                app.userPreferences.learnerLevelDescription.first(),
                onProgress = { streamedJson = it },
            )) {
                is GenerationResult.Success -> explanation = result.data
                is GenerationResult.Failure -> error = result.reason
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(word, style = MaterialTheme.typography.headlineSmall)
                (explanation ?: inLibrary)?.ipa?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { app.speechController.speakWord(word) } }) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读这个词", tint = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                inLibrary != null -> {
                    val value = inLibrary!!
                    Text(value.meaningZh)
                    WordExample(value.exampleEn, value.exampleZh)
                    MemoryHint(value.memoryHintZh)
                    Text("已经在你的知识库里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                explanation != null -> {
                    val value = explanation!!
                    Text(value.meaningZh)
                    if (value.usageNoteZh.isNotBlank()) {
                        Text(value.usageNoteZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WordExample(value.exampleEn, value.exampleZh)
                    MemoryHint(value.memoryHintZh)
                    Button(
                        onClick = {
                            scope.launch {
                                saved = app.knowledgeRepository.addVocabulary(
                                    term = word,
                                    meaningZh = value.meaningZh,
                                    ipa = value.ipa,
                                    // 例句优先用这个词出现的原句：它才是用户真正读到的语境。
                                    exampleEn = sentence.ifBlank { value.exampleEn },
                                    exampleZh = if (sentence.isBlank()) value.exampleZh else "",
                                    memoryHintZh = value.memoryHintZh,
                                ) != null
                            }
                        },
                        enabled = !saved,
                    ) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
                        Text(if (saved) "已记入，会安排复习" else "放进生词本", Modifier.padding(start = 8.dp))
                    }
                }
                error != null -> Text("查询失败：$error", color = MaterialTheme.colorScheme.error)
                streamedJson.isNotBlank() -> {
                    val meaning = partialJsonStringValue(streamedJson, "meaningZh")
                    val usage = partialJsonStringValue(streamedJson, "usageNoteZh")
                    StreamingLabel("正在生成词汇讲解…")
                    if (meaning.isNotBlank()) Text(meaning)
                    if (usage.isNotBlank()) {
                        Text(usage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WordExample(
                        partialJsonStringValue(streamedJson, "exampleEn"),
                        partialJsonStringValue(streamedJson, "exampleZh"),
                    )
                    MemoryHint(partialJsonStringValue(streamedJson, "memoryHintZh"))
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("AI 正在结合这句话查词…")
                }
            }
            Text("「$sentence」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSentenceSheet(sentence: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    var explanation by remember { mutableStateOf<com.lazydog.english.domain.generation.SentenceExplanation?>(null) }
    var streamedJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    // 面板关掉就别再念了。
    DisposableEffect(Unit) {
        onDispose { app.speechController.stopSpeaking() }
    }

    LaunchedEffect(sentence) {
        saved = app.knowledgeRepository.expressions.first()
            .any { it.detail.term.equals(sentence, ignoreCase = true) }
        when (val result = app.contentGenerator.explainSentence(
            sentence,
            app.userPreferences.learnerLevelDescription.first(),
            onProgress = { streamedJson = it },
        )) {
            is GenerationResult.Success -> explanation = result.data
            is GenerationResult.Failure -> error = result.reason
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(sentence, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { app.speechController.speak(sentence) } }) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读这句话", tint = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                explanation != null -> {
                    Text(explanation!!.translationZh, style = MaterialTheme.typography.bodyLarge)
                    if (explanation!!.explanationZh.isNotBlank()) {
                        Text(explanation!!.explanationZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                app.knowledgeRepository.addExpression(
                                    expressionEn = sentence,
                                    meaningZh = explanation!!.translationZh,
                                )
                                saved = true
                            }
                        },
                        enabled = !saved,
                    ) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
                        Text(if (saved) "已在表达里" else "摘下这句", Modifier.padding(start = 8.dp))
                    }
                }
                error != null -> Text("讲解失败：$error", color = MaterialTheme.colorScheme.error)
                streamedJson.isNotBlank() -> {
                    val translation = partialJsonStringValue(streamedJson, "translationZh")
                    val detail = partialJsonStringValue(streamedJson, "explanationZh")
                    StreamingLabel("正在生成句子讲解…")
                    if (translation.isNotBlank()) Text(translation, style = MaterialTheme.typography.bodyLarge)
                    if (detail.isNotBlank()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("AI 正在翻译并拆解这句话…")
                }
            }
            Text("提示：快速双击查单词，快速三击讲整句。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/** 速查面板里的例句块。英文本身仍然可以双击/三击继续查，空的时候整块不出现。 */
@Composable
private fun WordExample(exampleEn: String, exampleZh: String) {
    if (exampleEn.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InteractiveEnglishText(text = exampleEn, style = MaterialTheme.typography.bodyMedium)
            if (exampleZh.isNotBlank()) {
                Text(exampleZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 速查面板里的记忆方法，和单词卡上的"怎么记"同一套说法。 */
@Composable
private fun MemoryHint(memoryHintZh: String) {
    if (memoryHintZh.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("怎么记", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(memoryHintZh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun StreamingLabel(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** 从尚未闭合的 JSON 字符串字段中提取已到达的文本，供 SSE 增量展示。 */
internal fun partialJsonStringValue(jsonText: String, key: String): String {
    val markerIndex = jsonText.indexOf("\"$key\"")
    if (markerIndex < 0) return ""
    val colonIndex = jsonText.indexOf(':', markerIndex + key.length + 2)
    if (colonIndex < 0) return ""
    val quoteIndex = jsonText.indexOf('"', colonIndex + 1)
    if (quoteIndex < 0) return ""

    val result = StringBuilder()
    var index = quoteIndex + 1
    while (index < jsonText.length) {
        val char = jsonText[index]
        if (char == '"') break
        if (char != '\\') {
            result.append(char)
            index++
            continue
        }
        if (index + 1 >= jsonText.length) break
        when (val escaped = jsonText[index + 1]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                if (index + 5 >= jsonText.length) break
                val code = jsonText.substring(index + 2, index + 6).toIntOrNull(16) ?: break
                result.append(code.toChar())
                index += 4
            }
            else -> result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}
