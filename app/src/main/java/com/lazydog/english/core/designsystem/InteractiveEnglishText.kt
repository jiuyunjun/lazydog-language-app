package com.lazydog.english.core.designsystem

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    var inLibrary by remember { mutableStateOf<String?>(null) }
    var libraryIpa by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf<WordExplanation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(word, sentence) {
        val existing = app.knowledgeRepository.vocabulary.first()
            .firstOrNull { it.detail.term.equals(word, ignoreCase = true) }
        if (existing != null) {
            inLibrary = existing.detail.meaningZh
            libraryIpa = existing.detail.ipa
        } else {
            when (val result = app.contentGenerator.explainWord(
                word,
                sentence,
                app.userPreferences.learnerLevelDescription.first(),
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
                (explanation?.ipa?.takeIf { it.isNotBlank() } ?: libraryIpa.takeIf { it.isNotBlank() })?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { app.speechController.speak(word) } }) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读这个词", tint = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                inLibrary != null -> {
                    Text(inLibrary!!)
                    Text("已经在你的知识库里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                explanation != null -> {
                    val value = explanation!!
                    Text(value.meaningZh)
                    if (value.usageNoteZh.isNotBlank()) {
                        Text(value.usageNoteZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                saved = app.knowledgeRepository.addVocabulary(
                                    term = word,
                                    meaningZh = value.meaningZh,
                                    ipa = value.ipa,
                                    exampleEn = sentence,
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
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sentence) {
        when (val result = app.contentGenerator.explainSentence(
            sentence,
            app.userPreferences.learnerLevelDescription.first(),
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
                }
                error != null -> Text("讲解失败：$error", color = MaterialTheme.colorScheme.error)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("AI 正在翻译并拆解这句话…")
                }
            }
            Text("提示：快速双击查单词，快速三击讲整句。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
