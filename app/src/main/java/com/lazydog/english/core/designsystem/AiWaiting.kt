package com.lazydog.english.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.domain.generation.GenerationStage
import kotlinx.coroutines.delay

/**
 * 等 AI 的统一等待区。
 *
 * 之前每个页面各写一句"AI 正在挑词…"加一个转圈，等待期间屏幕上唯一会变的是字符数——
 * 而字符数在最花时间的那一段（推理模型开口前的思考）根本不动，看着就像卡死。
 * 实测一次听力：思考 49 秒、写 29 秒，等于前六成时间界面完全静止。
 *
 * 所以这里统一回答三件事：
 * 1. **现在卡在哪一步**（[GenerationStage]）：还没接通 / 模型在想 / 正在写。
 *    "没接通"和"在想"用户该做的反应不一样，一个是去查网络，一个是等。
 * 2. **等了多久**：干等时秒数是唯一在动的东西，也让"到底多久"变成能说出口的数字。
 * 3. **正在做什么**（[title]），由各页面自己说。
 *
 * [detail] 给已经有更好话可说的页面用（比如听力的"已经写好 3 句"），传了就盖掉默认那句。
 */
@Composable
fun AiWaiting(
    title: String,
    stage: GenerationStage,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val seconds = rememberWaitedSeconds()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = if (stage is GenerationStage.Thinking) "$title（模型正在想）" else title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail ?: stageDetail(stage, seconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 等待期间那句副标题。等得越久越要说明白在等什么。 */
fun stageDetail(stage: GenerationStage, seconds: Int): String {
    val waited = if (seconds >= 3) "（已等 $seconds 秒）" else ""
    return when (stage) {
        GenerationStage.Connecting -> "接通中$waited"
        is GenerationStage.Thinking ->
            // 服务商肯把思考过程流出来就显示尾巴，证明它确实在动；不给就说清这段本来就慢。
            if (stage.excerpt.isNotBlank()) {
                "…${stage.excerpt}"
            } else {
                "推理模型会先想一会儿再动笔，这段最花时间$waited"
            }
        is GenerationStage.Writing -> "已经写了 ${stage.chars} 个字符$waited"
    }
}

/** 从这个组合项出现算起等了几秒。干等时它是唯一保证会动的东西。 */
@Composable
fun rememberWaitedSeconds(): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds += 1
        }
    }
    return seconds
}
