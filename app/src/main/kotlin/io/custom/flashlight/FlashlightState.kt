package io.custom.flashlight

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI scheduling and preferences; successful hardware state is shared with the tile. */
@Stable
class FlashlightState(private val context: Context, private val scope: CoroutineScope) {
    private val controller = TorchController(context.applicationContext)
    private val preferences = context.getSharedPreferences(PrefKeys.PREF, Context.MODE_PRIVATE)
    private val hardwareLock = Mutex()
    private var brightnessJob: Job? = null
    private var batteryRequested = false
    var settings by mutableStateOf(controller.loadSettings()); private set
    var devices by mutableStateOf(emptyList<FlashDevice>()); private set
    var selectedIndex by mutableIntStateOf(0); private set
    var brightness by mutableIntStateOf(1); private set
    var activeIds by mutableStateOf(TorchSession.activeIds.value); private set

    init {
        scope.launch { TorchSession.activeIds.collect { activeIds = it } }
    }
    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    val device get() = devices.getOrNull(selectedIndex)
    val isActive get() = device?.id in activeIds
    val maxBrightness get() = device?.maxBrightness ?: 255
    val gears get() = List(3) { controller.gearBrightness(it, maxBrightness, settings) }
    val minimum get() = controller.clampBrightness(1, maxBrightness, settings)
    val maximum get() = controller.clampBrightness(maxBrightness, maxBrightness, settings)

    suspend fun initialize(startup: Boolean = true) {
        val previousId = device?.id
        val previousBrightness = brightness
        devices = withContext(Dispatchers.IO) { controller.discover() }
        selectedIndex = (if (!startup) devices.indexOfFirst { it.id == previousId }.coerceAtLeast(0) else if (settings.saveLastFlash) preferences.getInt(PrefKeys.LAST_FLASHLIGHT, 0) else 0).coerceIn(0, devices.lastIndex)
        brightness = controller.clampBrightness(
            if (!startup) previousBrightness else if (settings.saveLastBrightness) preferences.getInt(PrefKeys.LAST_BRIGHTNESS, gears[2]) else gears[2],
            maxBrightness, settings,
        )
        loading = false
        if (startup && settings.openOnStart && !isActive) toggle()
    }

    fun updateSettings(value: FlashSettings) {
        settings = value
        controller.saveSettings(value)
        changeBrightness(brightness)
    }

    fun select(index: Int) {
        if (devices.isEmpty() || loading || busy) return
        selectedIndex = index.coerceIn(0, devices.lastIndex)
        brightness = controller.clampBrightness(brightness, maxBrightness, settings)
        if (settings.saveLastFlash) preferences.edit().putInt(PrefKeys.LAST_FLASHLIGHT, selectedIndex).apply()
    }

    fun changeBrightness(value: Int) {
        brightness = controller.clampBrightness(value, maxBrightness, settings)
        if (settings.saveLastBrightness) preferences.edit().putInt(PrefKeys.LAST_BRIGHTNESS, brightness).apply()
        brightnessJob?.cancel()
        val target = device ?: return
        if (target.id !in activeIds) return
        val next = brightness
        brightnessJob = scope.launch {
            delay(120)
            hardwareLock.withLock {
                val result = withContext(Dispatchers.IO) {
                    synchronized(TorchSession.commandLock) {
                        if (target.id in TorchSession.activeIds.value) controller.setTorch(target, true, next, settings) else null
                    }
                }
                result?.let(::handleResult)
            }
        }
    }

    fun toggle() {
        val target = device ?: return
        if (busy || loading) return
        busy = true
        brightnessJob?.cancel()
        scope.launch {
            try {
                hardwareLock.withLock {
                    val result = withContext(Dispatchers.IO) {
                        synchronized(TorchSession.commandLock) {
                            val enabled = target.id !in TorchSession.activeIds.value
                            if (enabled && !settings.multiFlash) {
                                for (old in devices.filter { it.id in TorchSession.activeIds.value }) {
                                    val closed = controller.setTorch(old, false, 0, settings)
                                    if (!closed.success) return@synchronized closed
                                }
                            }
                            controller.setTorch(target, enabled, brightness, settings)
                        }
                    }
                    activeIds = TorchSession.activeIds.value
                    handleResult(result)
                }
            } finally { busy = false }
        }
    }

    fun refresh() {
        if (loading || busy || activeIds.isNotEmpty()) return
        loading = true
        scope.launch {
            try { initialize(startup = false) } finally { loading = false }
        }
    }

    private fun handleResult(result: TorchResult) {
        if (!result.success) {
            message = if (result.usedRoot && controller.hasFailedRootAccess && !controller.hasSuccessfulRootAccess) context.getString(R.string.root_missing_message)
                else result.message ?: context.getString(R.string.open_failed)
        }
        if (result.success && result.usedRoot && !batteryRequested) {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!manager.isIgnoringBatteryOptimizations(context.packageName)) {
                batteryRequested = true
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
}
