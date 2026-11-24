package com.lazydog.english.feature.books

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.database.BookChapterEntity
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.feature.ask.AskTopBarAction
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.BookLocator
import com.lazydog.english.domain.books.ChapterText
import com.lazydog.english.domain.books.PageMode
import com.lazydog.english.domain.books.ReaderSettings
import com.lazydog.english.domain.books.RenderedChapter
import com.lazydog.english.domain.books.RenderedReplacement
import com.lazydog.english.domain.books.ScaffoldStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 载入好的一章。[text] 是显示用的文本（母语阅读时是替换后的）；阅读位置一律记原文偏移，
 * 用 [rendered] 换算（设计文档 §5.9）。不开母语阅读时两者一样。
 */
private data class LoadedChapter(val index: Int, val title: String, val rendered: RenderedChapter) {
    val text: String get() = rendered.text

    /** 原文偏移 → 显示偏移；「从上一章末尾进来」的 Int.MAX_VALUE 落到末尾。 */
    fun displayOffset(source: Int): Int = if (source == Int.MAX_VALUE) text.length else rendered.toRendered(source)
}

/** 点在左边这么多以内翻上一页，右边这么多以内翻下一页，中间呼出菜单。 */
private const val TAP_EDGE = 0.3f

/** 进度停稳这么久才写库：翻页翻得快时不必每页都写。 */
private const val SAVE_DELAY_MILLIS = 600L

/**
 * 阅读器（D-098 R2，`读书与网络阅读DESIGN.md` §4）。
 *
 * 正文用交互文本：双击查词、三击讲句、摇一摇提问都照常能用。单击按位置分：左三成上一页、
 * 右三成下一页、中间呼出菜单——交互文本本来就会等多击窗口过去才报单击，所以不会和双击查词打架。
 */
