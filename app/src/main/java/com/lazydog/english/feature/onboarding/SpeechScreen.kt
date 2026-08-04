package com.lazydog.english.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Azure Speech 配置，可跳过。
 * 第一版还没有朗读功能，也不落盘 Speech 密钥，这一步只保留流程和说明。
 */
@Composable
fun SpeechScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    var speechKey by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("eastasia") }

    OnboardingStepScaffold(
        title = "朗读服务",
        step = 4,
        onBack = onBack,
        bottomBar = {
            OnboardingBottomBar {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("完成配置")
                    }
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("暂时跳过")
                    }
                }
            }
        },
    ) {
        Text(
            text = "Azure Speech 只用于朗读的识别和发音反馈。现在不填也行，随时能在设置里补。朗读功能会在后续版本上线，这一版填了也先不保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = speechKey,
            onValueChange = { speechKey = it },
            label = { Text("Speech Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = region,
            onValueChange = { region = it },
            label = { Text("区域") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MicOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "录音权限不在这里申请。只有你真的进入朗读并按下录音时，系统才会问一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
