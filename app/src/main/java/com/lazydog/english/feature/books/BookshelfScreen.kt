package com.lazydog.english.feature.books

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.BookRepository
import com.lazydog.english.core.database.BookEntity
import com.lazydog.english.domain.books.BookLanguage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 没有封面的书用这几种颜色，按书名挑一种，同一本书每次都一样。 */
private val COVER_COLORS = listOf(
    Color(0xFF5B6C8F), Color(0xFF7A5C61), Color(0xFF4E7A6A), Color(0xFF8A6D3B), Color(0xFF6B5B8A), Color(0xFF3F6E8C),
)

/**
 * 书架（D-098 R2，设计文档 §4.1）：封面网格，最近读的排前面，显示进度。
 * 右上角导入 EPUB / TXT；长按一本可以删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(onExit: () -> Unit, onOpenBook: (Long) -> Unit, onOpenCatalog: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val books by app.bookRepository.books.collectAsState(initial = null)
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<BookEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = runCatching { app.bookRepository.importFrom(uri) }
                .getOrElse { BookRepository.Imported.Failure("导入失败：${it.message ?: it.javaClass.simpleName}") }
            importing = false
            when (result) {
                is BookRepository.Imported.Success -> onOpenBook(result.bookId)
                is BookRepository.Imported.Failure -> message = result.reason
            }
        }
    }
    // EPUB 的 MIME 各家文件管理器给得不一样，有的给 octet-stream，一起放进来。
    val mimeTypes = arrayOf("application/epub+zip", "text/plain", "application/octet-stream")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    TextButton(onClick = onOpenCatalog) {
                        Icon(Icons.Outlined.TravelExplore, contentDescription = null)
                        Text("书库", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { picker.launch(mimeTypes) }, enabled = !importing) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("导入", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = books
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "书架是空的。导入手机里的 EPUB 或 TXT 电子书，中文、英文都可以。",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { picker.launch(mimeTypes) }, enabled = !importing) { Text("导入电子书") }
                    TextButton(onClick = onOpenCatalog) { Text("或者去书库找一本公版书") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { book ->
                        BookTile(
                            book = book,
                            onOpen = { onOpenBook(book.id) },
                            onLongPress = { deleting = book },
                        )
                    }
                }
            }
            if (importing) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("正在导入，长篇要几秒钟…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
            text = { Text(text) },
        )
    }
    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("书签和阅读进度一起删掉，正文文件也会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { app.bookRepository.delete(book.id) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookEntity, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val app = LocalContext.current.applicationContext as LazyDogApplication
    val cover = remember(book.id, book.coverFile) { app.bookRepository.coverFile(book) }
    Column(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(MaterialTheme.shapes.small)
                .background(COVER_COLORS[(book.title.hashCode() and Int.MAX_VALUE) % COVER_COLORS.size]),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val percent = (book.progress * 100).roundToInt()
        Text(
            text = listOf(
                BookLanguage.fromWire(book.language).labelZh,
                if (book.lastOpenedAt == 0L) "未读" else "读到 $percent%",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
