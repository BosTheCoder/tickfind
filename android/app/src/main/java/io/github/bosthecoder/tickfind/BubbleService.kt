package io.github.bosthecoder.tickfind

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** The optional floating bubble: a small round button at the screen edge that opens the search box. Drag it up and down,
 *  or across to the other edge; it tucks itself back against the nearest side. Long-press it for settings. */
class BubbleService : Service() {
    companion object {
        var running = false; private set
        fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java)) }
            catch (e: IllegalStateException) { Log.w("Tickfind", "Not allowed to start the bubble right now", e) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BubbleService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private lateinit var view: FrameLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var store: Store

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        if (!foreground() || !canOverlay(this)) { stopSelf(); return }
        running = true
        wm = getSystemService(WindowManager::class.java)
        val size = dp(48)
        view = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6262A3A.toInt()); setStroke(dp(1), 0x40FFFFFF) }
            alpha = 0.75f
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(TEXT) },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            y = store.bubbleY.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels * 0.4).toInt()
            x = edgeX(store.bubbleRight, size)
        }
        touch()
        wm.addView(view, lp)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        if (::view.isInitialized && view.isAttachedToWindow) wm.removeView(view)
        super.onDestroy()
    }

    private fun edgeX(right: Boolean, w: Int = lp.width) = if (right) resources.displayMetrics.widthPixels - w + dp(6) else -dp(6)

    @SuppressLint("ClickableViewAccessibility")
    private fun touch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val longPress = Runnable { if (!dragging) { open(MainActivity::class.java); dragging = true } }
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    v.alpha = 1f; v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) { dragging = true; v.removeCallbacks(longPress) }
                    if (dragging) {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, resources.displayMetrics.heightPixels - lp.height)
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress); v.alpha = 0.75f
                    if (!dragging && e.action == MotionEvent.ACTION_UP) open(SearchActivity::class.java)
                    else if (dragging) settle()
                }
            }
            true
        }
    }

    /** Glides back to whichever side edge is nearer and remembers the spot. */
    private fun settle() {
        val right = lp.x + lp.width / 2 > resources.displayMetrics.widthPixels / 2
        store.bubbleRight = right; store.bubbleY = lp.y
        ValueAnimator.ofInt(lp.x, edgeX(right)).apply {
            duration = 180
            addUpdateListener { lp.x = it.animatedValue as Int; if (view.isAttachedToWindow) wm.updateViewLayout(view, lp) }
            start()
        }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** The quiet "bubble is on" notification Android needs for a floating window; tapping it opens the search too. */
    private fun foreground(): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder("bubble", NotificationManagerCompat.IMPORTANCE_LOW).setName("Floating bubble").build())
        val search = PendingIntent.getActivity(this, 0, Intent(this, SearchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "bubble")
            .setSmallIcon(R.drawable.ic_search)
            .setContentTitle("Search TickTick")
            .setContentText("Tap to search. The Tickfind bubble is on.")
            .setContentIntent(search)
            .setOngoing(true)
            .build()
        return try {
            @Suppress("InlinedApi")
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            true
        } catch (e: IllegalStateException) {
            Log.w("Tickfind", "Android refused the foreground service", e)
            false
        }
    }
}
