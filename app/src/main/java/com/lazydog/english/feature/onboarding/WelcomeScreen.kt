package com.lazydog.english.feature.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.backup.AutoBackupWorker
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onStart: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var restoring by remember { mutableStateOf(false) }
    var restoreStatus by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        restoring = true
        restoreStatus = "正在从这个文件夹恢复…"
        scope.launch {
            app.backupFileStore.read(uri.toString()).fold(
                onSuccess = { payload ->
                    app.backupRepository.restore(payload)
                    app.userPreferences.setBackupFolderUri(uri.toString())
                    AutoBackupWorker.schedule(context)
                    restoring = false
                    onRestored()
                },
                onFailure = {
                    restoring = false
                    restoreStatus = "没恢复成功：${it.message}。可以换个文件夹再试，或者直接开始配置。"
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 这一屏没有 Scaffold 兜底，自己让开状态栏和导航栏——
            // 不让的话最底下那个"从备份恢复"会压在手势条上。
            .safeDrawingPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            text = "懒狗放洋屁",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "AI 帮你备课，你只管学。\n每天 10～15 分钟，今天少学一点，也算学了。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            WelcomeBullet("先做一次自适应测试，摸清底子")
            WelcomeBullet("所有学习记录只存在这台手机和你自己选的备份文件夹里")
        }
        Button(
            onClick = onStart,
            enabled = !restoring,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
                .height(48.dp),
        ) {
            Text("开始配置")
        }
        TextButton(
            onClick = { folderPicker.launch(null) },
            enabled = !restoring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (restoring) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text("正在恢复…")
                }
            } else {
                Text("换过手机？选文件夹恢复之前的数据")
            }
        }
        if (restoreStatus != null) {
            Text(
                text = restoreStatus.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WelcomeBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
