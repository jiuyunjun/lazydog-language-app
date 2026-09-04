package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.designsystem.InteractiveEnglishBlock
import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.generation.GenerationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 释义下面的那几个搭配短语：翻译直接摆在英文下面，点一下就念。
 *
 * 一串裸英文短语在初学者眼里和没写差不多——不知道怎么读，也不确定是什么意思，
 * 所以翻译跟着这个词一起生成、一起入库，进屏就已经在那儿了。
 * 只有老词条（入库时还没有这个字段）才留着「点一下现翻」这条退路。
 * 双击查词、三击讲整句仍然照旧。
 *
 * 学习卡和记录里的单词页显示的是同一个东西，所以放在这里共用。
 */
@Composable
fun CollocationChip(collocation: Collocation) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val phrase = collocation.en
    var fetched by remember(phrase) { mutableStateOf("") }
    var failed by remember(phrase) { mutableStateOf("") }
    var loading by remember(phrase) { mutableStateOf(false) }
    val translation = collocation.zh.ifBlank { fetched }

    // 点整块只负责把缺的翻译取回来；念不念由喇叭那个按钮说了算。
    fun tap() {
        if (translation.isNotBlank() || loading) return
        loading = true
        failed = ""
        scope.launch {
            val result = app.contentGenerator.explainSentence(
                phrase,
                app.userPreferences.vocabLevelDescription.first(),
            )
            loading = false
            when (result) {
                is GenerationResult.Success -> fetched = result.data.translationZh
                is GenerationResult.Failure -> failed = result.reason
            }
        }
    }

    // 整块仍然可点（双击查词、三击讲整条搭配）：这块小到只有两三个词，
    // 逼人去瞄准那行英文的字面，点偏一点就没反应，看着像坏了。
    // 但**播放归喇叭管**——它和别处的朗读按钮是同一个组件，
    // 有加载转圈和停止态；藏在整块手势里的话，这些状态就没地方显示了。
    InteractiveEnglishBlock(
        text = phrase,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        spacing = 2.dp,
        fillWidth = false,
        showHint = false,
        // 单击不只是念：老词条没存翻译，这一下还要把翻译现取回来。
        onSingleTap = { tap() },
        trailing = {
            SpeakButton(
                source = PlaybackSource.sentence(phrase),
                contentDescription = "读这个搭配",
                modifier = Modifier.size(32.dp),
                iconSize = 18.dp,
            )
        },
        below = {
            when {
                translation.isNotBlank() -> Text(
                    text = translation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loading -> Text(
                    text = "翻译中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failed.isNotBlank() -> Text(
                    text = "翻译没拿到，再点一下",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
