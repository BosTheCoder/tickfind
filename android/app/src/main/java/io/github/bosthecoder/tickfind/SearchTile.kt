package io.github.bosthecoder.tickfind

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** The "Search TickTick" Quick Settings tile: swipe down, tap, type. */
class SearchTile : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val i = Intent(this, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

/** Brings the bubble back after a reboot or an in-app update, if it was on and still may draw over other apps. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val s = Store(ctx)
        val want = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> s.bubble
            Intent.ACTION_MY_PACKAGE_REPLACED -> s.sp.getBoolean("restartAfterUpdate", false).also { s.sp.edit().remove("restartAfterUpdate").apply() }
            else -> false
        }
        if (want && BubbleService.canOverlay(ctx)) BubbleService.start(ctx)
    }
}
