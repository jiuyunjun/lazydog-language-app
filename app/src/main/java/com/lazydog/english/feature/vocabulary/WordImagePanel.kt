package com.lazydog.english.feature.vocabulary

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.vocabulary.ImageFailureReason
import com.lazydog.english.domain.vocabulary.SenseKey
import com.lazydog.english.domain.vocabulary.SenseVisualState
import com.lazydog.english.domain.vocabulary.VocabularyImageAsset
import kotlinx.coroutines.launch

/**
 * 词卡里的视觉记忆图片（`单词视觉记忆图片DESIGN.md` §45、§63）。
 *
 * 位置是有意的：这一块在**释义之后、例句之前**，而不是设计文档 §45 示意图里的最顶上。
 * 图片可能不存在也可能加载失败，放最顶上时收起图片区会让词头每次开卡都跳位；
 * 放在释义之后，词、音标、释义的位置永远固定，图有没有只影响它自己下面的东西。
 *
 * 五种状态见 [SenseVisualState]。任何一种都不阻塞词卡的其余部分：词、音标、释义、例句
 * 先渲染，这一块自己异步填（§64）。
 *
 * [autoSearch] 为 false 时只读缓存、绝不发起检索——阅读页点词用的就是这个：
 * 点开等三秒会毁掉阅读节奏，图必须是文章生成时预取好的（§48）。
 */
@Composable
fun WordImagePanel(
    senseKey: SenseKey,
    term: String,
    meaningZh: String,
    modifier: Modifier = Modifier,
    pos: String = "",
    exampleEn: String = "",
    autoSearch: Boolean = true,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val repository = app.vocabularyImageRepository
    val scope = rememberCoroutineScope()

    key(senseKey.value) {
        val stateFlow = remember(senseKey.value) { repository.observe(senseKey) }
        val state by stateFlow.collectAsState(initial = null)
        var searching by remember(senseKey.value) { mutableStateOf(false) }

        LaunchedEffect(senseKey.value, autoSearch) {
            if (!autoSearch) return@LaunchedEffect
            searching = true
            runCatching { repository.ensure(senseKey, term, meaningZh, pos, exampleEn) }
            searching = false
        }

        WordImageContent(
            state = state,
            searching = searching,
            modifier = modifier,
            onRefresh = {
                scope.launch {
                    searching = true
                    runCatching { repository.ensure(senseKey, term, meaningZh, pos, exampleEn, force = true) }
                    searching = false
                }
            },
            onSelect = { index -> scope.launch { repository.select(senseKey, index) } },
            onHide = { scope.launch { repository.hide(senseKey, term, meaningZh) } },
            onBroken = { index -> scope.launch { repository.dropBroken(senseKey, index) } },
            onFeedback = { reason -> scope.launch { repository.recordFeedback(senseKey, reason) } },
        )
    }
}

/**
 * 只读缓存的小图，给复习答案面和阅读点词抽屉用（§46、§48）。
 * 缓存里没有就什么都不显示——这两个地方都不该为了一张图去发请求。
 */
@Composable
fun CachedSenseImage(senseKey: SenseKey, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val stateFlow = remember(senseKey.value) { app.vocabularyImageRepository.observe(senseKey) }
    val state by stateFlow.collectAsState(initial = null)
    val asset = state?.takeIf { it.hasImage }?.selected ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ImageFrame(
            asset = asset,
            contentDescription = state?.contentDescriptionZh().orEmpty(),
            onBroken = {},
        )
        SourceLine(asset)
    }
}

@Composable
private fun WordImageContent(
    state: SenseVisualState?,
    searching: Boolean,
    modifier: Modifier,
    onRefresh: () -> Unit,
    onSelect: (Int) -> Unit,
    onHide: () -> Unit,
    onBroken: (Int) -> Unit,
    onFeedback: (String) -> Unit,
) {
    // 这个词义本来就不该有图，或者用户关掉了：整块连标题一起不渲染，
    // 词卡里看不出这儿曾经有东西（§63）。
    if (state?.silent == true) return
    if (state == null && !searching) return

    var picking by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var brokenNotice by remember(state?.selected?.thumbnailUrl) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val asset = state?.selected
        when {
            asset != null -> {
                SectionLabel()
                ImageFrame(
                    asset = asset,
                    contentDescription = state.contentDescriptionZh(),
                    onBroken = {
                        brokenNotice = true
                        onBroken(state.selectedIndex)
                    },
                )
                if (brokenNotice) BrokenBanner()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) { SourceLine(asset) }
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.height(40.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        )
                        Text("换一张", style = MaterialTheme.typography.labelLarge)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "这张图的更多操作")
                        }
                        FeedbackMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            onPick = { reason ->
                                menuOpen = false
                                onFeedback(reason)
                            },
                            onHide = {
                                menuOpen = false
                                onHide()
                            },
                        )
                    }
                }
            }
            // 缓存没命中，正在跑 判断 → 检索词 → Brave。词卡其余部分早就可读了。
            searching -> {
                SectionLabel()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        Text(
                            text = "正在给「${state?.meaningZh.orEmpty().ifBlank { "这个意思" }}」找图",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 搜过了但没有能用的。收成一行，不留一整块空框——宁可没有，不硬配（§57）。
            else -> UnavailableLine(state?.failure, onRefresh)
        }
    }

    if (picking && state != null) {
        ImagePickerSheet(
            state = state,
            onDismiss = { picking = false },
            onSelect = {
                onSelect(it)
                picking = false
            },
            onHide = {
                onHide()
                picking = false
            },
            onRefresh = {
                onRefresh()
                picking = false
            },
        )
    }
}

