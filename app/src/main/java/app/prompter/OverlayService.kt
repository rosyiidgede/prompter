package app.prompter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Wrapper that records raw screen coordinates, which do not shift when the window itself moves. */
private class RawFrame(context: Context, private val onRaw: (Float, Float) -> Unit) : android.widget.FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onRaw(ev.rawX, ev.rawY)
        return super.dispatchTouchEvent(ev)
    }
}

class OverlayService : Service() {

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        fun start() {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var view: android.view.View? = null
    private val owner = Owner()
    private var scale by mutableFloatStateOf(0.92f)
    private var rawX = 0f
    private var rawY = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (view == null) showOverlay()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Prompter overlay", NotificationManager.IMPORTANCE_LOW),
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Prompter is running")
            .setContentText("Tap to open, or stop the overlay")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun showOverlay() {
        PrompterStore.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenW = resources.displayMetrics.widthPixels
        params = WindowManager.LayoutParams(
            (screenW * scale).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Let the window reach the camera cutout area so there is no gap next to the lens.
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = (screenW - width) / 2
            y = 0
        }

        owner.start()
        val v = RawFrame(this) { x, y -> rawX = x; rawY = y }
        v.setViewTreeLifecycleOwner(owner)
        v.setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(this)
        compose.setContent {
            OverlayContent(
                scale = scale,
                onDragStart = { lastX = rawX; lastY = rawY },
                onDragMove = {
                    val dx = rawX - lastX
                    val dy = rawY - lastY
                    lastX = rawX
                    lastY = rawY
                    moveBy(dx, dy)
                },
                onZoom = ::zoomBy,
                onSnap = ::snapToCamera,
                onBack = {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                },
                onClose = { stopSelf() },
            )
        }
        v.addView(compose)
        wm.addView(v, params)
        view = v
        v.post { snapToCamera() }
    }

    /** Top-centre cutout (camera hole), if the device reports one. */
    private fun cutoutRect(): android.graphics.Rect? {
        val screenW = resources.displayMetrics.widthPixels
        val rects: List<android.graphics.Rect>? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY).cutout?.boundingRects
            } else if (Build.VERSION.SDK_INT >= 28) {
                view?.rootWindowInsets?.displayCutout?.boundingRects
            } else null
        }.getOrNull()
        return rects?.filter { it.top <= 2 }?.minByOrNull { kotlin.math.abs(it.centerX() - screenW / 2) }
    }

    /** Centre the window on the camera and put its top edge right under it. */
    private fun snapToCamera() {
        val v = view ?: return
        val screenW = resources.displayMetrics.widthPixels
        val cut = cutoutRect()
        val cx = cut?.centerX() ?: (screenW / 2)
        params.x = (cx - params.width / 2).coerceIn(0, maxOf(0, screenW - params.width))
        params.y = cut?.bottom ?: 0
        wm.updateViewLayout(v, params)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val dm = resources.displayMetrics
        params.x = (params.x + dx.toInt()).coerceIn(0, maxOf(0, dm.widthPixels - params.width))
        params.y = (params.y + dy.toInt()).coerceIn(0, maxOf(0, dm.heightPixels - v.height))
        wm.updateViewLayout(v, params)
    }

    private fun zoomBy(zoom: Float) {
        val v = view ?: return
        scale = (scale * zoom).coerceIn(0.55f, 1f)
        val screenW = resources.displayMetrics.widthPixels
        params.width = (screenW * scale).toInt()
        params.x = params.x.coerceIn(0, maxOf(0, screenW - params.width))
        wm.updateViewLayout(v, params)
    }

    override fun onDestroy() {
        PrompterStore.persist(this)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.prompter.STOP"
        private const val CHANNEL = "overlay"
    }
}
