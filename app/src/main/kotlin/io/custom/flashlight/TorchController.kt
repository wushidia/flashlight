package io.custom.flashlight

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

object PrefKeys {
    const val PREF = "pref"
    const val ADJ_USE_BTN = "adj_use_btn"
    const val LIMIT_MIN_BRIGHT = "limit_min_bright"
    const val LIMIT_MAX_BRIGHT = "limit_max_bright"
    const val DYN_BRIGHT_ADJ = "dyn_bright_adj"
    const val CTRL_MULTI_FLASH = "ctrl_multi_flash"
    const val SAVE_LAST_FLASH = "save_last_flash"
    const val SAVE_LAST_BRIGHT = "save_last_bright"
    const val OPEN_ON_START = "open_flashlight_on_start"
    const val TILE_BRIGHT_ADJ = "is_tile_bright_adj"
    const val TILE_FLASH_POS = "tile_flash_pos"
    const val LAST_FLASHLIGHT = "last_flashlight"
    const val LAST_BRIGHTNESS = "last_brightness"
    const val FLASHLIGHT_TIPS = "flashlight_tips"
}

data class FlashSettings(
    val useButtons: Boolean = true,
    val limitMin: Boolean = false,
    val limitMax: Boolean = true,
    val dynamicBrightness: Boolean = false,
    val multiFlash: Boolean = false,
    val saveLastFlash: Boolean = false,
    val saveLastBrightness: Boolean = false,
    val openOnStart: Boolean = false,
    val tileBrightness: Boolean = false,
    val tileFlashIndex: Int = 0,
)

enum class FlashDeviceKind {
    Camera,
    Sysfs,
}

data class FlashDevice(
    val id: String,
    val label: String,
    val kind: FlashDeviceKind,
    val cameraId: String? = null,
    val sysfsPath: String? = null,
    val sysfsSwitchPath: String? = null,
    val sysfsTorchPath: String? = null,
    val maxBrightness: Int = 255,
    val maxStrength: Int = 1,
) {
    val supportsBrightness: Boolean
        get() = sysfsTorchPath != null || (kind == FlashDeviceKind.Sysfs && sysfsSwitchPath == null) || maxStrength > 1
}

data class TorchResult(
    val success: Boolean,
    val message: String? = null,
    val usedRoot: Boolean = false,
)

class TorchController(private val context: Context) {
    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    var hasSuccessfulRootAccess: Boolean = false
        private set

    @Volatile
    var hasFailedRootAccess: Boolean = false
        private set

    fun loadSettings(): FlashSettings {
        val pref = context.getSharedPreferences(PrefKeys.PREF, Context.MODE_PRIVATE)
        return FlashSettings(
            useButtons = pref.getBoolean(PrefKeys.ADJ_USE_BTN, true),
            limitMin = pref.getBoolean(PrefKeys.LIMIT_MIN_BRIGHT, false),
            limitMax = pref.getBoolean(PrefKeys.LIMIT_MAX_BRIGHT, true),
            dynamicBrightness = pref.getBoolean(PrefKeys.DYN_BRIGHT_ADJ, false),
            multiFlash = pref.getBoolean(PrefKeys.CTRL_MULTI_FLASH, false),
            saveLastFlash = pref.getBoolean(PrefKeys.SAVE_LAST_FLASH, false),
            saveLastBrightness = pref.getBoolean(PrefKeys.SAVE_LAST_BRIGHT, false),
            openOnStart = pref.getBoolean(PrefKeys.OPEN_ON_START, false),
            tileBrightness = pref.getBoolean(PrefKeys.TILE_BRIGHT_ADJ, false),
            tileFlashIndex = pref.getInt(PrefKeys.TILE_FLASH_POS, 0),
        )
    }

