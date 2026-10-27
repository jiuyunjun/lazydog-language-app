package com.lazydog.english.core.ocr

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lazydog.english.domain.vocabulary.ScannedBlock
import com.lazydog.english.domain.vocabulary.ScannedLine
import com.lazydog.english.domain.vocabulary.ScannedWord
import com.lazydog.english.domain.vocabulary.TextBox

/**
 * 后置相机取景 + 端上实时文字识别。
 *
 * 识别结果直接换算成**这个预览视图的像素坐标**（`COORDINATE_SYSTEM_VIEW_REFERENCED`），
 * 调用方在同样大小的叠层上画框、拿视图中心当准星就行，不用自己处理旋转、裁切和缩放。
 *
 * 识别全在本机做（ML Kit 拉丁文字模型打包在 APK 里），不联网、不存图。
 * 相机和识别器的生命周期跟着这个组件走：离开页面就解绑相机、关掉识别器，不挂到全局。
 *
 * [paused] 时停掉识别、保留预览——查词面板打开的那段时间不用再烧电认字。
 */
@Composable
fun LiveTextCamera(
    modifier: Modifier = Modifier,
    paused: Boolean,
    torchOn: Boolean,
    onText: (List<ScannedBlock>) -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val latestOnError by rememberUpdatedState(onCameraError)
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // 默认的分析分辨率是 480p，书页上的小字认不出来；720p 是识别率和发热之间的折中。
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        }
    }

    DisposableEffect(controller, lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (_: Exception) {
            // 没有后置相机，或者相机正被别的 App 占着。
            latestOnError()
        }
        controller.initializationFuture.addListener({
            try {
                controller.initializationFuture.get()
            } catch (_: Exception) {
                latestOnError()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer.close() }
    }

    DisposableEffect(controller, paused) {
        if (!paused) {
            val executor = ContextCompat.getMainExecutor(context)
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(recognizer),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    executor,
                ) { result ->
                    // 这一帧认失败（模型还在加载、画面糊了）就跳过，下一帧再来。
                    result.getValue(recognizer)?.let { latestOnText(it.toScannedBlocks()) }
                },
            )
        }
        onDispose { controller.clearImageAnalysisAnalyzer() }
    }

    LaunchedEffect(controller, torchOn) {
        // 没有闪光灯的机器这里会静默失败，不影响取景。
        controller.enableTorch(torchOn)
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // 点按对焦、双指缩放由 PreviewView 自带，凑近看小字时用得上。
                this.controller = controller
            }
        },
    )
}

private fun Text.toScannedBlocks(): List<ScannedBlock> = textBlocks.map { block ->
    ScannedBlock(
        block.lines.map { line ->
            ScannedLine(
                line.elements.mapNotNull { element ->
                    element.boundingBox?.let { box ->
                        ScannedWord(
                            text = element.text,
                            box = TextBox(
                                box.left.toFloat(),
                                box.top.toFloat(),
                                box.right.toFloat(),
                                box.bottom.toFloat(),
                            ),
                        )
                    }
                },
            )
        },
    )
}
