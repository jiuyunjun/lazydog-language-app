package com.lazydog.english.feature.reading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.generation.HotTopicCategory
import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.ageZh
import com.lazydog.english.domain.generation.distinctHeadlines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 母语阅读开篇页的「今日热点」（`母语阅读DESIGN.md` §6.2 模式 B 的最小版）。
 *
 * 点一个分类才去拉，不进页面就拉：新闻检索花的是用户自己的 Brave 配额，
 * 大多数时候用户是来读常识主题的。挑中一条后由开篇页按"这条新闻 + 一周内补检索"写，
 * 聚类、去低价值、生成角度这几步还没做（ROADMAP）。
 */
@Composable
internal fun HotTopicsSection(picked: WebSearchHit?, onPick: (WebSearchHit) -> Unit) {
    val app = LocalContext.current.applicationContext as LazyDogApplication
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf<HotTopicCategory?>(null) }
    var headlines by remember { mutableStateOf<List<WebSearchHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun load(target: HotTopicCategory) {
        category = target
        loading = true
        failure = null
        headlines = emptyList()
        scope.launch {
            var result = app.webSearch.news(target.query, freshness = WebSearchProvider.FRESH_DAY)
            // 一天内太少（小分类、凌晨）就放宽到一周。Brave 免费档每秒一次，隔开再发。
            if (result.failure == null && distinctHeadlines(result.hits).size < MIN_HEADLINES) {
                delay(RATE_GAP_MILLIS)
                val week = app.webSearch.news(target.query, freshness = WebSearchProvider.FRESH_WEEK)
                if (week.failure == null) result = week
            }
            // 用户在等的时候换了分类，这批就作废。
            if (category != target) return@launch
            headlines = distinctHeadlines(result.hits)
            failure = result.failure
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("今日热点", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HotTopicCategory.entries.forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { load(option) },
                    label = { Text(option.labelZh) },
                )
            }
        }
        when {
            category == null -> Text(
                text = "点一个分类，看看最近一天的新闻；挑一条就围绕它先检索再写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在找最近的新闻…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            headlines.isEmpty() -> Text(
                text = failure ?: "这个分类最近没搜到新闻，换一个试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> headlines.forEach { hit ->
                HeadlineRow(hit = hit, selected = picked == hit, onClick = { onPick(hit) })
            }
        }
    }
}

@Composable
private fun HeadlineRow(hit: WebSearchHit, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = hit.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(hit.source, ageZh(hit.age)).filter { it.isNotBlank() }.joinToString(" · ")
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

/** 一天内少于这么多条就放宽到一周。 */
private const val MIN_HEADLINES = 4

/** Brave 免费档每秒一次请求。 */
private const val RATE_GAP_MILLIS = 1_100L
