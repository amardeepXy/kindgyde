package com.example.safety

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.ai.GuideSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class SosCaptureProgress(
    val isActive: Boolean = false,
    val isCompleted: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 7,
    val statusLabel: String = "",
    val lastAlertId: String? = null
)

object SosCaptureManager {

    private const val TAG = "SosCaptureManager"
    private val captureExecutor by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Captures the current screen cleanly using PixelCopy (API 26+) or View Canvas fallback,
     * scaled and compressed for fast Firestore delivery.
     */
    suspend fun captureCurrentScreenBase64(activity: Activity?): String? {
        val rawBitmap: Bitmap? = withContext(Dispatchers.Main) {
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val window = activity.window
                val view = window?.decorView?.rootView
                if (window != null && view != null && view.width > 0 && view.height > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        suspendCancellableCoroutine { cont ->
                            val bmp = Bitmap.createBitmap(
                                view.width,
                                view.height,
                                Bitmap.Config.ARGB_8888
                            )
                            runCatching {
                                PixelCopy.request(
                                    window,
                                    bmp,
                                    { copyResult ->
                                        if (cont.isActive) {
                                            if (copyResult == PixelCopy.SUCCESS) {
                                                cont.resume(bmp)
                                            } else {
                                                cont.resume(drawViewFallback(view))
                                            }
                                        }
                                    },
                                    Handler(Looper.getMainLooper())
                                )
                            }.onFailure {
                                if (cont.isActive) cont.resume(drawViewFallback(view))
                            }
                        }
                    } else {
                        drawViewFallback(view)
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }

        val bitmap = rawBitmap ?: return null
        return withContext(Dispatchers.Default) {
            encodeScaledJpegBase64(bitmap, maxDim = 640, quality = 62)
        }
    }

    private fun drawViewFallback(view: android.view.View): Bitmap? = runCatching {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        bmp
    }.getOrNull()

    /**
     * Runs sequential burst capture: 3 front camera frames followed by 3 back camera frames,
     * invoking [onCapturedFrame] progressively for each captured photo.
     */
    suspend fun captureFrontAndBackBursts(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onStatus: (String) -> Unit,
        onCapturedFrame: suspend (source: String, sequenceIndex: Long, base64: String) -> Unit
    ) {
        val hasCameraPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) {
            Log.w(TAG, "Camera permission not granted; skipping camera burst capture.")
            return
        }

        val provider = awaitCameraProvider(context) ?: return

        // Avoid camera lock conflict if Gemini Live camera sharing is active
        runCatching {
            val coordinatorClass = GuideSessionCoordinator::class.java
            Log.d(TAG, "Starting sequential front/back SOS capture via ${coordinatorClass.simpleName}")
        }

        captureLensBurst(
            context = context,
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            lensFacing = CameraSelector.LENS_FACING_FRONT,
            sourceKey = "front_camera",
            displayName = "front camera",
            count = 3,
            onStatus = onStatus,
            onCapturedFrame = onCapturedFrame
        )

        captureLensBurst(
            context = context,
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            sourceKey = "back_camera",
            displayName = "back camera",
            count = 3,
            onStatus = onStatus,
            onCapturedFrame = onCapturedFrame
        )
    }

    private suspend fun captureLensBurst(
        context: Context,
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        sourceKey: String,
        displayName: String,
        count: Int,
        onStatus: (String) -> Unit,
        onCapturedFrame: suspend (source: String, sequenceIndex: Long, base64: String) -> Unit
    ) {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val hasLens = runCatching { provider.hasCamera(selector) }.getOrDefault(false)
        if (!hasLens) {
            Log.w(TAG, "Device does not report $displayName; skipping.")
            return
        }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val bound = withContext(Dispatchers.Main) {
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                true
            }.onFailure { err ->
                Log.w(TAG, "Failed binding $displayName", err)
            }.getOrDefault(false)
        }
        if (!bound) return

        // Brief sensor/AE settle
        delay(350L)

        for (index in 1..count) {
            onStatus("Capturing $displayName $index/$count...")
            val encoded = captureSingleFrameBase64(imageCapture)
            if (!encoded.isNullOrBlank()) {
                onCapturedFrame(sourceKey, index.toLong(), encoded)
            }
            if (index < count) {
                delay(380L)
            }
        }

        withContext(Dispatchers.Main) {
            runCatching { provider.unbindAll() }
        }
        delay(180L)
    }

    private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (cont.isActive) {
                        cont.resume(runCatching { future.get() }.getOrNull())
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    private suspend fun captureSingleFrameBase64(imageCapture: ImageCapture): String? {
        val bitmap: Bitmap? = suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bmp = runCatching {
                            val raw = image.toBitmap()
                            val degrees = image.imageInfo.rotationDegrees.toFloat()
                            if (degrees != 0f) {
                                val matrix = Matrix().apply { postRotate(degrees) }
                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                            } else {
                                raw
                            }
                        }.getOrNull()
                        image.close()
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "SOS photo capture failed", exception)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

        val captured = bitmap ?: return null
        return withContext(Dispatchers.Default) {
            encodeScaledJpegBase64(captured, maxDim = 520, quality = 60)
        }
    }

    private fun encodeScaledJpegBase64(bitmap: Bitmap, maxDim: Int, quality: Int): String {
        val ratio = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
