package com.reverseassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        @Volatile var onScreenshot: ((ByteArray?) -> Unit)? = null
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 72
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var captured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reverse Assistant")
            .setContentText("Capturing the screen you approved…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
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
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source ->
                val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!captured) {
                        val plane = frame.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowPadding = plane.rowStride - pixelStride * width
                        val padded = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                        padded.recycle()
                        bitmap.recycle()
                        captured = true
                        finishCapture(output.toByteArray())
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
            handler.postDelayed({
                if (!captured) finishCapture(null)
            }, 6000)
        } catch (_: Exception) {
            finishCapture(null)
        }
        return START_NOT_STICKY
    }

    private fun finishCapture(bytes: ByteArray?) {
        if (bytes == null && captured) return
        onScreenshot?.invoke(bytes)
        onScreenshot = null
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
        try { projection?.stop() } catch (_: Exception) { }
        display = null
        reader = null
        projection = null
        super.onDestroy()
    }
}
