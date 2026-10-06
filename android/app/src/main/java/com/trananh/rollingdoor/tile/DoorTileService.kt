package com.trananh.rollingdoor.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.trananh.rollingdoor.R

// Quick Settings tile that only opens the app, like a shortcut. It never sends a command: the
// control screen connects on its own once it is visible.
class DoorTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openApp() } else openApp()
    }

    override fun onTileAdded() = QuickTile.setAdded(this, true)

    override fun onTileRemoved() = QuickTile.setAdded(this, false)

    // Same intent as the launcher icon, so an app already in the background comes to the front.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

// Whether the tile is in Quick Settings, and the system prompt that adds it (Android 13+).
// Android has no query for it, so the tile records added/removed; a request answered with
// "already added" corrects a stale value, e.g. after the app data was cleared.
object QuickTile {
    private const val PREFS = "quick_tile"
    private const val KEY_ADDED = "added"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAdded(context: Context): Boolean = prefs(context).getBoolean(KEY_ADDED, false)

    fun setAdded(context: Context, added: Boolean) = prefs(context).edit { putBoolean(KEY_ADDED, added) }

    // Shows the system "Add tile" prompt. Only works while the app is in the foreground.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAdd(context: Context) {
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return
        statusBar.requestAddTileService(
            ComponentName(context, DoorTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_tile_door),
            context.mainExecutor,
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> setAdded(context, true)
            }
        }
    }
}
