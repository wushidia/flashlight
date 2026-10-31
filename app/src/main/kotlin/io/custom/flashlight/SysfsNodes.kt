package io.custom.flashlight

internal data class SysfsControl(
    val index: Int,
    val switchPath: String?,
    val torchPath: String?,
    val maxBrightness: Int,
)

/** Both local and root discovery use the same parser; never invent missing LED indices. */
internal object SysfsNodes {
    private val torchPattern = Regex("^led:torch_(\\d+)$")
    private val switchPattern = Regex("^led:switch_(\\d+)$")

    fun controls(names: List<String>, maximum: (String) -> Int): List<SysfsControl> {
        val available = names.toSet()
        val torches = names.mapNotNull { torchPattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.distinct().sorted()
        if (torches.isNotEmpty()) return torches.map { index ->
            val torch = "/sys/class/leds/led:torch_$index/brightness"
            val switch = when {
                "led:switch_${index / 2}" in available -> "/sys/class/leds/led:switch_${index / 2}/brightness"
                "led:switch" in available -> "/sys/class/leds/led:switch/brightness"
                else -> null
            }
            SysfsControl(index, switch, torch, maximum(torch))
        }
        val fallback = listOf("flashlight", "torch-light", "torch", "flash").firstOrNull { it in available }
            ?: names.firstOrNull {
                val name = it.lowercase(java.util.Locale.ROOT)
                !name.startsWith("led:switch") && !name.startsWith("led:torch") && (name.contains("torch") || name.contains("flash"))
            }
        if (fallback != null) {
            val path = "/sys/class/leds/$fallback/brightness"
            return listOf(SysfsControl(-1, null, path, maximum(path)))
        }
        return names.filter { it == "led:switch" || switchPattern.matches(it) }.distinct().sorted().mapIndexed { index, name ->
            SysfsControl(index, "/sys/class/leds/$name/brightness", null, 1)
        }
    }

    fun commands(device: FlashDevice, enabled: Boolean, brightness: Int, dynamic: Boolean): List<String> {
        val switch = device.sysfsSwitchPath
        val torch = device.sysfsTorchPath ?: device.sysfsPath?.takeUnless { it == switch }
        return buildList {
            if (!enabled) {
                (switch ?: torch)?.let { add(write(0, it)) }
            } else {
                if (dynamic && switch != null && switch != torch) add(write(0, switch))
                torch?.let { add(write(brightness, it)) }
                if (switch != null && switch != torch) add(write(127, switch))
            }
        }
    }

    private fun write(value: Int, path: String) = "echo $value > ${shellQuote(path)}"
}

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
