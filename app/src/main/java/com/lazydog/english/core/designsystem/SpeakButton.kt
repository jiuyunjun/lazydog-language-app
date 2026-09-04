package com.lazydog.english.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus

/**
 * 统一的朗读按钮：图标按全局播放状态画，点击交给 [com.lazydog.english.core.speech.PlaybackController]。
 *
 * 页面不要自己存「这个按钮在不在播」（`语音服务DESIGN.md` §23）——一页上好几个播放按钮时，
 * 各存各的迟早会同时显示在播。这里一律按 sourceId 去问全局状态，任何时刻最多一个按钮是「播/加载」。
 *
 * 状态映射见 §24：
 * - 空闲 → 喇叭，点一下开始念
 * - 加载 → 转圈（合成还没出声，这段可能要等网络）
 * - 播放 → 停止圆钮，再点一下就停
 * - 出错 → 还是喇叭，但用错误色，点一下重来
 *
 * 短句朗读不做暂停：几秒钟的东西，停了重念比接着念更符合直觉。
 */
@Composable
fun SpeakButton(
    source: PlaybackSource,
    contentDescription: String = "朗读",
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val speech = remember { (context.applicationContext as LazyDogApplication).speechController }
    val state by speech.playback.collectAsState()
    val status = state.statusOf(source.id)

    IconButton(onClick = { speech.onPlayClicked(source) }, modifier = modifier) {
        when (status) {
            PlaybackStatus.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = tint,
            )

            PlaybackStatus.Playing -> Icon(
                imageVector = Icons.Outlined.StopCircle,
                contentDescription = "停止朗读",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )

            PlaybackStatus.Idle, PlaybackStatus.Error -> Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = contentDescription,
                tint = if (status == PlaybackStatus.Error) MaterialTheme.colorScheme.error else tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
