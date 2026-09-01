package com.lazydog.english.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 一组可多选、也可以自己往里加的标签（学习目标、感兴趣话题都用它）。
 *
 * 预置项只是起点：目标和兴趣本来就是每个人自己的事，写不进四个固定选项里。
 * 用户加的项和预置项一样是普通标签——加进来即选中，取消选中就从列表里消失，
 * 不额外维护一份"我加过的词"，省得出现一堆点不掉的残留 chip。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 最多选几个；null 表示不限。到上限后未选中的项点不动。 */
    max: Int? = null,
    addPlaceholder: String = "自己写一个",
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // 用户自己加的排在预置项后面，顺序稳定，不会因为重组跳来跳去。
    val all = options + selected.filterNot { it in options }
    val atLimit = max != null && selected.size >= max

    fun commit() {
        val value = draft.trim().take(12)
        draft = ""
        adding = false
        if (value.isNotEmpty() && value !in selected && !atLimit) onToggle(value)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            all.forEach { option ->
                val isSelected = option in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(option) },
                    enabled = isSelected || !atLimit,
                    label = { Text(option) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
            if (!adding) {
                AssistChip(
                    onClick = { adding = true },
                    enabled = !atLimit,
                    label = { Text("自己加") },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                )
            }
        }
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(12) },
                    placeholder = { Text(addPlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { commit() }, enabled = draft.isNotBlank()) { Text("加上") }
                TextButton(onClick = { draft = ""; adding = false }) { Text("算了") }
            }
        }
        if (atLimit) {
            Text(
                text = "已经选满了，想换就先点掉一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}
