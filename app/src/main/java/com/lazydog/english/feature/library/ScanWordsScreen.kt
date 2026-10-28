package com.lazydog.english.feature.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lazydog.english.core.designsystem.GlobalSentenceSheet
import com.lazydog.english.core.designsystem.GlobalWordSheet
import com.lazydog.english.core.ocr.LiveTextCamera
import com.lazydog.english.core.ocr.rememberLiveTextCameraState
import com.lazydog.english.domain.vocabulary.ScanMode
import com.lazydog.english.domain.vocabulary.ScanRefine
import com.lazydog.english.domain.vocabulary.ScanStabilizer
import com.lazydog.english.domain.vocabulary.ScanTarget
import com.lazydog.english.domain.vocabulary.TextBox
import kotlinx.coroutines.launch

/** 准星离最近的词还有多远就不算对准。比一行字高一点，手抖一下不会丢。 */
private val AIM_TOLERANCE = 28.dp

/** 已锁定的词享有的粘性：别的词要比它近出这么多才抢得走，交界处不来回跳。 */
private val AIM_STICKINESS = 12.dp

/** 这么久一个字都没认出来，就提示用户靠近、拿稳或者开手电。 */
private const val NO_TEXT_HINT_MILLIS = 2_000L

/**
 * 扫一扫：后置相机取景，屏幕正中一个十字准星，实时认字。
 *
 * 准星对准一个词，底下的结果卡跟着显示这个词和它所在的那句话；按正下方的加号，
 * 取词模式打开查词面板、取句模式打开讲句面板——和双击查词、三击讲句是同一个面板，
 * 看过讲解再由用户按按钮决定要不要放进记录（单词进「单词」，句子进「表达」）。
 * 这一屏自己不写任何记录。
 */
