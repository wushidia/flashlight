package io.custom.flashlight

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class FlashTileService : TileService() {
    private val controller by lazy { TorchController(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var busy = false
    @Volatile private var selectedId: String? = null

    override fun onStartListening() {
        super.onStartListening()
        selectedId = getSharedPreferences(PrefKeys.PREF, MODE_PRIVATE).getString(TILE_DEVICE, null)
        listening?.cancel()
        listening = scope.launch { TorchSession.activeIds.collect { updateTile() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        busy = true
        updateTile()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { applyClick() }
                if (!result.success) Toast.makeText(this@FlashTileService,
                    result.message ?: getString(R.string.open_failed), Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
                updateTile()
            }
        }
    }

    private fun applyClick(): TorchResult = synchronized(TorchSession.commandLock) {
        val devices = controller.discover()
        val settings = controller.loadSettings()
        val device = devices[settings.tileFlashIndex.coerceIn(devices.indices)]
        val pref = getSharedPreferences(PrefKeys.PREF, MODE_PRIVATE)
        selectedId = device.id
        pref.edit().putString(TILE_DEVICE, device.id).apply()
        val active = device.id in TorchSession.activeIds.value
        val gear = TileBrightnessPolicy.nextGear(settings.tileBrightness, active, pref.getInt(TILE_GEAR, -1))
        val enabled = gear >= 0
        if (enabled && !settings.multiFlash) {
            for (other in devices.filter { it.id != device.id && it.id in TorchSession.activeIds.value }) {
                val result = controller.setTorch(other, false, 0, settings)
                if (!result.success) return@synchronized result
            }
        }
        val result = controller.setTorch(device, enabled,
            if (enabled) controller.gearBrightness(gear, device.maxBrightness, settings) else 0, settings)
        if (result.success) pref.edit().putInt(TILE_GEAR, gear).apply()
        result
    }

    override fun onTileRemoved() {
        getSharedPreferences(PrefKeys.PREF, MODE_PRIVATE).edit().remove(TILE_GEAR).remove(TILE_DEVICE).apply()
        super.onTileRemoved()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            val devices = TorchSession.devices
            val selected = if (devices.isEmpty()) selectedId else devices[controller.loadSettings().tileFlashIndex.coerceIn(devices.indices)].id
            val active = selected in TorchSession.activeIds.value
            tile.state = if (busy) Tile.STATE_UNAVAILABLE else if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(if (active) R.string.on else R.string.off)
            tile.updateTile()
        }
    }

    private companion object {
        const val TILE_GEAR = "tile_brightness_gear"
        const val TILE_DEVICE = "tile_device_id"
    }
}

internal object TileBrightnessPolicy {
    fun nextGear(stepped: Boolean, active: Boolean, previous: Int): Int = when {
        !active -> if (stepped) 0 else 2
        !stepped -> -1
        previous !in -1..1 -> -1
        else -> previous + 1
    }
}
