package com.lazydog.english.feature.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.database.BookBookmarkEntity
import com.lazydog.english.core.database.BookChapterEntity
import com.lazydog.english.domain.books.PageMode
import com.lazydog.english.domain.books.ReaderSettings
import com.lazydog.english.domain.books.ReaderTheme
import kotlinx.coroutines.launch

/** 目录：当前章加粗，打开时滚到当前章附近。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(
    chapters: List<BookChapterEntity>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(chapters) { index, chapter ->
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (index == current) FontWeight.Bold else null,
                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(index) }.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** 书签列表：章节名、摘录；点开跳过去，右边删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(
    bookId: Long,
    chapters: List<BookChapterEntity>,
    onPick: (BookBookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LazyDogApplication
    val scope = rememberCoroutineScope()
    val bookmarks by remember(bookId) { app.bookRepository.bookmarks(bookId) }.collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (bookmarks.isEmpty()) {
            Text(
                text = "还没有书签。阅读时点中间呼出菜单，右上角可以加书签。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp).navigationBarsPadding(),
            )
            return@ModalBottomSheet
        }
        LazyColumn(modifier = Modifier.navigationBarsPadding()) {
            items(bookmarks, key = { it.id }) { bookmark ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(bookmark) }.padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = chapters.getOrNull(bookmark.chapterIndex)?.title.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = bookmark.excerpt,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { scope.launch { app.bookRepository.deleteBookmark(bookmark.id) } }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除这个书签")
                    }
                }
            }
        }
    }
}

/** 排版：字号、行距、页边距、字体、主题、翻页方式。改了立刻生效，所有书共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
                OutlinedButton(
                    onClick = { onChange(settings.withFontSize(-1)) },
                    enabled = settings.fontSizeSp > ReaderSettings.MIN_FONT,
                ) { Text("A-") }
                Text(
                    text = settings.fontSizeSp.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedButton(
                    onClick = { onChange(settings.withFontSize(1)) },
                    enabled = settings.fontSizeSp < ReaderSettings.MAX_FONT,
                ) { Text("A+") }
            }
            OptionRow("行距", ReaderSettings.LINE_HEIGHTS, settings.lineHeight, label = { lineHeightLabel(it) }) {
                onChange(settings.copy(lineHeight = it))
            }
            OptionRow("页边距", ReaderSettings.MARGINS, settings.marginDp, label = { marginLabel(it) }) {
                onChange(settings.copy(marginDp = it))
            }
            OptionRow("字体", listOf(true, false), settings.serif, label = { if (it) "衬线" else "无衬线" }) {
                onChange(settings.copy(serif = it))
            }
            OptionRow("主题", ReaderTheme.entries, settings.theme, label = { it.labelZh }) {
                onChange(settings.copy(theme = it))
            }
            OptionRow("翻页", PageMode.entries, settings.pageMode, label = { it.labelZh }) {
                onChange(settings.copy(pageMode = it))
            }
        }
    }
}

private fun lineHeightLabel(value: Float): String = when (value) {
    ReaderSettings.LINE_HEIGHTS[0] -> "紧凑"
    ReaderSettings.LINE_HEIGHTS[2] -> "宽松"
    else -> "适中"
}

private fun marginLabel(value: Int): String = when (value) {
    ReaderSettings.MARGINS[0] -> "窄"
    ReaderSettings.MARGINS[2] -> "宽"
    else -> "适中"
}

@Composable
private fun <T> OptionRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}
