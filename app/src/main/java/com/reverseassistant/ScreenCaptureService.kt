package com.reverseassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** One-shot, OS-consented screenshot capture. It never runs as a background monitor. */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_CONTINUOUS = "projection_continuous"
        const val ACTION_STOP = "com.reverseassistant.STOP_SCREEN_SHARE"
        @Volatile var onScreenshot: ((ByteArray?) -> Unit)? = null
        @Volatile var onFrame: ((ByteArray) -> Unit)? = null
        @Volatile var onStopped: (() -> Unit)? = null
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 72
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var captured = false
    private var finished = false
    private var continuous = false
    private var lastFrameAt = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!finished) finishCapture(null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onFrame = null
            onScreenshot = null
            stopSelf()
            return START_NOT_STICKY
        }
        continuous = intent?.getBooleanExtra(EXTRA_CONTINUOUS, false) == true
        createNotificationChannel()
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 73, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reverse Assistant")
            .setContentText(if (continuous) "Live screen sharing is active. Tap Stop to end." else "Capturing the screen you approved…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopPendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            finishCapture(null)
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, handler)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source ->
                val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!captured || (continuous && now - lastFrameAt >= 1000L)) {
                        val plane = frame.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowPadding = plane.rowStride - pixelStride * width
                        val padded = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                        val output = ByteArrayOutputStream()
                        val maxDimension = maxOf(bitmap.width, bitmap.height)
                        val streamBitmap = if (continuous && maxDimension > 768) {
                            val scale = 768f / maxDimension.toFloat()
                            Bitmap.createScaledBitmap(
                                bitmap,
                                (bitmap.width * scale).toInt().coerceAtLeast(1),
                                (bitmap.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                        } else bitmap
                        streamBitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
                        if (streamBitmap !== bitmap) streamBitmap.recycle()
                        padded.recycle()
                        bitmap.recycle()
                        captured = true
                        lastFrameAt = now
                        val bytes = output.toByteArray()
                        if (continuous) onFrame?.invoke(bytes) else finishCapture(bytes)
                    }
                } catch (_: Exception) {
                    finishCapture(null)
                } finally {
                    frame.close()
                }
            }, handler)
            display = projection?.createVirtualDisplay(
                "ReverseAssistantCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, handler
            )
            if (!continuous) {
                handler.postDelayed({
                    if (!captured) finishCapture(null)
                }, 6000)
            }
        } catch (_: Exception) {
            finishCapture(null)
        }
        return START_NOT_STICKY
    }

    private fun finishCapture(bytes: ByteArray?) {
        if (finished) return
        finished = true
        onScreenshot?.invoke(bytes)
        onScreenshot = null
        onFrame = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try { display?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        display = null
        reader = null
        projection = null
        onFrame = null
        onScreenshot = null
        onStopped?.invoke()
        onStopped = null
        super.onDestroy()
    }
}