@Composable
private fun SectionLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        // 说的是"这个词义"，不是"这个单词"——整个模块就是为了这个区别存在的。
        Text(
            text = "这个词义看起来是这样",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 固定 16:9 裁切，而不是按原图比例撑高：候选图的长宽千奇百怪，
 * 跟着原图走会让每张词卡的高度都不一样，翻卡时整页都在跳。
 */
@Composable
private fun ImageFrame(
    asset: VocabularyImageAsset,
    contentDescription: String,
    onBroken: () -> Unit,
) {
    AsyncImage(
        model = asset.thumbnailUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onState = { imageState -> if (imageState is AsyncImagePainter.State.Error) onBroken() },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/**
 * 来源常驻，不是装饰：Brave 负责发现，不等于拿到第三方图片的版权（§33）。
 * 整行可点，打开图片所在的原网页。
 */
@Composable
private fun SourceLine(asset: VocabularyImageAsset) {
    val context = LocalContext.current
    val target = asset.sourcePageUrl.ifBlank { asset.originalUrl }
    Column(
        modifier = Modifier.clickable(enabled = target.isNotBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri())) }
        },
    ) {
        Text(
            text = "来自网络搜索" + asset.hostLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = asset.rightsNoteZh,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 外链挂了会自动顶下一张，但得说一声，否则用户会以为是自己看花了眼（§37）。 */
@Composable
private fun BrokenBanner() {
    val extended = LazyDogTheme.extendedColors
    Surface(color = extended.attentionContainer, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = "原来那张图挂了，换成了备用的一张",
            style = MaterialTheme.typography.bodySmall,
            color = extended.onAttentionContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun UnavailableLine(failure: ImageFailureReason?, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = Icons.Outlined.HideImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = (failure ?: ImageFailureReason.NoResults).messageZh,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("再找一次") }
    }
}

/** ⋯ 里的反馈。只记，不当场改这张图——当场换掉会让用户以为反馈是撤销键（§41）。 */
@Composable
private fun FeedbackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onHide: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            "irrelevant" to "图片不相关",
            "ambiguous" to "太抽象，看不出是哪个意思",
            "low_quality" to "图片质量差",
            "text_heavy" to "图上全是字",
        ).forEach { (reason, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onPick(reason) })
        }
        DropdownMenuItem(text = { Text("这个词不用配图") }, onClick = onHide)
    }
}

/**
 * 换一张（§40）。
 *
 * 每条候选都写一句来源，不只是三张缩略图并排：用户不是在挑好看的，
 * 是在挑说得清这个意思的，来源本身就是判断依据的一部分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePickerSheet(
    state: SenseVisualState,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onHide: () -> Unit,
    onRefresh: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("换一张", style = MaterialTheme.typography.headlineSmall)
                Text(
                    // 把 sense-first 说给用户听：他挑的是"哪张说清了这个意思"，不是"哪张和这个词有关"。
                    text = "都是照着「${state.meaningZh.ifBlank { "这个意思" }}」找的，不是照着 ${state.term} 这个词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.assets.forEachIndexed { index, asset ->
                val current = index == state.selectedIndex
                Surface(
                    color = if (current) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(16.dp),
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = asset.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(116.dp)
                                .height(84.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = if (current) "正在用这张图" else "换成这张图"
                                },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (current) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text("正在用这张", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Text(
                                text = asset.title.ifBlank { "这张图" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = asset.hostLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("都不合适，换一批检索词重新找")
            }
            TextButton(onClick = onHide, modifier = Modifier.fillMaxWidth()) {
                Text("这个词不用配图")
            }
        }
    }
}
