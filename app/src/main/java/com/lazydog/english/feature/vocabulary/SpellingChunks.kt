package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingFacts

/**
 * 拼写分组（S0 接触），只高亮已标记的易错片段；分组本身不证明构词或发音边界。
 *
 * 学习页和记录详情页共用一份：同一个词在两个地方该长得一样，
 * 从记录里点开却少了半屏内容，用户会以为这条记录存坏了。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SpellingChunks(term: String, facts: SpellingFacts) {
    val chunks = remember(term, facts) { SpellingEngine.chunkWord(term, facts) }
    if (chunks.size < 2) return
    val extended = LazyDogTheme.extendedColors
    // 只高亮生成时标好的易错段；没有依据就不猜哪一块更难。
    val trickyIndex = remember(term, facts, chunks) { chunks.indexOfTricky(facts) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "拼写分组",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("分段记字母，不代表词根词缀；读音听整词。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chunks.forEachIndexed { index, chunk ->
                val highlight = index == trickyIndex
                Surface(
                    color = if (highlight) extended.attentionContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = chunk,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (highlight) extended.attention else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // 只有真拿到易错段时才敢下这个断言。猜出来的词块（necessary → nec/ess/ary）
        // 配上"这里最容易拼错"就是一句假话，宁可不说。
        if (trickyIndex >= 0) {
            Text(
                text = "${chunks[trickyIndex]} 是最容易拼错的部分",
                style = MaterialTheme.typography.bodySmall,
                color = extended.attention,
            )
        }
    }
}

/** 易错段落在哪一块；没标或对不上返回 -1。 */
private fun List<String>.indexOfTricky(facts: SpellingFacts): Int {
    val part = facts.trickyPart.trim().lowercase()
    if (part.isEmpty()) return -1
    return indexOfFirst { it.lowercase().contains(part) || part.contains(it.lowercase()) }
}
