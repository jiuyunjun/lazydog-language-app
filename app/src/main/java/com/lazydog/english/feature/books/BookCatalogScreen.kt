package com.lazydog.english.feature.books

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.BookCatalogService
import com.lazydog.english.domain.books.Ao3Rating
import com.lazydog.english.domain.books.CatalogEntry
import com.lazydog.english.domain.books.CatalogResult
import com.lazydog.english.domain.books.CatalogSource
import com.lazydog.english.domain.books.WIKISOURCE_SUGGESTIONS
import kotlinx.coroutines.launch

/** 下载对话框的几种状态。 */
private sealed interface DownloadState {
    data class Running(val entry: CatalogEntry, val progress: String) : DownloadState
    data class Busy(val entry: CatalogEntry, val reason: String) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * 在线书库（D-098 R3，设计文档 §3）：古腾堡、维基文库、AO3 三个页签，检索、下载、直接打开。
 * 古腾堡和 AO3 不带关键词时列最受欢迎的；维基文库不好浏览，给几部常读的作起点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCatalogScreen(onExit: () -> Unit, onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(CatalogSource.Gutenberg) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var ao3Rating by rememberSaveable { mutableStateOf(Ao3Rating.All) }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<CatalogResult?>(null) }
    var download by remember { mutableStateOf<DownloadState?>(null) }

    val waitingForQuery = source == CatalogSource.Wikisource && submitted.isBlank()
    LaunchedEffect(source, submitted, refresh, ao3Rating) {
        result = null
        if (waitingForQuery) return@LaunchedEffect
        result = app.bookCatalogService.search(source, submitted, ao3Rating)
    }

    fun search(text: String) {
        query = text
        submitted = text.trim()
        refresh++
    }

    fun start(entry: CatalogEntry) {
        download = DownloadState.Running(entry, "正在下载…")
        scope.launch {
            val outcome = app.bookCatalogService.download(entry) { progress ->
                download = DownloadState.Running(entry, progress)
            }
            download = when (outcome) {
                is BookCatalogService.Downloaded.Success -> {
                    onOpenBook(outcome.bookId)
                    null
                }
                is BookCatalogService.Downloaded.Busy -> DownloadState.Busy(entry, outcome.reason)
                is BookCatalogService.Downloaded.Failure -> DownloadState.Failed(outcome.reason)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书库") },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CatalogSource.entries.forEach { option ->
                    FilterChip(
                        selected = source == option,
                        onClick = {
                            source = option
                            query = ""
                            submitted = ""
                        },
                        label = { Text(option.labelZh) },
                    )
                }
            }
            Text(
                text = source.noteZh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (source == CatalogSource.Wikisource) "作品名，比如 西遊記" else "英文书名或作者，留空看最受欢迎的") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { search(query) }) { Icon(Icons.Outlined.Search, contentDescription = "检索") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (source == CatalogSource.Ao3) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Ao3Rating.entries.forEach { rating ->
                        FilterChip(
                            selected = ao3Rating == rating,
                            onClick = { ao3Rating = rating },
                            label = { Text(rating.labelZh) },
                        )
                    }
                }
            }
            if (source == CatalogSource.Wikisource) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WIKISOURCE_SUGGESTIONS.forEach { title ->
                        AssistChip(onClick = { search(title) }, label = { Text(title) })
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val current = result
                when {
                    waitingForQuery -> Text(
                        text = "输入作品名，或者点上面一部开始。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    current.failure != null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(current.failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { refresh++ }) { Text("再试一次") }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(current.entries, key = { it.source.name + it.id }) { entry ->
                            CatalogCard(entry, onClick = { start(entry) })
                        }
                    }
                }
            }
        }
    }

    when (val state = download) {
        is DownloadState.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(state.entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(state.progress)
                }
            },
        )
        is DownloadState.Busy -> AlertDialog(
            onDismissRequest = { download = null },
            title = { Text("暂时下不了") },
            text = { Text("${state.reason}。可以在浏览器里打开这本书，下载 EPUB，再回书架点「导入」。") },
            confirmButton = {
                TextButton(onClick = {
                    download = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.entry.webUrl))) }
                }) { Text("在浏览器打开") }
            },
            dismissButton = { TextButton(onClick = { download = null }) { Text("算了") } },
        )
        is DownloadState.Failed -> AlertDialog(
            onDismissRequest = { download = null },
            text = { Text(state.reason) },
            confirmButton = { TextButton(onClick = { download = null }) { Text("知道了") } },
        )
        null -> Unit
    }
}

@Composable
private fun CatalogCard(entry: CatalogEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.author.isNotBlank()) {
                Text(entry.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.meta.isNotBlank()) {
                Text(entry.meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