    fun saveSettings(settings: FlashSettings) {
        context.getSharedPreferences(PrefKeys.PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(PrefKeys.ADJ_USE_BTN, settings.useButtons)
            .putBoolean(PrefKeys.LIMIT_MIN_BRIGHT, settings.limitMin)
            .putBoolean(PrefKeys.LIMIT_MAX_BRIGHT, settings.limitMax)
            .putBoolean(PrefKeys.DYN_BRIGHT_ADJ, settings.dynamicBrightness)
            .putBoolean(PrefKeys.CTRL_MULTI_FLASH, settings.multiFlash)
            .putBoolean(PrefKeys.SAVE_LAST_FLASH, settings.saveLastFlash)
            .putBoolean(PrefKeys.SAVE_LAST_BRIGHT, settings.saveLastBrightness)
            .putBoolean(PrefKeys.OPEN_ON_START, settings.openOnStart)
            .putBoolean(PrefKeys.TILE_BRIGHT_ADJ, settings.tileBrightness)
            .putInt(PrefKeys.TILE_FLASH_POS, settings.tileFlashIndex)
            .apply()
    }

    fun discover(): List<FlashDevice> {
        val sysfsControls = discoverOriginalSysfsControls()
        val devices = buildList {
            val cameraDevices = discoverCameraDevices(sysfsControls)
            addAll(cameraDevices)
            addAll(discoverSysfsDevices(sysfsControls.drop(cameraDevices.size)))
        }.distinctBy { "${it.kind}:${it.cameraId ?: it.sysfsPath}" }

        return devices.ifEmpty {
            listOf(
                FlashDevice(
                    id = "unknown",
                    label = context.getString(R.string.unknown),
                    kind = FlashDeviceKind.Camera,
                ),
            )
        }.also { TorchSession.devices = it }
    }

    fun setTorch(device: FlashDevice, enabled: Boolean, brightness: Int, settings: FlashSettings): TorchResult = synchronized(TorchSession.commandLock) {
        if (device.id == "unknown") {
            return TorchResult(false, context.getString(R.string.no_flashlight))
        }

        when (device.kind) {
            FlashDeviceKind.Camera -> {
                if (device.sysfsTorchPath != null || device.sysfsSwitchPath != null) {
                    setSysfsTorch(device, enabled, brightness, settings)
                } else {
                    setCameraTorch(device, enabled, brightness, settings)
                }
            }
            FlashDeviceKind.Sysfs -> setSysfsTorch(device, enabled, brightness, settings)
        }.also { result ->
            if (result.success) TorchSession.record(device.id, enabled)
        }
    }

    fun clampBrightness(value: Int, settings: FlashSettings): Int {
        return clampBrightness(value, 255, settings)
    }

    fun clampBrightness(value: Int, maxBrightness: Int, settings: FlashSettings): Int {
        val maxValue = maxBrightness.coerceAtLeast(1)
        val min = if (settings.limitMin) (maxValue * 0.2f).toInt().coerceAtLeast(1) else 1
        val max = if (settings.limitMax) (maxValue * 0.8f).toInt().coerceAtLeast(min) else maxValue
        return value.coerceIn(min, max)
    }

    fun gearBrightness(gear: Int, settings: FlashSettings): Int {
        return gearBrightness(gear, 255, settings)
    }

    fun gearBrightness(gear: Int, maxBrightness: Int, settings: FlashSettings): Int {
        val maxValue = maxBrightness.coerceAtLeast(1)
        val lowRatio = if (settings.limitMin) 0.2f else 0.1f
        val highRatio = if (settings.limitMax) 0.8f else 1.0f
        return when (gear) {
            0 -> (maxValue * lowRatio).toInt().coerceAtLeast(1)
            1 -> (maxValue * 0.5f).toInt().coerceAtLeast(1)
            else -> (maxValue * highRatio).toInt().coerceAtLeast(1)
        }
    }

    private fun setCameraTorch(
        device: FlashDevice,
        enabled: Boolean,
        brightness: Int,
        settings: FlashSettings,
    ): TorchResult {
        val manager = cameraManager ?: return TorchResult(false, context.getString(R.string.open_failed))
        val cameraId = device.cameraId ?: return TorchResult(false, context.getString(R.string.open_failed))
        return runCatching {
            if (!enabled) {
                manager.setTorchMode(cameraId, false)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && device.maxStrength > 1) {
                val clamped = clampBrightness(brightness, settings)
                val strength = ((clamped / 255f) * device.maxStrength).roundToInt().coerceIn(1, device.maxStrength)
                manager.turnOnTorchWithStrengthLevel(cameraId, strength)
            } else {
                manager.setTorchMode(cameraId, true)
            }
        }.fold(
            onSuccess = { TorchResult(true) },
            onFailure = { TorchResult(false, it.message ?: context.getString(R.string.open_failed)) },
        )
    }

    private fun setSysfsTorch(
        device: FlashDevice,
        enabled: Boolean,
        brightness: Int,
        settings: FlashSettings,
    ): TorchResult {
        val value = if (enabled) clampBrightness(brightness, device.maxBrightness, settings) else 0
        val commands = SysfsNodes.commands(device, enabled, value, settings.dynamicBrightness)
        if (commands.isEmpty()) return TorchResult(false, context.getString(R.string.open_failed))

        return runRootCommands(commands).let { result ->
            if (result.exitCode == 0) {
                TorchResult(true, usedRoot = true)
            } else {
                val message = result.output.ifBlank { context.getString(R.string.root_failed) }
                TorchResult(false, message, usedRoot = true)
            }
        }
    }

    private fun discoverCameraDevices(sysfsControls: List<SysfsControl>): List<FlashDevice> {
        val manager = cameraManager ?: return emptyList()
        val rawDevices = runCatching {
            manager.cameraIdList.mapNotNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) return@mapNotNull null
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val maxStrength =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    } else {
                        1
                    }
                RawCameraFlash(id, facing, maxStrength.coerceAtLeast(1))
            }
        }.getOrDefault(emptyList())

        val back = rawDevices.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val front = rawDevices.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
        val other = rawDevices.filter { it.facing != CameraCharacteristics.LENS_FACING_BACK && it.facing != CameraCharacteristics.LENS_FACING_FRONT }

        return buildList {
            back.forEachIndexed { index, raw ->
                add(raw.toDevice("camera:${raw.cameraId}", labelForBack(index, back.size), sysfsControls.getOrNull(size)))
            }
            front.forEachIndexed { index, raw ->
                add(raw.toDevice("camera:${raw.cameraId}", labelForFront(index, front.size), sysfsControls.getOrNull(size)))
            }
            other.forEachIndexed { index, raw ->
                add(raw.toDevice("camera:${raw.cameraId}", "${context.getString(R.string.flashlight)} ${index + 1}", sysfsControls.getOrNull(size)))
            }
        }
    }

    private fun discoverSysfsDevices(sysfsControls: List<SysfsControl>): List<FlashDevice> {
        return sysfsControls.mapIndexed { index, control ->
            val path = control.torchPath ?: control.switchPath
            FlashDevice(
                id = "sysfs:${control.index}:${path.orEmpty()}",
                label = "${context.getString(R.string.flashlight)} ${index + 1}",
                kind = FlashDeviceKind.Sysfs,
                sysfsPath = path,
                sysfsSwitchPath = control.switchPath,
                sysfsTorchPath = control.torchPath,
                maxBrightness = control.maxBrightness,
            )
        }
    }

    private fun discoverOriginalSysfsControls(): List<SysfsControl> {
        val maximums = mutableMapOf<String, Int>()
        fun parse(names: List<String>) = SysfsNodes.controls(names) { path ->
            maximums.getOrPut(path) { readMaxBrightness(path) }
        }
        val local = parse(runCatching { File("/sys/class/leds").listFiles()?.map { it.name }.orEmpty() }.getOrDefault(emptyList()))
        if (local.isNotEmpty()) return local
        val result = runRootCommands(listOf("ls /sys/class/leds 2>/dev/null"))
        if (result.exitCode != 0) return emptyList()
        return parse(result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList())
    }

    private fun labelForBack(index: Int, total: Int): String = when {
        total <= 1 -> context.getString(R.string.back_flashlight)
        index == 0 -> context.getString(R.string.back_main_flashlight)
        index == 1 -> context.getString(R.string.back_vice_flashlight)
        else -> "${context.getString(R.string.flashlight)} ${index + 1}"
    }

    private fun labelForFront(index: Int, total: Int): String = when {
        total <= 1 -> context.getString(R.string.front_flashlight)
        index == 0 -> context.getString(R.string.front_main_flashlight)
        index == 1 -> context.getString(R.string.front_vice_flashlight)
        else -> "${context.getString(R.string.flashlight)} ${index + 1}"
    }

    private fun readMaxBrightness(brightnessPath: String): Int {
        val maxPath = brightnessPath.replace("brightness", "max_brightness")
        val local = runCatching { File(maxPath).readText().trim().toIntOrNull() }.getOrNull()
        if (local != null && local > 0) return local
        val rooted = runRootCommands(listOf("cat ${shellQuote(maxPath)} 2>/dev/null"))
            .output
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.toIntOrNull()
        return rooted?.takeIf { it > 0 } ?: 255
    }

    private fun RawCameraFlash.toDevice(id: String, label: String, control: SysfsControl?): FlashDevice =
        FlashDevice(
            id = id,
            label = label,
            kind = FlashDeviceKind.Camera,
            cameraId = cameraId,
            sysfsPath = control?.torchPath ?: control?.switchPath,
            sysfsSwitchPath = control?.switchPath,
            sysfsTorchPath = control?.torchPath,
            maxBrightness = control?.maxBrightness ?: 255,
            maxStrength = maxStrength,
        )

    private data class RawCameraFlash(
        val cameraId: String,
        val facing: Int?,
        val maxStrength: Int,
    )

    private fun runRootCommands(commands: List<String>): ShellResult = synchronized(TorchSession.commandLock) {
        // A failure in an earlier write must not be hidden by a later successful command.
        val marker = "__FLASHLIGHT_ROOT_GRANTED__"
        val script = "printf '%s\\n' '$marker'\nset -e\n" + commands.joinToString("\n") + "\nexit\n"
        val result = ShellRunner.run(listOf("su"), script)
        if (result.output.contains(marker)) {
            hasSuccessfulRootAccess = true
            hasFailedRootAccess = false
        } else if (result.output != "Root command timed out") {
            hasFailedRootAccess = true
        }
        result.copy(output = result.output.substringAfter(marker, result.output).trim())
    }
}
