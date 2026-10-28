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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.Role
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
import com.lazydog.english.core.ask.SuspendAsk
import com.lazydog.english.core.ocr.LiveTextCamera
import com.lazydog.english.core.ocr.rememberLiveTextCameraState
import com.lazydog.english.domain.vocabulary.ScanMode
import com.lazydog.english.domain.vocabulary.ScanIssue
import com.lazydog.english.domain.vocabulary.ScanQuality
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

/** 取景页上所有控件共用的深色半透明底：压在任何画面上白字都看得清，又不把画面整块盖死。 */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/**
 * 扫一扫：后置相机全屏取景，十字准星，实时认字。
 *
 * 准星对准一个词，底部结果行跟着显示这个词和它所在的那句话；按正下方的加号，
 * 取词模式打开查词面板、取句模式打开讲句面板——和双击查词、三击讲句是同一个面板，
 * 看过讲解再由用户按按钮决定要不要放进记录（单词进「单词」，句子进「表达」）。
 * 这一屏自己不写任何记录。
 *
 * 布局按相机 App 的习惯：画面铺满全屏，控件压在深色半透明底上；准星放在**没被控件挡住的那块画面**
 * 的正中，而不是整屏正中——底部控件一高，整屏正中就落到控件边上去了。
 * 常用的加号、手电、取词/取句都在底部一行，单手拇指够得着；顶栏只留返回。
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

    var cameraError by remember { mutableStateOf(false) }
    var topBarHeight by remember { mutableIntStateOf(0) }

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
            else -> ScanViewfinder(topInset = topBarHeight, onCameraError = { cameraError = true })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SCRIM)
                .statusBarsPadding()
                .padding(horizontal = 4.dp)
                .onSizeChanged { topBarHeight = it.height },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回记录", tint = Color.White)
            }
            Text(text = "扫一扫", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

/** [topInset]：顶栏压住的画面高度（像素），准星要避开它居中。 */
@Composable
private fun ScanViewfinder(topInset: Int, onCameraError: () -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tolerance = with(density) { AIM_TOLERANCE.toPx() }
    val stickiness = with(density) { AIM_STICKINESS.toPx() }
    val stabilizer = remember { ScanStabilizer() }
    val camera = rememberLiveTextCameraState()
    val scope = rememberCoroutineScope()
    /** 按了加号、正在拍高清照片重认。这段时间加号转圈，不重复触发。 */
    var refining by remember { mutableStateOf(false) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var bottomInset by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<ScanTarget?>(null) }
    var lastTextAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var noTextForAWhile by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(ScanMode.Word) }
    /** 按下加号那一刻对准的目标。面板开着时识别暂停，不让后面的帧把它换掉。 */
    var picked by remember { mutableStateOf<Pair<ScanTarget, ScanMode>?>(null) }
    var needsReview by remember { mutableStateOf<Pair<ScanTarget, ScanMode>?>(null) }

    // 准星 = 上下控件之间那块露出来的画面的中心。识别坐标就是预览视图坐标，直接拿来比。
    val aim = Offset(
        viewSize.width / 2f,
        topInset + (viewSize.height - topInset - bottomInset).coerceAtLeast(0) / 2f,
    )

    // 对准一个新词时轻轻震一下：眼睛盯着书页时，不用低头看结果也知道锁上了。
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
            paused = picked != null || needsReview != null || refining,
            torchOn = torchOn,
            onText = { recognized ->
                val now = System.currentTimeMillis()
                if (recognized.isNotEmpty()) lastTextAt = now
                noTextForAWhile = recognized.isEmpty() && now - lastTextAt > NO_TEXT_HINT_MILLIS
                target = stabilizer.update(recognized, aim.x, aim.y, tolerance, stickiness, now)
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
            drawCrosshair(aim, locked = target != null)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomInset = it.height },
        ) {
            // 缩放档位浮在画面上、紧挨控件上沿，不另占一块底色。
            val range = camera.zoomRange
            if (range.endInclusive > range.start) {
                ScanZoomStrip(
                    ratio = camera.zoomRatio,
                    range = range,
                    onRatio = { camera.zoomToRatio(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ScanControls(
                target = target,
                mode = mode,
                noTextForAWhile = noTextForAWhile,
                refining = refining,
                torchOn = torchOn,
                onTorchChange = { torchOn = it },
                onModeChange = { mode = it },
                onAdd = {
                    val aimed = target
                    if (aimed != null && !refining) {
                        val pickedMode = mode
                        refining = true
                        // 实时帧只有 720p，按下那一刻再拍一张高清照片重认，按文字找回同一句纠正错字；
                        // 拍不到或找不回就用实时结果，不让用户白等。
                        scope.launch {
                            try {
                                val refined = camera.readStill()?.let { ScanRefine.refine(aimed, it) }
                                val result = refined ?: aimed
                                if (ScanQuality.issue(result, pickedMode) == null) {
                                    picked = result to pickedMode
                                } else {
                                    needsReview = result to pickedMode
                                }
                            } finally {
                                refining = false
                            }
                        }
                    }
                },
            )
        }
    }

    needsReview?.let { (aimed, pickedMode) ->
        ScanReviewDialog(
            target = aimed,
            mode = pickedMode,
            onRescan = {
                needsReview = null
                stabilizer.clear()
                target = null
            },
            onConfirm = { corrected ->
                needsReview = null
                picked = corrected to pickedMode
            },
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

/** 可疑的识别原文只在可编辑输入框中展示，确认前不请求讲解或保存。 */
@Composable
private fun ScanReviewDialog(target: ScanTarget, mode: ScanMode, onRescan: () -> Unit, onConfirm: (ScanTarget) -> Unit) {
    SuspendAsk("正在校对相机识别原文")
    var input by remember(target, mode) { mutableStateOf(if (mode == ScanMode.Word) target.word else target.sentence) }
    val corrected = ScanQuality.corrected(target, mode, input)
    val issue = ScanQuality.issue(target, mode)
    AlertDialog(
        onDismissRequest = onRescan,
        title = { Text("没看清，请核对原文") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (issue) {
                        ScanIssue.Unsteady -> "画面还没稳定。可以重新对准，或核对下面的原文。"
                        ScanIssue.LowConfidence -> "文字比较模糊。请靠近、拿稳后重扫，或校正原文。"
                        else -> "可能混入了中文、代码或乱码。请只保留要学习的英文，确认前不会发送给讲解服务。"
                    },
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        // 原始识别可能已超长，仍允许逐字删除，不能让长度上限锁死校对框。
                        if (it.length <= ScanQuality.MAX_SENTENCE_LENGTH || it.length < input.length) input = it
                    },
                    label = { Text("核对或修改原文") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    isError = corrected == null,
                    supportingText = { if (corrected == null) Text("请填写英文原文，取句至少两个词；去掉乱码和代码。") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = corrected != null, onClick = { corrected?.let(onConfirm) }) { Text("已核对，继续") }
        },
        dismissButton = { TextButton(onClick = onRescan) { Text("重新扫描") } },
    )
}

/**
 * 底部控件，两层：一行实时结果，一行操作。
 *
 * 操作行照相机 App 排：快门式加号居中（左右手拇指够到的距离一样），
 * 左边取词/取句，右边手电。说明文字不再单占一行——加号的作用写在读屏描述里，
 * 重认时的"拿稳"直接出现在结果行。
 */
@Composable
private fun ScanControls(
    target: ScanTarget?,
    mode: ScanMode,
    noTextForAWhile: Boolean,
    refining: Boolean,
    torchOn: Boolean,
    onTorchChange: (Boolean) -> Unit,
    onModeChange: (ScanMode) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SCRIM)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 实时结果。高度先占住，词出现、消失时控件不跟着上下跳，准星也就不跟着挪。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            val secondary = Color.White.copy(alpha = 0.72f)
            when {
                target == null -> Text(
                    text = if (noTextForAWhile) "没认出字。靠近一点、拿稳，光线暗就开手电"
                    else "把准星对准一个英文词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    textAlign = TextAlign.Center,
                )
                mode == ScanMode.Word -> {
                    Text(target.word, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    val sentence = target.contextSentence
                    if (refining || sentence.isNotBlank()) {
                        Text(
                            text = if (refining) "拿稳，正在拍清楚再认一遍" else sentence,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    Text(
                        text = target.sentence,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (refining) {
                        Text(
                            text = "拿稳，正在拍清楚再认一遍",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(76.dp)) {
            ScanModeSwitch(mode = mode, onModeChange = onModeChange, modifier = Modifier.align(Alignment.CenterStart))
            ShutterButton(
                enabled = target != null && !refining,
                refining = refining,
                description = if (mode == ScanMode.Word) "查这个词，看过讲解再决定放不放进生词本"
                else "讲这句话，看过翻译再决定摘不摘进「表达」",
                onClick = onAdd,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = { onTorchChange(!torchOn) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (torchOn) Color.White else Color.White.copy(alpha = 0.16f)),
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    contentDescription = if (torchOn) "关手电" else "开手电",
                    tint = if (torchOn) Color.Black else Color.White,
                )
            }
        }
    }
}

/** 取词 / 取句：一颗胶囊里两格，选中的那格白底黑字。 */
@Composable
private fun ScanModeSwitch(mode: ScanMode, onModeChange: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .selectableGroup(),
    ) {
        ScanMode.entries.forEach { option ->
            val selected = mode == option
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onModeChange(option) })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (option == ScanMode.Word) "取词" else "取句",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.Black else Color.White,
                )
            }
        }
    }
}

/** 快门式加号：白色外圈 + 主色实心圆。不可按时整体变淡。 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    refining: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(3.dp, Color.White.copy(alpha = if (enabled || refining) 0.95f else 0.4f), CircleShape)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.fillMaxSize(),
            colors = IconButtonDefaults.filledIconButtonColors(
                disabledContainerColor = Color.White.copy(alpha = 0.2f),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) {
            if (refining) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = Color.White)
            } else {
                Icon(Icons.Outlined.Add, contentDescription = description, modifier = Modifier.size(32.dp))
            }
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
 * 十字准星，画在 [c]。中间留一个空：准星压在字上时不能把字本身挡住。
 * 先画一圈半透明黑底再画白线，白纸黑字和深色屏幕上都看得清。
 */
private fun DrawScope.drawCrosshair(c: Offset, locked: Boolean) {
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
