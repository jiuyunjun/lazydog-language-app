package com.lazydog.english.core.designsystem

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.WordExplanation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 多击窗口：这段时间内再点一下就升级成双击/三击。 */
private const val MULTI_TAP_WINDOW_MILLIS = 280L

/**
 * 单击/双击/三击这一整套的状态：判定、朗读、以及要弹哪个面板。
 *
 * 抽出来是因为有两个入口共用它——[InteractiveEnglishText] 把手势装在文字上，
 * [InteractiveEnglishBlock] 装在整块上。判定逻辑只该有一份。
 */
@Stable
private class EnglishTapState(
    val text: String,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var word by mutableStateOf<Pair<String, String>?>(null)
    var sentence by mutableStateOf<String?>(null)

    private var tapCount = 0
    private var settleJob: Job? = null

    fun speak() {
        scope.launch {
            (context.applicationContext as LazyDogApplication).speechController.speak(text)
        }
    }

    /**
     * 收到一次点击，[offset] 是点到的文本下标。
     *
     * 单击要等窗口关掉才执行：不等的话双击会先触发一次单击，查词之前先念一遍。
     */
    fun onTap(offset: Int, speakOnSingleTap: Boolean, onSingleTap: (() -> Unit)?) {
        tapCount = (tapCount + 1).coerceAtMost(3)
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(MULTI_TAP_WINDOW_MILLIS)
            when (tapCount) {
                1 -> if (onSingleTap != null) onSingleTap() else if (speakOnSingleTap) speak()
                2 -> wordAt(text, offset)?.let { word = it to sentenceAround(text, offset) }
                else -> sentence = sentenceAround(text, offset).takeIf { it.isNotBlank() }
            }
            tapCount = 0
        }
    }
}

@Composable
private fun rememberEnglishTapState(text: String): EnglishTapState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(text, scope, context) { EnglishTapState(text, scope, context) }
}

/** 查词和讲句的面板。两个入口都要挂，所以单独放一份。 */
@Composable
private fun EnglishSheets(state: EnglishTapState) {
    state.word?.let { (word, sentence) ->
        GlobalWordSheet(word, sentence) { state.word = null }
    }
    state.sentence?.let { sentence ->
        GlobalSentenceSheet(sentence) { state.sentence = null }
    }
}

/**
 * 全局英文文本交互：快速双击查当前位置的词，快速三击讲解当前位置的句子。
 * 单击回调可选；存在时会等多击窗口结束再触发，避免把双击误当成两次单击。
 *
 * [speakOnSingleTap] 打开后单击就朗读这段文字。
 *
 * 按下不做任何点亮。这一版试过按下高亮那段文字/那一块，实际用起来是满屏乱闪——
 * 界面上到处都是可点的英文，随手一碰就亮一块，比"点了没动静"更烦。
 *
 * 点击范围就是文字本身，这是默认形态。整块可点的 [InteractiveEnglishBlock] 是特例，
 * 目前只有词组小块用它。
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
    speakOnSingleTap: Boolean = false,
    onSingleTap: (() -> Unit)? = null,
) {
    val state = rememberEnglishTapState(text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
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
        modifier = modifier
            .pointerInput(text, onSingleTap, speakOnSingleTap) {
                detectTapGestures { position ->
                    val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    state.onTap(offset, speakOnSingleTap, onSingleTap)
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

    EnglishSheets(state)
}

/**
 * 一整块可点的英文。
 *
 * 和 [InteractiveEnglishText] 的区别只有一个：手势和点亮都作用在**整块**上。
 * 一行英文加一行中文翻译加一行提示，眼里它就是一张卡片，只有那行英文的字面能点
 * 等于让人去瞄准——点空了还没有任何反应，和坏了没区别。
 *
 * **只给词组小块（[com.lazydog.english.feature.vocabulary.CollocationChip]）用**。
 * 别处的英文仍然是文字级的点击范围：整块可点会把块里其它东西的点击也吃掉，
 * 那些地方的卡片各有各的主用途，不该被朗读顶掉。
 *
 * 双击三击仍然要知道点的是哪个词，所以按下的位置会换算回英文那一行的坐标系
 * （[LayoutCoordinates.localPositionOf]）；点在中文那行上就落到最近的词，
 * 这比"什么也不发生"强。
 *
 * [header] 放在英文上面（标签、喇叭之类），[trailing] 和英文同一行，[below] 在英文下面。
 */