@Composable
fun ReaderScreen(bookId: Long, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val repo = app.bookRepository
    val scope = rememberCoroutineScope()

    val book by repo.observeBook(bookId).collectAsState(initial = null)
    val settings by app.userPreferences.readerSettings.collectAsState(initial = null)
    var chapters by remember { mutableStateOf<List<BookChapterEntity>>(emptyList()) }
    var locator by remember { mutableStateOf<BookLocator?>(null) }
    var loaded by remember { mutableStateOf<LoadedChapter?>(null) }
    var missing by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    /** 当前屏上的文字：加书签的摘录、摇一摇提问的上下文。 */
    var visibleText by remember { mutableStateOf("") }
    /** 点到的那处替换（母语阅读）。 */
    var tapped by remember { mutableStateOf<RenderedReplacement?>(null) }
    /** 纠错后重渲染这一章。 */
    var reloadTick by remember { mutableIntStateOf(0) }
    /** 这次打开已经记过曝光的替换（章节:原文偏移），翻回来不重复记。 */
    val exposed = remember { HashSet<String>() }
    val nativeService = app.nativeBookService

    LaunchedEffect(bookId) {
        chapters = repo.chapters(bookId)
        val saved = repo.book(bookId) ?: return@LaunchedEffect
        missing = !repo.hasContent(bookId)
        locator = BookLocator(saved.chapterIndex.coerceIn(0, (saved.chapterCount - 1).coerceAtLeast(0)), saved.chapterOffset)
    }

    val language = BookLanguage.fromWire(book?.language.orEmpty())
    val chapterIndex = locator?.chapter
    LaunchedEffect(chapterIndex, chapters, language, book?.nativeEnabled, book?.englishDose, reloadTick) {
        val index = chapterIndex ?: return@LaunchedEffect
        val chapter = chapters.getOrNull(index) ?: return@LaunchedEffect
        val paragraphs = repo.chapterParagraphs(bookId, index)
        if (paragraphs == null) {
            missing = true
            return@LaunchedEffect
        }
        val source = ChapterText.build(chapter.title, paragraphs, language)
        // 母语阅读：本地分词、挑词、替换（设计文档 §5）；没开或不是中文书就是原文。
        val rendered = repo.book(bookId)?.let { nativeService.render(it, index, source, chapter.title.length) }
            ?: RenderedChapter.plain(source)
        loaded = LoadedChapter(index, chapter.title, rendered)
    }

    // 分词词典和反查表二十来 MB，离开阅读器就放掉。
    DisposableEffect(Unit) { onDispose { nativeService.release() } }

    // 进度：停稳一会儿再写，离开页面时 DisposableEffect 再补一次。
    LaunchedEffect(locator) {
        val at = locator ?: return@LaunchedEffect
        delay(SAVE_DELAY_MILLIS)
        repo.saveProgress(bookId, at, ChapterText.progress(chapters.map { it.charCount }, at))
    }

    // 阅读时长：前台才算。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookId) {
        var resumedAt = System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_PAUSE -> {
                    val spent = System.currentTimeMillis() - resumedAt
                    scope.launch { repo.addReadingTime(bookId, spent.coerceAtMost(MAX_SESSION_MILLIS)) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val spent = System.currentTimeMillis() - resumedAt
            val at = locator
            app.applicationScope.launch {
                repo.addReadingTime(bookId, spent.coerceAtMost(MAX_SESSION_MILLIS))
                if (at != null) repo.saveProgress(bookId, at, ChapterText.progress(chapters.map { it.charCount }, at))
            }
        }
    }

    ProvideAskContext(
        book?.let { b ->
            AskContext(
                kind = AskContextKind.Reading,
                title = b.title,
                details = listOf(
                    AskDetail("书名", b.title),
                    AskDetail("章节", loaded?.title.orEmpty()),
                    AskDetail("当前这一页", visibleText),
                ),
                suggestions = listOf("这一页在讲什么？", "有哪句话结构比较难？", "挑两个值得记的表达"),
            )
        },
        unavailableReason = "书还没打开",
    )

    val currentSettings = settings ?: ReaderSettings()
    val colors = currentSettings.theme.colors()
    val focus = remember { FocusRequester() }
    /** 翻页由当前模式的视图注册进来，音量键和菜单按钮都走它。 */
    var turnPage by remember { mutableStateOf<(Int) -> Unit>({}) }

    fun goToChapter(index: Int, offset: Int = 0) {
        if (index in chapters.indices) locator = BookLocator(index, offset)
    }

    /** 母语阅读：这些替换上了屏，各记一次曝光（每次打开每处只记一次）。 */
    fun recordExposures(chapterIndex: Int, replacements: List<RenderedReplacement>) {
        val fresh = replacements.filter { exposed.add("$chapterIndex:${it.sourceStart}") }
        if (fresh.isEmpty()) return
        app.applicationScope.launch { nativeService.recordExposures(bookId, fresh.map { it.zh }) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // 音量键翻页（只在左右翻页、菜单收起时）。按下和抬起都吃掉，不然系统会去调音量。
                val direction = when (event.key) {
                    Key.VolumeDown -> 1
                    Key.VolumeUp -> -1
                    else -> return@onPreviewKeyEvent false
                }
                if (currentSettings.pageMode != PageMode.Paged || menuVisible) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) turnPage(direction)
                true
            },
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val chapter = loaded
        val at = locator
        when {
            missing -> MissingContent(onExit)
            settings == null || chapter == null || at == null || chapter.index != at.chapter -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            currentSettings.pageMode == PageMode.Paged -> PagedChapter(
                chapter = chapter,
                chapterCount = chapters.size,
                offset = at.offset,
                settings = currentSettings,
                onLocate = { locator = it },
                onVisibleText = { visibleText = it },
                onCenterTap = { menuVisible = !menuVisible },
                registerTurn = { turnPage = it },
                onReplacementTap = { tapped = it },
                onExposed = { replacements -> recordExposures(chapter.index, replacements) },
            )
            else -> ScrollChapter(
                chapter = chapter,
                hasNext = chapter.index < chapters.lastIndex,
                offset = at.offset,
                settings = currentSettings,
                onLocate = { locator = it },
                onVisibleText = { visibleText = it },
                onTap = { menuVisible = !menuVisible },
                onNextChapter = { goToChapter(chapter.index + 1) },
                onReplacementTap = { tapped = it },
                onExposed = { replacements -> recordExposures(chapter.index, replacements) },
            )
        }

        ReaderMenu(
            visible = menuVisible,
            title = book?.title.orEmpty(),
            chapterIndex = locator?.chapter ?: 0,
            chapterCount = chapters.size,
            chapterTitle = loaded?.title.orEmpty(),
            onBack = onExit,
            onAddBookmark = {
                val here = locator ?: return@ReaderMenu
                scope.launch {
                    repo.addBookmark(bookId, here, visibleText.trim())
                    Toast.makeText(context, "已加书签", Toast.LENGTH_SHORT).show()
                }
            },
            onChapter = { goToChapter(it) },
            onOpenSheet = { sheet = it },
            showNative = book?.let { nativeService.supports(it) } == true,
            // 没有摇一摇传感器的手机靠这个按钮提问。
            askAction = { AskTopBarAction() },
        )
    }

    when (sheet) {
        ReaderSheet.Contents -> ContentsSheet(
            chapters = chapters,
            current = locator?.chapter ?: 0,
            onPick = {
                sheet = null
                menuVisible = false
                goToChapter(it)
            },
            onDismiss = { sheet = null },
        )
        ReaderSheet.Bookmarks -> BookmarksSheet(
            bookId = bookId,
            chapters = chapters,
            onPick = { bookmark ->
                sheet = null
                menuVisible = false
                goToChapter(bookmark.chapterIndex, bookmark.chapterOffset)
            },
            onDismiss = { sheet = null },
        )
        ReaderSheet.Settings -> SettingsSheet(
            settings = currentSettings,
            onChange = { changed -> scope.launch { app.userPreferences.saveReaderSettings(changed) } },
            onDismiss = { sheet = null },
        )
        ReaderSheet.Native -> book?.let { NativeSheet(book = it, onDismiss = { sheet = null }) }
        null -> Unit
    }

    tapped?.let { replacement ->
        // 点开就看到了中文：提示、独立阶段算一次"没认出来"，退回上一阶段（§5.7）。引入阶段本来就写着中文，不算。
        LaunchedEffect(replacement) {
            if (replacement.stage != ScaffoldStage.Introduced) {
                app.applicationScope.launch { nativeService.recordReveal(bookId, replacement.zh) }
            }
        }
        ReplacementSheet(
            bookId = bookId,
            chapterIndex = loaded?.index ?: 0,
            replacement = replacement,
            onChanged = { reloadTick++ },
            onDismiss = { tapped = null },
        )
    }
}

