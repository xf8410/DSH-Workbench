package io.github.jackwener.dsh.pet

import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings switch for the desktop pet: tap turns the overlay on or off. */
class PetTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val context: Context = applicationContext
        if (!PetOverlay.canShow(context)) {
            PetOverlay.openPermission(context)
            return
        }
        PetOverlay.toggle(context)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (PetOverlay.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "东海帝皇"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_menu_gallery)
        tile.updateTile()
    }
}