@Composable
fun InteractiveEnglishBlock(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    /** 撑满一行。词组那种并排摆的小块要关掉，否则一行只放得下一个。 */
    fillWidth: Boolean = true,
    showHint: Boolean = true,
    speakOnSingleTap: Boolean = true,
    onSingleTap: (() -> Unit)? = null,
    header: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val state = rememberEnglishTapState(text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var blockCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Surface(
        // 底色恒定。按下高亮整块实测就是满屏乱闪，比没有反馈更烦。
        color = container,
        border = border,
        shape = shape,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .onGloballyPositioned { blockCoords = it }
            .pointerInput(text, speakOnSingleTap, onSingleTap) {
                detectTapGestures { position ->
                    val inText = textCoords?.let { t ->
                        blockCoords?.let { b -> t.localPositionOf(b, position) }
                    } ?: position
                    val offset = layout?.getOffsetForPosition(inText) ?: return@detectTapGestures
                    state.onTap(offset, speakOnSingleTap, onSingleTap)
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (header != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = header,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = text,
                    style = style,
                    color = color,
                    // 不撑满一行时不能用 weight：宽度是无界的，weight 分不出东西来。
                    modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                        .onGloballyPositioned { textCoords = it },
                    onTextLayout = { layout = it },
                )
                trailing?.invoke()
            }
            below?.invoke(this)
            if (showHint) InteractiveTextHint(speakOnSingleTap = speakOnSingleTap)
        }
    }

    EnglishSheets(state)
}

/**
 * "这段英文可以点"的说明。
 *
 * 双击和三击是没有任何视觉痕迹的交互——不写这一行，界面上就没有任何东西告诉用户
 * 这段英文和一张图片有什么区别，功能等于不存在。凡是摆了 [InteractiveEnglishText]
 * 又不止一两个词的地方（例句、短文、对白），都该在旁边带上它。
 */
@Composable
fun InteractiveTextHint(modifier: Modifier = Modifier, speakOnSingleTap: Boolean = false) {
    Text(
        text = if (speakOnSingleTap) "单击朗读 · 双击查词 · 三击讲句" else "快速双击查词 · 快速三击讲句",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
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
    /** 词条已经在库里，但这次查出来的是它的另一个词义。 */
    var otherSense by remember { mutableStateOf<WordExplanation?>(null) }
    var streamedJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    // 面板关掉就别再念了。
    DisposableEffect(Unit) {
        onDispose { app.speechController.stopSpeaking() }
    }

    /**
     * 查库要查两次，因为原型得等 AI 回来才知道。
     *
     * 第一次按用户点到的形态查——大多数时候他点的就是原型，命中就直接摊开库里那份，
     * 一次生成都不用花。没命中才讲解；讲解回来拿到原型再查一次：库里有 `go`、
     * 用户点的是 `went`，只查第一次的话会白花一次生成，还会再存一条重复的词条。
     */
    suspend fun lookup(form: String): WordExplanation? =
        // 按词形找：库里存的是原型，不规则变形也记着，所以点 went 能命中 go
        // （单词记忆DESIGN.md §4.1「Word Form != 独立生词」），一次生成都不用花。
        app.knowledgeRepository.findByWordForm(form).firstOrNull()?.let {
            WordExplanation(
                term = it.detail.term,
                lemma = it.detail.term,
                pos = it.detail.pos,
                ipa = it.detail.ipa,
                meaningZh = it.detail.meaningZh,
                usageNoteZh = "",
                exampleEn = it.detail.exampleEn,
                exampleZh = it.detail.exampleZh,
                memoryHintZh = it.detail.memoryHintZh,
            )
        }

    LaunchedEffect(word, sentence) {
        val existing = lookup(word)
        if (existing != null) {
            inLibrary = existing
        } else {
            when (val result = app.contentGenerator.explainWord(
                word,
                sentence,
                app.userPreferences.learnerLevelDescription.first(),
                onProgress = { streamedJson = it },
            )) {
                is GenerationResult.Success -> {
                    val fresh = result.data
                    // 还原出来的原型可能早就在库里了（点 went，库里有 go）。
                    // 命中的话把用户点到的形态带回去，那行"你点的是 went"才显示得出来。
                    val known = if (fresh.inflected) lookup(fresh.headword)?.copy(term = word) else null
                    if (known != null) {
                        inLibrary = known
                        // 库里那条是同一个词条的另一个词义时，这一条仍然值得单独存
                        // （run 的"跑"和"经营"各自复习，单词记忆DESIGN.md §5）。
                        otherSense = fresh.takeIf {
                            it.meaningZh.isNotBlank() && it.meaningZh != known.meaningZh
                        }
                    } else {
                        explanation = fresh
                    }
                }
                is GenerationResult.Failure -> error = result.reason
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = explanation ?: inLibrary
            // 标题是词条（`go`），不是他点到的形态（`went`）——存进生词本、以后复习的都是词条。
            // 但朗读仍读他点的那个形态：他要听的是这句话里这个词怎么念。
            val headword = shown?.headword ?: word
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(headword, style = MaterialTheme.typography.headlineSmall)
                shown?.ipa?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { app.speechController.speakWord(word) } }) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读这个词", tint = MaterialTheme.colorScheme.primary)
                }
            }
            // 换了个词形就得说清楚，不然用户会以为自己点错了行、或者存进去的词丢了。
            if (shown?.inflected == true) {
                Text(
                    text = "你点的是 $word，它是 $headword 的变形",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                inLibrary != null -> {
                    val value = inLibrary!!
                    InteractiveEnglishText(value.meaningZh)
                    WordExample(value.exampleEn, value.exampleZh)
                    MemoryHint(value.memoryHintZh)
                    Text("已经在你的知识库里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 同一个词的另一个意思不是重复：把两个词义挤进一条记录，
                    // 就没法知道用户到底会了哪个（单词记忆DESIGN.md Principle 3）。
                    val another = otherSense
                    if (another != null) {
                        Text(
                            text = "这句里它是另一个意思：${another.meaningZh}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    saved = app.knowledgeRepository.addVocabulary(
                                        term = another.headword,
                                        meaningZh = another.meaningZh,
                                        ipa = another.ipa,
                                        exampleEn = sentence.ifBlank { another.exampleEn },
                                        exampleZh = if (sentence.isBlank()) another.exampleZh else "",
                                        pos = another.pos,
                                        memoryHintZh = another.memoryHintZh,
                                        seenAs = if (sentence.isBlank()) "" else word,
                                        forms = another.forms,
                                        asNewSense = true,
                                    ) != null
                                }
                            },
                            enabled = !saved,
                        ) {
                            Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
                            Text(
                                text = if (saved) "已记入，会安排复习" else "这个意思也记下来",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                explanation != null -> {
                    val value = explanation!!
                    InteractiveEnglishText(value.meaningZh)
                    if (value.usageNoteZh.isNotBlank()) {
                        InteractiveEnglishText(value.usageNoteZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WordExample(value.exampleEn, value.exampleZh)
                    MemoryHint(value.memoryHintZh)
                    Button(
                        onClick = {
                            scope.launch {
                                saved = app.knowledgeRepository.addVocabulary(
                                    // 存词条，不存句子里那个形态：went 和 go 是同一个词，
                                    // 分开存就会各自走一遍拼写阶段、各攒一份画像。
                                    term = value.headword,
                                    meaningZh = value.meaningZh,
                                    ipa = value.ipa,
                                    // 例句优先用这个词出现的原句：它才是用户真正读到的语境。
                                    exampleEn = sentence.ifBlank { value.exampleEn },
                                    exampleZh = if (sentence.isBlank()) value.exampleZh else "",
                                    pos = value.pos,
                                    memoryHintZh = value.memoryHintZh,
                                    // 原句里出现的是变形，语境默写要挖的空是它而不是词条。
                                    seenAs = if (sentence.isBlank()) "" else word,
                                    forms = value.forms,
                                ) != null
                            }
                        },
                        enabled = !saved,
                    ) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
                        // 存的是词条，按钮就得报词条的名字：用户点 went 存完，
                        // 回记录里只找得到 go，不明说他会以为丢了。
                        Text(
                            text = when {
                                saved -> "已记入，会安排复习"
                                value.inflected -> "把 ${value.headword} 放进生词本"
                                else -> "放进生词本"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
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
                    InteractiveEnglishText(explanation!!.translationZh, style = MaterialTheme.typography.bodyLarge)
                    if (explanation!!.explanationZh.isNotBlank()) {
                        InteractiveEnglishText(explanation!!.explanationZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
