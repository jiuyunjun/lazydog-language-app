package com.lazydog.english.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lazydog.english.domain.vocabulary.ScannedBlock
import com.lazydog.english.domain.vocabulary.ScannedLine
import com.lazydog.english.domain.vocabulary.ScannedWord
import com.lazydog.english.domain.vocabulary.TextBox
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** 拍照 + 解码 + 识别的总时限。超时就当没拍到，调用方沿用实时结果，用户不至于干等。 */
private const val STILL_TIMEOUT_MILLIS = 2_500L

/** 缩放条的上限倍数。 */
private const val MAX_USEFUL_ZOOM = 8f

/**
 * 取景相机和识别器。由 [rememberLiveTextCameraState] 创建，交给 [LiveTextCamera] 显示；
 * 调用方拿着它可以随时 [readStill] 拍一张高清照片重认一遍。
 */
@Stable
class LiveTextCameraState internal constructor(
    internal val controller: LifecycleCameraController,
    internal val recognizer: TextRecognizer,
    internal val stillExecutor: ExecutorService,
) {
    /** 当前放大倍数。双指缩放和 [zoomToRatio] 都会更新它。 */
    var zoomRatio by mutableFloatStateOf(1f)
        private set

    /**
     * 可用的倍数范围，相机打开前是 1..1。下限取 1×（不切超广角，近处小字反而更糊），
     * 上限封顶 [MAX_USEFUL_ZOOM]——再往上是数码放大，字只是变大变糊，认字没好处。
     */
    var zoomRange by mutableStateOf(1f..1f)
        private set

    /** 缩放到某个倍数，超出 [zoomRange] 的会被夹回范围内。 */
    fun zoomToRatio(ratio: Float) {
        if (zoomRange.endInclusive <= zoomRange.start) return
        val clamped = ratio.coerceIn(zoomRange.start, zoomRange.endInclusive)
        zoomRatio = clamped
        controller.setZoomRatio(clamped)
    }

    internal fun onZoomState(state: ZoomState) {
        zoomRatio = state.zoomRatio
        zoomRange = max(1f, state.minZoomRatio)..min(MAX_USEFUL_ZOOM, state.maxZoomRatio)
    }

    /**
     * 拍一张全分辨率照片在本机认字。实时预览帧只有 720p，小字每个字母才几个像素，
     * 照片清楚得多。坐标是照片自己的，和预览视图对不上——调用方只用它的文字。
     * 拍不到、认不出、超时都返回 null。照片只在内存里过一遍，不落盘。
     */
    suspend fun readStill(): List<ScannedBlock>? = try {
        withTimeoutOrNull(STILL_TIMEOUT_MILLIS) {
            val (bitmap, rotation) = capture() ?: return@withTimeoutOrNull null
            suspendCancellableCoroutine { continuation ->
                try {
                    recognizer.process(InputImage.fromBitmap(bitmap, rotation))
                        .addOnSuccessListener { continuation.resume(it.toScannedBlocks()) }
                        .addOnFailureListener { continuation.resume(null) }
                        // 超时不代表 ML Kit 已停，等识别任务真正结束才释放像素。
                        .addOnCompleteListener { bitmap.recycle() }
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 相机未就绪或识别器已关闭也可能同步抛错，和异步失败一样退回实时结果。
        null
    }

    private suspend fun capture(): Pair<Bitmap, Int>? = suspendCancellableCoroutine { continuation ->
        controller.takePicture(
            stillExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // 在后台线程解码 JPEG，千万像素的图放主线程会卡一下。
                    val decoded = try {
                        image.toBitmap() to image.imageInfo.rotationDegrees
                    } catch (_: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    continuation.resume(decoded)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resume(null)
                }
            },
        )
    }
}

@Composable
fun rememberLiveTextCameraState(): LiveTextCameraState {
    val context = LocalContext.current
    val state = remember { createState(context) }
    DisposableEffect(state) {
        onDispose {
            state.recognizer.close()
            state.stillExecutor.shutdown()
        }
    }
    return state
}

private fun createState(context: Context): LiveTextCameraState {
    val controller = LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
        // 默认的分析分辨率是 480p，书页上的小字认不出来；720p 是识别率和发热之间的折中。
        imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        // 按加号时重认用的照片：800 万像素左右够认清小字，再大解码和识别都慢，等待时间翻倍。
        imageCaptureResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    }
    return LiveTextCameraState(
        controller = controller,
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        stillExecutor = Executors.newSingleThreadExecutor(),
    )
}

/**
 * 后置相机取景 + 端上实时文字识别。
 *
 * 识别结果直接换算成**这个预览视图的像素坐标**（`COORDINATE_SYSTEM_VIEW_REFERENCED`），
 * 调用方在同样大小的叠层上画框、拿视图中心当准星就行，不用自己处理旋转、裁切和缩放。
 *
 * 识别全在本机做（ML Kit 拉丁文字模型打包在 APK 里），不联网、不存图。
 * 相机和识别器的生命周期跟着 [state] 走：离开页面就解绑相机、关掉识别器，不挂到全局。
 *
 * [paused] 时停掉识别、保留预览——查词面板打开的那段时间不用再烧电认字。
 */
@Composable
fun LiveTextCamera(
    state: LiveTextCameraState,
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
    val controller = state.controller
    val recognizer = state.recognizer

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

    // 双指缩放是 PreviewView 自己做的，缩放条要跟着它走，只能听相机报上来的倍数。
    DisposableEffect(controller, lifecycleOwner) {
        val observer = Observer<ZoomState> { state.onZoomState(it) }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
    }

    DisposableEffect(controller, paused) {
        var active = !paused
        if (!paused) {
            val executor = ContextCompat.getMainExecutor(context)
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(recognizer),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    executor,
                ) { result ->
                    // 失败也推进丢失计时；暂停前在途的回调不能再更新目标。
                    if (active) latestOnText(result.getValue(recognizer)?.toScannedBlocks().orEmpty())
                },
            )
        }
        onDispose {
            active = false
            controller.clearImageAnalysisAnalyzer()
        }
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
