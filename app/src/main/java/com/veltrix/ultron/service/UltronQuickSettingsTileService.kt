package com.veltrix.ultron.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.veltrix.ultron.MainActivity
import com.veltrix.ultron.R

/**
 * User-invoked silent entry point from Android Quick Settings.
 *
 * This tile never executes a mission directly. It only opens ULTRON Chat; all later
 * execution still passes through the normal planner, permission and verification gates.
 * If the device is locked, Android remains authoritative through unlockAndRun().
 */
class UltronQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.quick_settings_tile_label)
            state = Tile.STATE_ACTIVE
            contentDescription = getString(R.string.quick_settings_tile_description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(R.string.quick_settings_tile_subtitle)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun { openUltronChat() }
    }

    private fun openUltronChat() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_INVOCATION_SOURCE, INVOCATION_QUICK_SETTINGS)
            .putExtra(MainActivity.EXTRA_INITIAL_PAGE, MainActivity.PAGE_CHAT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_CHAT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val INVOCATION_QUICK_SETTINGS = "quick_settings"
        const val REQUEST_OPEN_CHAT = 7013
    }
}
