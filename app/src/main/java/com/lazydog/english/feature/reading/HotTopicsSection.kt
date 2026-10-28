package com.lazydog.english.feature.reading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.SuspendAsk
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.NewsCategory
import com.lazydog.english.domain.generation.NewsEdition
import com.lazydog.english.domain.generation.NewsFeedResult
import com.lazydog.english.domain.generation.NewsQuery
import com.lazydog.english.domain.generation.NewsSourceRef
import com.lazydog.english.domain.generation.NewsStory
import com.lazydog.english.domain.generation.NewsText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 从「今日热点」挑中的一条，连同它是哪个分类下挑的（当作文章类别）、
 * 哪国媒体的（决定清理还是全文翻译，D-089）。
 */
internal data class PickedNews(val story: NewsStory, val category: NewsCategory, val edition: NewsEdition) {
    /** 给人看的标题：外文新闻用译好的中文标题，没译成就用原文。 */
    val displayTitle: String get() = story.titleZh.ifBlank { story.title }
}

/**
 * 母语阅读开篇页的「今日热点」（D-088，`母语阅读DESIGN.md` §6.2 模式 B）。
 *
 * 版本 + 地区决定看哪里的新闻，分类决定看哪一类；两者都记住，下次打开还是这里。
 * 列表来自 Google 新闻（不要密钥），所以一进来就拉；连不上时换 Brave，会标出来。
 * 挑中一条后，开篇页去抓原文、只做清理，不再按"引人入胜"那套重写。
 */
@Composable
internal fun HotTopicsSection(picked: PickedNews?, braveReady: Boolean, onPick: (PickedNews) -> Unit) {
    val app = LocalContext.current.applicationContext as LazyDogApplication
    val scope = rememberCoroutineScope()
    val prefs = app.userPreferences
    val editionName by prefs.newsEdition.collectAsState(initial = null)
    val location by prefs.newsLocation.collectAsState(initial = null)
    val categoryName by prefs.newsCategory.collectAsState(initial = null)
    var showRegion by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NewsFeedResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    /** 「刷新」只是让同一个查询再跑一次，绕过缓存。 */
    var refresh by remember { mutableIntStateOf(0) }

    // 偏好还没读出来之前不拉，免得先按默认值拉一次、再按存的拉一次。
    val query = if (editionName == null || location == null || categoryName == null) {
        null
    } else {
        NewsQuery(NewsEdition.fromName(editionName.orEmpty()), NewsCategory.fromName(categoryName.orEmpty()), location.orEmpty())
    }

    LaunchedEffect(query, refresh) {
        val q = query ?: return@LaunchedEffect
        val cached = if (refresh == 0) NewsListCache.get(q) else null
        if (cached != null) {
            result = cached
            return@LaunchedEffect
        }
        loading = true
        result = null
        var fresh = app.newsFeed.headlines(q)
        // 外文媒体的标题先译成中文再给人挑（D-089）。译不出来就显示原文，不挡列表。
        if (q.edition.language.needsTranslation && fresh.stories.isNotEmpty()) {
            translating = true
            val zh = app.contentGenerator.translateNewsTitles(fresh.stories.map { it.title }, q.edition.language)
            if (zh is GenerationResult.Success) {
                fresh = fresh.copy(stories = fresh.stories.zip(zh.data) { story, title -> story.copy(titleZh = title) })
            }
            translating = false
        }
        NewsListCache.put(q, fresh)
        result = fresh
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日热点", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            query?.let { q ->
                AssistChip(
                    onClick = { showRegion = true },
                    label = { Text(regionLabel(q)) },
                    leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新新闻列表")
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NewsCategory.entries.forEach { option ->
                FilterChip(
                    selected = query?.category == option,
                    onClick = { scope.launch { prefs.saveNewsCategory(option.name) } },
                    label = { Text(option.labelZh) },
                )
            }
        }

        val current = result
        when {
            loading || query == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = if (translating) "正在把标题译成中文…" else "正在拉最新新闻…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            current == null -> Unit
            current.stories.isEmpty() -> Text(
                text = current.failure ?: "这个地区和分类最近没有新闻",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                if (current.fromFallback) {
                    Hint("Google 新闻连不上（大陆需要代理），这批来自 Brave 新闻检索，分类和地区只能大致匹配。")
                }
                if (!braveReady) {
                    Hint("看列表不用密钥；读新闻原文要用 Brave 搜索找链接，请先在设置里填 Brave 密钥。")
                }
                if (query.edition.language.needsTranslation) {
                    Hint("${query.edition.labelZh}媒体的新闻是${query.edition.language.labelZh}，读的时候全文翻成中文，不做摘要。")
                }
                val now = System.currentTimeMillis()
                current.stories.forEach { story ->
                    StoryRow(
                        story = story,
                        now = now,
                        selected = picked?.story == story,
                        onClick = { onPick(PickedNews(story, query.category, query.edition)) },
                    )
                }
            }
        }
    }

    if (showRegion && query != null) {
        RegionSheet(
            edition = query.edition,
            location = query.location,
            onDismiss = { showRegion = false },
            onConfirm = { edition, place ->
                showRegion = false
                scope.launch { prefs.saveNewsRegion(edition.name, place) }
            },
        )
    }
}