/** 一次阅读最多记这么久：开着阅读器睡着了，不该给他记十个小时。 */
private const val MAX_SESSION_MILLIS = 3 * 60 * 60 * 1000L

/**
 * 左右翻页的一章。前后各多一页"哨兵"：滑到哨兵页就切到相邻章节，
 * 这样章与章之间也能连续地滑过去，而不是滑到章末就停住。
 */
@Composable
private fun PagedChapter(
    chapter: LoadedChapter,
    chapterCount: Int,
    offset: Int,
    settings: ReaderSettings,
    onLocate: (BookLocator) -> Unit,
    onVisibleText: (String) -> Unit,
    onCenterTap: () -> Unit,
    registerTurn: ((Int) -> Unit) -> Unit,
    onReplacementTap: (RenderedReplacement) -> Unit,
    onExposed: (List<RenderedReplacement>) -> Unit,
) {
    val colors = settings.theme.colors()
    val style = readerStyle(settings, colors.text)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val annotated = remember(chapter, settings.theme) {
        nativeAnnotated(chapter.text, ChapterText.titleLength(chapter.title), chapter.rendered.replacements, settings.theme)
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val horizontal = settings.marginDp.dp
        val top = 28.dp
        val bottom = 36.dp
        val widthPx = with(density) { (maxWidth - horizontal * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - top - bottom).roundToPx() }
        val pages = remember(annotated, style, widthPx, heightPx) { paginate(measurer, annotated, style, widthPx, heightPx) }
        val hasPrev = chapter.index > 0
        val hasNext = chapter.index < chapterCount - 1
        val lead = if (hasPrev) 1 else 0

        key(chapter.index, pages) {
            val pagerState = rememberPagerState(initialPage = lead + pageIndexFor(pages, chapter.displayOffset(offset))) {
                pages.size + lead + (if (hasNext) 1 else 0)
            }
            /** 手指按下的横向位置（0..1），单击时据此决定翻页还是呼出菜单。 */
            var downFraction by remember { mutableFloatStateOf(0.5f) }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    when {
                        hasPrev && page == 0 -> onLocate(BookLocator(chapter.index - 1, Int.MAX_VALUE))
                        hasNext && page == lead + pages.size -> onLocate(BookLocator(chapter.index + 1, 0))
                        else -> {
                            val current = pages[(page - lead).coerceIn(0, pages.lastIndex)]
                            // 位置记原文偏移：替换和支架会改变显示文本的长度（设计文档 §5.9）。
                            onLocate(BookLocator(chapter.index, chapter.rendered.toSource(current.start)))
                            onVisibleText(chapter.text.substring(current.start, current.end))
                            onExposed(chapter.rendered.replacements.filter { it.start >= current.start && it.start < current.end })
                        }
                    }
                }
            }
            LaunchedEffect(pagerState) {
                registerTurn { direction ->
                    val target = (pagerState.currentPage + direction).coerceIn(0, pagerState.pageCount - 1)
                    scope.launch { pagerState.animateScrollToPage(target) }
                }
            }
            fun onTap() {
                when {
                    downFraction < TAP_EDGE -> scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                    downFraction > 1 - TAP_EDGE -> scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1))
                    }
                    else -> onCenterTap()
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 只看不吃：记下按下的位置，手势照常交给文字和翻页。
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let {
                                    if (size.width > 0) downFraction = it.position.x / size.width
                                }
                            }
                        }
                    },
            ) { index ->
                val pageIndex = index - lead
                if (pageIndex < 0 || pageIndex > pages.lastIndex) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (pageIndex < 0) "上一章" else "下一章",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondary,
                        )
                    }
                } else {
                    val page = pages[pageIndex]
                    // 页尾的换行不画：多出一行空行会把下一页的版面挤歪。
                    val end = if (page.end > page.start && chapter.text[page.end - 1] == '\n') page.end - 1 else page.end
                    val slice = annotated.subSequence(page.start, end)
                    Column(Modifier.fillMaxSize().padding(horizontal = horizontal)) {
                        Box(Modifier.fillMaxWidth().height(top), contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        InteractiveEnglishText(
                            text = slice.text,
                            annotations = slice,
                            style = style,
                            color = colors.text,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            // 点到替换上的英文就打开它的面板，别处按位置翻页或呼出菜单。
                            onSingleTapAt = { at ->
                                val hit = chapter.rendered.replacementAt(page.start + at)
                                if (hit != null) onReplacementTap(hit) else onTap()
                            },
                            onLongPressAt = { at -> chapter.rendered.replacementAt(page.start + at)?.let(onReplacementTap) },
                        )
                        Box(Modifier.fillMaxWidth().height(bottom), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                text = "${pageIndex + 1} / ${pages.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 上下滚动的一章：一行一段，章末一个「下一章」。 */
@Composable
private fun ScrollChapter(
    chapter: LoadedChapter,
    hasNext: Boolean,
    offset: Int,
    settings: ReaderSettings,
    onLocate: (BookLocator) -> Unit,
    onVisibleText: (String) -> Unit,
    onTap: () -> Unit,
    onNextChapter: () -> Unit,
    onReplacementTap: (RenderedReplacement) -> Unit,
    onExposed: (List<RenderedReplacement>) -> Unit,
) {
    val colors = settings.theme.colors()
    val style = readerStyle(settings, colors.text)
    val lines = remember(chapter) { chapter.text.split('\n') }
    val starts = remember(lines) { lines.runningFold(0) { acc, line -> acc + line.length + 1 }.dropLast(1) }
    val annotated = remember(chapter, settings.theme) {
        nativeAnnotated(chapter.text, ChapterText.titleLength(chapter.title), chapter.rendered.replacements, settings.theme)
    }
    key(chapter.index) {
        val displayOffset = chapter.displayOffset(offset)
        val initial = starts.indexOfLast { it <= displayOffset }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                val line = index.coerceIn(0, lines.lastIndex)
                onLocate(BookLocator(chapter.index, chapter.rendered.toSource(starts[line])))
                onVisibleText(lines.drop(line).take(3).joinToString("\n"))
                val from = starts[line]
                val to = starts.getOrElse(line + 3) { chapter.text.length }
                onExposed(chapter.rendered.replacements.filter { it.start >= from && it.start < to })
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = settings.marginDp.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val start = starts[index]
                InteractiveEnglishText(
                    text = line,
                    annotations = annotated.subSequence(start, start + line.length),
                    style = style,
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth(),
                    onSingleTapAt = { at ->
                        val hit = chapter.rendered.replacementAt(start + at)
                        if (hit != null) onReplacementTap(hit) else onTap()
                    },
                    onLongPressAt = { at -> chapter.rendered.replacementAt(start + at)?.let(onReplacementTap) },
                )
            }
            if (hasNext) {
                item {
                    OutlinedButton(onClick = onNextChapter, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text("下一章")
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingContent(onExit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "这本书的正文不在这台手机上。恢复备份只带回了书架和进度，正文要重新导入或下载。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onExit) { Text("回书架") }
    }
}

internal enum class ReaderSheet { Contents, Bookmarks, Settings, Native }

/**
 * 菜单进出的节奏。默认的"展开 + 淡入"走的是偏软的弹簧，从点下去到菜单完全出来要大半秒，
 * 读书时呼出菜单是很频繁的动作，拖沓很明显。改成短促的滑入加淡入：
 * 进场 160ms、标准减速曲线；退场再快一点（120ms、加速曲线），让开得干脆。
 */
private fun <T> menuEnterSpec() = tween<T>(durationMillis = 160, easing = LinearOutSlowInEasing)

private fun <T> menuExitSpec() = tween<T>(durationMillis = 120, easing = FastOutLinearInEasing)

/** 呼出的菜单：上面返回、书名、加书签；下面章节进度和目录、书签、设置。 */
@Composable
private fun ReaderMenu(
    visible: Boolean,
    title: String,
    chapterIndex: Int,
    chapterCount: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onAddBookmark: () -> Unit,
    onChapter: (Int) -> Unit,
    onOpenSheet: (ReaderSheet) -> Unit,
    /** 中文书才有「母语」按钮。 */
    showNative: Boolean,
    /** 提问按钮：由页面传进来，页面级的提问声明要在页面函数里（tools/check_ui_capabilities.py）。 */
    askAction: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(menuEnterSpec()) { -it } + fadeIn(menuEnterSpec()),
            exit = slideOutVertically(menuExitSpec()) { -it } + fadeOut(menuExitSpec()),
        ) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "回书架") }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    askAction()
                    IconButton(onClick = onAddBookmark) { Icon(Icons.Outlined.BookmarkAdd, contentDescription = "在这里加书签") }
                }
            }
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(menuEnterSpec()) { it } + fadeIn(menuEnterSpec()),
            exit = slideOutVertically(menuExitSpec()) { it } + fadeOut(menuExitSpec()),
        ) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onChapter(chapterIndex - 1) }, enabled = chapterIndex > 0) { Text("上一章") }
                        if (chapterCount > 1) {
                            var dragging by remember(chapterIndex) { mutableFloatStateOf(chapterIndex.toFloat()) }
                            Slider(
                                value = dragging,
                                onValueChange = { dragging = it },
                                onValueChangeFinished = { onChapter(dragging.toInt()) },
                                valueRange = 0f..(chapterCount - 1).toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Box(Modifier.weight(1f))
                        }
                        TextButton(onClick = { onChapter(chapterIndex + 1) }, enabled = chapterIndex < chapterCount - 1) { Text("下一章") }
                    }
                    Row(horizontalArrangement = Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onOpenSheet(ReaderSheet.Contents) }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null)
                            Text("目录", modifier = Modifier.padding(start = 4.dp))
                        }
                        TextButton(onClick = { onOpenSheet(ReaderSheet.Bookmarks) }) {
                            Icon(Icons.Outlined.Bookmarks, contentDescription = null)
                            Text("书签", modifier = Modifier.padding(start = 4.dp))
                        }
                        TextButton(onClick = { onOpenSheet(ReaderSheet.Settings) }) {
                            Icon(Icons.Outlined.TextFields, contentDescription = null)
                            Text("排版", modifier = Modifier.padding(start = 4.dp))
                        }
                        if (showNative) {
                            TextButton(onClick = { onOpenSheet(ReaderSheet.Native) }) {
                                Icon(Icons.Outlined.Translate, contentDescription = null)
                                Text("母语", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
