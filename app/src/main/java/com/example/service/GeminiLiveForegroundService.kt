package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.GeminiLiveManager

class GeminiLiveForegroundService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameCapturedMs = 0L
    private var latestCapturedBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private var isForegroundStarted = false

    private val heartbeatFrameRunnable = object : Runnable {
        override fun run() {
            val handler = captureHandler ?: return
            if (mediaProjection == null) return
            val now = System.currentTimeMillis()
            if (now - lastFrameCapturedMs >= 1300L) {
                val cached = synchronized(bitmapLock) {
                    latestCapturedBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (cached != null) {
                    lastFrameCapturedMs = now
                    GeminiLiveManager.sendScreenFrame(cached, sourceLabel = "MediaProjection")
                    cached.recycle()
                }
            }
            handler.postDelayed(this, 1300L)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system or user")
            stopScreenCaptureOnly()
            updateNotification(isScreenSharing = false)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val targetSize = scaleTargetSize(width, height)
            setupVirtualDisplayAndReader(targetSize.first, targetSize.second)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()

        when (intent?.action) {
            ACTION_START_VOICE -> {
                promoteForeground(includeMediaProjection = false, isScreenSharing = false)
                val voice = intent.getStringExtra(EXTRA_VOICE) ?: "AOEDE"
                val prompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)
                GeminiLiveManager.startLiveSession(
                    context = applicationContext,
                    voiceName = voice,
                    initialPrompt = prompt
                )
            }

            ACTION_START_SCREEN_SHARE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && dataIntent != null) {
                    promoteForeground(includeMediaProjection = true, isScreenSharing = true)
                    val voice = intent.getStringExtra(EXTRA_VOICE) ?: "AOEDE"
                    GeminiLiveManager.startLiveSession(
                        context = applicationContext,
                        voiceName = voice
                    )
                    startMediaProjectionCapture(resultCode, dataIntent)
                } else {
                    GeminiLiveManager.setScreenSharingActive(false)
                }
            }

            ACTION_STOP_SCREEN_SHARE -> {
                stopScreenCaptureOnly()
                promoteForeground(includeMediaProjection = false, isScreenSharing = false)
            }

            ACTION_STOP_ALL -> {
                stopScreenCaptureOnly()
                GeminiLiveManager.stopLiveSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenCaptureOnly()
        GeminiLiveManager.stopLiveSession()
    }

    private fun promoteForeground(includeMediaProjection: Boolean, isScreenSharing: Boolean) {
        val notification = buildNotification(isScreenSharing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
    }

    private fun updateNotification(isScreenSharing: Boolean) {
        if (!isForegroundStarted) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(isScreenSharing))
    }

    private fun startMediaProjectionCapture(resultCode: Int, data: Intent) {
        stopScreenCaptureOnly()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching {
            manager.getMediaProjection(resultCode, data)
        }.onFailure {
            Log.e(TAG, "Failed to obtain MediaProjection", it)
        }.getOrNull() ?: return

        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val thread = HandlerThread("KindGuideLiveCapture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val metrics = resources.displayMetrics
        val (w, h) = scaleTargetSize(metrics.widthPixels, metrics.heightPixels)
        setupVirtualDisplayAndReader(w, h)
        handler.postDelayed(heartbeatFrameRunnable, 1300L)
        GeminiLiveManager.setScreenSharingActive(true)
    }

    private fun setupVirtualDisplayAndReader(width: Int, height: Int) {
        val projection = mediaProjection ?: return
        val handler = captureHandler ?: return

        virtualDisplay?.release()
        imageReader?.close()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                if (planes.isEmpty()) return@setOnImageAvailableListener
                val plane = planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride

                val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                val cleanBitmap = if (paddedWidth != width) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, width, height).also {
                        rawBitmap.recycle()
                    }
                } else {
                    rawBitmap
                }

                synchronized(bitmapLock) {
                    latestCapturedBitmap?.recycle()
                    latestCapturedBitmap = cleanBitmap.copy(Bitmap.Config.ARGB_8888, false)
                }

                val now = System.currentTimeMillis()
                if (now - lastFrameCapturedMs >= 850L) {
                    lastFrameCapturedMs = now
                    GeminiLiveManager.sendScreenFrame(cleanBitmap, sourceLabel = "MediaProjection")
                }
                cleanBitmap.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Error capturing live screen frame", e)
            } finally {
                image.close()
            }
        }, handler)

        val dpi = resources.displayMetrics.densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "KindGuideLiveScreen",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun stopScreenCaptureOnly() {
        captureHandler?.removeCallbacks(heartbeatFrameRunnable)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { captureThread?.quitSafely() }
        captureThread = null
        captureHandler = null
        synchronized(bitmapLock) {
            latestCapturedBitmap?.recycle()
            latestCapturedBitmap = null
        }
        GeminiLiveManager.setScreenSharingActive(false)
    }

    private fun scaleTargetSize(width: Int, height: Int): Pair<Int, Int> {
        val maxDim = 768f
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val ratio = minOf(1f, maxDim / maxOf(safeW, safeH).toFloat())
        val w = ((safeW * ratio).toInt() / 2 * 2).coerceAtLeast(240)
        val h = ((safeH * ratio).toInt() / 2 * 2).coerceAtLeast(240)
        return w to h
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KindGuide Gemini Live",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps real-time voice and screen guidance active across apps."
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isScreenSharing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_LIVE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GeminiLiveForegroundService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isScreenSharing) {
            "Gemini Live is listening & watching your screen — speak anytime to interrupt."
        } else {
            "Gemini Live voice assistant is active — speak anytime."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KindGuide Live Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "End Live", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "GeminiLiveFGS"
        private const val CHANNEL_ID = "kindguide_gemini_live_channel"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_START_VOICE = "com.example.action.START_LIVE_VOICE"
        const val ACTION_START_SCREEN_SHARE = "com.example.action.START_LIVE_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "com.example.action.STOP_LIVE_SCREEN_SHARE"
        const val ACTION_STOP_ALL = "com.example.action.STOP_LIVE_ALL"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_VOICE = "extra_voice"
        const val EXTRA_INITIAL_PROMPT = "extra_initial_prompt"
        const val EXTRA_OPEN_LIVE = "extra_open_live"

        fun startVoiceOnly(context: Context, voiceName: String = "AOEDE", initialPrompt: String? = null) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_START_VOICE
                putExtra(EXTRA_VOICE, voiceName)
                if (!initialPrompt.isNullOrBlank()) putExtra(EXTRA_INITIAL_PROMPT, initialPrompt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWithScreenShare(
            context: Context,
            resultCode: Int,
            dataIntent: Intent,
            voiceName: String = "AOEDE"
        ) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_START_SCREEN_SHARE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, dataIntent)
                putExtra(EXTRA_VOICE, voiceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopScreenShare(context: Context) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP_SCREEN_SHARE
            }
            runCatching { context.startService(intent) }
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            runCatching { context.startService(intent) }
        }
    }
}