/** 「日本媒体 · 東京」：先说谁报，再说报哪里，免得把"日本"看成地区。 */
private fun regionLabel(query: NewsQuery): String =
    listOf("${query.edition.labelZh}媒体", query.location.trim()).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StoryRow(story: NewsStory, now: Long, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = story.titleZh.ifBlank { story.title },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // 译过的标题下面留一行原文，对照着看，也知道是哪国媒体怎么写的。
            if (story.titleZh.isNotBlank()) {
                Text(
                    text = story.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOf(
                story.source,
                NewsText.ageZh(story.publishedAt, now),
                if (story.related.isEmpty()) "" else "另有 ${story.related.size} 家报道",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 常用地名。只是省得打字，写别的地名一样能用（Google 按名字匹配本地新闻）。
 * 外文媒体要写当地文字：日本版认「東京」，不一定认「东京」。
 */
private fun placeSuggestions(edition: NewsEdition): List<String> = when (edition) {
    NewsEdition.Mainland -> listOf(
        "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆",
        "广东", "浙江", "江苏", "日本", "韩国", "美国", "英国", "欧洲",
    )
    NewsEdition.HongKong -> listOf("香港", "九龍", "新界", "澳門", "深圳", "日本", "美國")
    NewsEdition.Taiwan -> listOf("台北", "新北", "台中", "高雄", "台南", "日本", "美國")
    NewsEdition.Japan -> listOf("東京", "大阪", "京都", "横浜", "名古屋", "北海道", "沖縄", "福岡")
    NewsEdition.UnitedStates -> listOf("New York", "California", "Washington", "Texas", "Chicago", "Florida", "Boston")
    NewsEdition.UnitedKingdom -> listOf("London", "Manchester", "Scotland", "Wales", "Birmingham")
    NewsEdition.Korea -> listOf("서울", "부산", "인천", "제주")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RegionSheet(
    edition: NewsEdition,
    location: String,
    onDismiss: () -> Unit,
    onConfirm: (NewsEdition, String) -> Unit,
) {
    SuspendAsk("正在选新闻地区")
    var pickedEdition by remember { mutableStateOf(edition) }
    var place by remember { mutableStateOf(location) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("看哪里的新闻", style = MaterialTheme.typography.titleMedium)
            Text("媒体", style = MaterialTheme.typography.titleSmall)
            Hint("决定谁来报：哪国/地区的媒体、什么口径。")
            listOf(false, true).forEach { foreign ->
                Text(
                    text = if (foreign) "外文媒体（全文翻成中文再读）" else "中文媒体",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsEdition.entries.filter { it.language.needsTranslation == foreign }.forEach { option ->
                        FilterChip(
                            selected = pickedEdition == option,
                            onClick = {
                                // 换媒体后原来写的地名多半对不上（「杭州」拿去搜日本媒体），清掉。
                                if (option != pickedEdition) place = ""
                                pickedEdition = option
                            },
                            label = { Text(option.labelZh) },
                        )
                    }
                }
            }
            Text("地区", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = place,
                onValueChange = { place = it },
                label = { Text("城市、省份或国家，留空不限") },
                supportingText = if (pickedEdition.language.needsTranslation) {
                    { Text("外文媒体请写当地文字，比如 東京、New York") }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = place.isBlank(), onClick = { place = "" }, label = { Text("不限") })
                placeSuggestions(pickedEdition).forEach { name ->
                    FilterChip(selected = place.trim() == name, onClick = { place = name }, label = { Text(name) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onConfirm(pickedEdition, place.trim()) }, modifier = Modifier.weight(1f)) { Text("看这里的新闻") }
            }
        }
    }
}

/** 母语阅读里新闻的出处行：谁报的、什么时候、点开看原文。 */
@Composable
internal fun NewsSourceLine(source: NewsSourceRef) {
    val uriHandler = LocalUriHandler.current
    val time = source.publishedAt?.let {
        DateTimeFormatter.ofPattern("M月d日 HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val translated = source.translatedFrom.takeIf { it.isNotBlank() }?.let { "译自$it" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull("来源：${source.name}", translated, time).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.originalTitle.isNotBlank()) {
                Text(
                    text = "原标题：${source.originalTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (source.url.isNotBlank()) {
            TextButton(onClick = { runCatching { uriHandler.openUri(source.url) } }) { Text("看原文") }
        }
    }
}

/**
 * 新闻列表的进程内缓存，十分钟。开篇页进进出出、切分类再切回来，不用每次都重拉；
 * 刷新按钮绕过它。不落盘：新闻过了十分钟就该重拉。
 */
private object NewsListCache {
    private const val TTL_MILLIS = 10 * 60 * 1000L
    private val entries = mutableMapOf<NewsQuery, Pair<Long, NewsFeedResult>>()

    @Synchronized
    fun get(query: NewsQuery): NewsFeedResult? {
        val (at, result) = entries[query] ?: return null
        return result.takeIf { System.currentTimeMillis() - at < TTL_MILLIS && it.failure == null }
    }

    @Synchronized
    fun put(query: NewsQuery, result: NewsFeedResult) {
        entries[query] = System.currentTimeMillis() to result
    }
}