@Composable
fun ScanWordsScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasCamera()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA)
    }
    // 从系统设置里打开权限再切回来，要能直接接着用，不用退出重进。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCamera()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var torchOn by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !granted -> CameraPermissionPane(
                asked = asked,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
            cameraError -> Text(
                text = "打不开后置相机。可能被别的 App 占着，关掉它再回来试试。",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> ScanViewfinder(torchOn = torchOn, onCameraError = { cameraError = true })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回记录", tint = Color.White)
            }
            Text(
                text = "扫一扫",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (granted && !cameraError) {
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        imageVector = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                        contentDescription = if (torchOn) "关手电" else "开手电",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanViewfinder(torchOn: Boolean, onCameraError: () -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tolerance = with(density) { AIM_TOLERANCE.toPx() }
    val stickiness = with(density) { AIM_STICKINESS.toPx() }
    val stabilizer = remember { ScanStabilizer() }
    val camera = rememberLiveTextCameraState()
    val scope = rememberCoroutineScope()
    /** 按了加号、正在拍高清照片重认。这段时间加号转圈，不重复触发。 */
    var refining by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf<ScanTarget?>(null) }
    var lastTextAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var noTextForAWhile by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(ScanMode.Word) }
    /** 按下加号那一刻对准的目标。面板开着时识别暂停，不让后面的帧把它换掉。 */
    var picked by remember { mutableStateOf<Pair<ScanTarget, ScanMode>?>(null) }

    // 对准一个新词时轻轻震一下：眼睛盯着书页时，不用低头看结果卡也知道锁上了。
    val lockKey = target?.let { if (mode == ScanMode.Word) it.word else it.sentence }
    LaunchedEffect(lockKey) {
        if (lockKey != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // 识别每秒只出十来帧，平滑过的框仍然是一格一格跳的。高亮在两帧之间用弹簧滑过去；
    // 换了一个词就直接跳到位，不从上一个词那儿滑过来。
    val glide = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val glideTarget = target?.wordBoxes?.first()?.topLeft
    val glideIdentity = remember { arrayOf<String?>(null) }
    LaunchedEffect(glideTarget) {
        val to = glideTarget ?: return@LaunchedEffect
        if (glideIdentity[0] != target?.word) {
            glideIdentity[0] = target?.word
            glide.snapTo(to)
        } else {
            glide.animateTo(to, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LiveTextCamera(
            state = camera,
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
            paused = picked != null || refining,
            torchOn = torchOn,
            onText = { recognized ->
                val now = System.currentTimeMillis()
                if (recognized.isNotEmpty()) lastTextAt = now
                noTextForAWhile = recognized.isEmpty() && now - lastTextAt > NO_TEXT_HINT_MILLIS
                target = stabilizer.update(
                    recognized, viewSize.width / 2f, viewSize.height / 2f, tolerance, stickiness, now,
                )
            },
            onCameraError = onCameraError,
        )

        val highlight = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            if (picked == null) {
                target?.let { aimed ->
                    val boxes = if (mode == ScanMode.Word) aimed.wordBoxes else aimed.sentenceBoxes
                    val shift = glide.value - aimed.wordBoxes.first().topLeft
                    boxes.forEach { box ->
                        val drawn = box.offset(shift.x, shift.y)
                        fillBox(drawn, highlight.copy(alpha = 0.35f))
                        outlineBox(drawn, Color.White, 2.dp.toPx())
                    }
                }
            }
            drawCrosshair(locked = target != null)
        }

        ScanBottomPanel(
            target = target,
            mode = mode,
            noTextForAWhile = noTextForAWhile,
            refining = refining,
            zoomRatio = camera.zoomRatio,
            zoomRange = camera.zoomRange,
            onZoom = { camera.zoomToRatio(it) },
            onModeChange = { mode = it },
            onAdd = {
                val aimed = target
                if (aimed != null && !refining) {
                    val pickedMode = mode
                    refining = true
                    // 实时帧只有 720p，按下那一刻再拍一张高清照片重认，按文字找回同一句纠正错字；
                    // 拍不到或找不回就用实时结果，不让用户白等。
                    scope.launch {
                        val refined = camera.readStill()?.let { ScanRefine.refine(aimed, it) }
                        refining = false
                        picked = (refined ?: aimed) to pickedMode
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    picked?.let { (aimed, pickedMode) ->
        val close = {
            picked = null
            stabilizer.clear()
            target = null
        }
        when (pickedMode) {
            ScanMode.Word -> GlobalWordSheet(aimed.word, aimed.contextSentence, onDismiss = close)
            ScanMode.Sentence -> GlobalSentenceSheet(aimed.sentence, onDismiss = close)
        }
    }
}

@Composable
private fun ScanBottomPanel(
    target: ScanTarget?,
    mode: ScanMode,
    noTextForAWhile: Boolean,
    refining: Boolean,
    zoomRatio: Float,
    zoomRange: ClosedFloatingPointRange<Float>,
    onZoom: (Float) -> Unit,
    onModeChange: (ScanMode) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 实时识别结果。高度先占住，词出现、消失时整块面板不跟着上下跳。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                when {
                    target == null -> Text(
                        text = if (noTextForAWhile) "没认出字。靠近一点、拿稳，光线暗就开手电"
                        else "把准星对准一个英文词",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    mode == ScanMode.Word -> {
                        Text(target.word, style = MaterialTheme.typography.headlineSmall)
                        if (target.contextSentence.isNotBlank()) {
                            Text(
                                text = target.contextSentence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    else -> Text(
                        text = target.sentence,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SingleChoiceSegmentedButtonRow {
                ScanMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onModeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, ScanMode.entries.size),
                    ) {
                        Text(if (option == ScanMode.Word) "取词" else "取句")
                    }
                }
            }

            // 缩放条紧贴加号上方、与面板同宽：左右手拇指都在下方正中这一片活动。
            if (zoomRange.endInclusive > zoomRange.start) {
                ScanZoomStrip(ratio = zoomRatio, range = zoomRange, onRatio = onZoom)
            }

            FilledIconButton(
                onClick = onAdd,
                enabled = target != null && !refining,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                if (refining) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = if (mode == ScanMode.Word) "查这个词，看过再放进生词本"
                        else "讲这句话，看过再摘进表达",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Text(
                text = when {
                    refining -> "拿稳，正在拍清楚再认一遍"
                    mode == ScanMode.Word -> "按加号先看讲解，再决定放不放进生词本"
                    else -> "按加号先看翻译，再决定摘不摘进「表达」"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CameraPermissionPane(asked: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "扫一扫要用后置相机认书上、屏幕上的字。画面只在本机识别，不上传、不保存。",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("允许使用相机") }
        // 用户点过"拒绝且不再询问"后系统不会再弹窗，只能去设置里开。
        if (asked) {
            TextButton(onClick = onOpenSettings) { Text("没有弹窗？去系统设置里打开", color = Color.White) }
        }
    }
}

private val TextBox.topLeft get() = Offset(left, top)

private fun DrawScope.outlineBox(box: TextBox, color: Color, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(box.left, box.top),
        size = Size(box.right - box.left, box.bottom - box.top),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width),
    )
}

private fun DrawScope.fillBox(box: TextBox, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(box.left, box.top),
        size = Size(box.right - box.left, box.bottom - box.top),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
}

/**
 * 十字准星。中间留一个空：准星压在字上时不能把字本身挡住。
 * 先画一圈半透明黑底再画白线，白纸黑字和深色屏幕上都看得清。
 */
private fun DrawScope.drawCrosshair(locked: Boolean) {
    val c = center
    val gap = 6.dp.toPx()
    val arm = (if (locked) 16.dp else 12.dp).toPx()
    val segments = listOf(
        Offset(c.x - gap - arm, c.y) to Offset(c.x - gap, c.y),
        Offset(c.x + gap, c.y) to Offset(c.x + gap + arm, c.y),
        Offset(c.x, c.y - gap - arm) to Offset(c.x, c.y - gap),
        Offset(c.x, c.y + gap) to Offset(c.x, c.y + gap + arm),
    )
    segments.forEach { (from, to) -> drawLine(Color.Black.copy(alpha = 0.45f), from, to, strokeWidth = 4.dp.toPx()) }
    segments.forEach { (from, to) -> drawLine(Color.White, from, to, strokeWidth = 2.dp.toPx()) }
    drawCircle(Color.White, radius = 1.5.dp.toPx(), center = c)
}
