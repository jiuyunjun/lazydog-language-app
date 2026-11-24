package com.lazydog.english.feature.reading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.generation.NewsCategory
import com.lazydog.english.domain.generation.NewsText
import com.lazydog.english.domain.reading.VoaChannel
import com.lazydog.english.domain.reading.WebReadingCard
import com.lazydog.english.domain.reading.WebReadingFeed
import com.lazydog.english.domain.reading.WebReadingSource

/** 英文新闻按这些分类取；娱乐、国内这种对学英语意义不大的不放。 */
private val NEWS_CATEGORIES = listOf(
    NewsCategory.Top, NewsCategory.World, NewsCategory.Tech, NewsCategory.Science,
    NewsCategory.Business, NewsCategory.Health, NewsCategory.Sports,
)

/** 列表按（来源, 栏目或检索词）缓存在页面生命周期里，切来切去不重拉。 */
private object WebFeedCache {
    private val feeds = HashMap<String, WebReadingFeed>()
    fun get(key: String) = feeds[key]
    fun put(key: String, feed: WebReadingFeed) {
        feeds[key] = feed
    }
}

/**
 * 日常阅读的入口（D-098 R1）：从网上挑一篇真实文章读。AI 写一篇是次要入口，放在最底下。
 *
 * 三个来源各一个页签；VOA 和新闻按栏目，维基可以检索也可以随机来一批。
 * 读过的文章标「读过」，再点开是存好的那一篇。
 */
@Composable
internal fun WebReadingFeedView(
    onOpen: (WebReadingCard) -> Unit,
    onAiWrite: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LazyDogApplication
    var source by rememberSaveable { mutableStateOf(WebReadingSource.Voa) }
    var voaChannel by rememberSaveable { mutableStateOf(VoaChannel.AsItIs) }
    var newsCategory by rememberSaveable { mutableStateOf(NewsCategory.Top) }
    var wikiQuery by rememberSaveable { mutableStateOf("") }
    var wikiSubmitted by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var feed by remember { mutableStateOf<WebReadingFeed?>(null) }
    val opened by app.readingRepository.openedSourceUrls.collectAsState(initial = emptyList())

    val cacheKey = when (source) {
        WebReadingSource.Voa -> "voa:${voaChannel.name}"
        WebReadingSource.News -> "news:${newsCategory.name}"
        WebReadingSource.Wiki -> "wiki:$wikiSubmitted"
    }

    LaunchedEffect(cacheKey, refresh) {
        // 维基不带检索词时是随机一批，刷新就该换一批，所以不吃缓存。
        val cached = if (refresh == 0) WebFeedCache.get(cacheKey) else null
        if (cached != null) {
            feed = cached
            return@LaunchedEffect
        }
        feed = null
        val fresh = when (source) {
            WebReadingSource.Voa -> app.webReadingService.voa(voaChannel)
            WebReadingSource.News -> app.webReadingService.news(newsCategory)
            WebReadingSource.Wiki -> app.webReadingService.wiki(wikiSubmitted)
        }
        if (fresh.failure == null) WebFeedCache.put(cacheKey, fresh)
        feed = fresh
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WebReadingSource.entries.forEach { option ->
                FilterChip(
                    selected = source == option,
                    onClick = {
                        source = option
                        refresh = 0
                    },
                    label = { Text(option.labelZh) },
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { refresh++ }, enabled = feed != null) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新列表")
                }
            }
        }
        when (source) {
            WebReadingSource.Voa -> ChipRow(
                options = VoaChannel.entries,
                selected = voaChannel,
                label = { it.labelZh },
                onSelect = {
                    voaChannel = it
                    refresh = 0
                },
            )
            WebReadingSource.News -> ChipRow(
                options = NEWS_CATEGORIES,
                selected = newsCategory,
                label = { it.labelZh },
                onSelect = {
                    newsCategory = it
                    refresh = 0
                },
            )
            WebReadingSource.Wiki -> OutlinedTextField(
                value = wikiQuery,
                onValueChange = { wikiQuery = it },
                placeholder = { Text("英文关键词，比如 volcano；留空随机来一批") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        wikiSubmitted = wikiQuery.trim()
                        refresh = 0
                    }) { Icon(Icons.Outlined.Search, contentDescription = "检索维基") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    wikiSubmitted = wikiQuery.trim()
                    refresh = 0
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        val current = feed
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                current == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                current.failure != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = current.failure,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { refresh++ }) { Text("再试一次") }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(current.cards, key = { it.source.wire + it.key }) { card ->
                        WebCard(card = card, read = card.link in opened, onOpen = { onOpen(card) })
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onAiWrite,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Text("让 AI 按主题写一篇", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** 一条文章：标题、引子、栏目和时间。标题和引子是英文，也能双击查词；单击打开。 */
@Composable
private fun WebCard(card: WebReadingCard, read: Boolean, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InteractiveEnglishText(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                onSingleTap = onOpen,
            )
            if (card.teaser.isNotBlank()) {
                InteractiveEnglishText(
                    text = card.teaser,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    onSingleTap = onOpen,
                )
            }
            val meta = listOfNotNull(
                card.channel.takeIf { it.isNotBlank() },
                NewsText.ageZh(card.publishedAt, System.currentTimeMillis()).takeIf { it.isNotBlank() },
                "读过".takeIf { read },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
