package io.custom.flashlight

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class BrightnessPolicyTest {
    private val controller = TorchController(ContextWrapper(null))

    @Test fun safetyLimitsClampManualInput() {
        val settings = FlashSettings(limitMin = true, limitMax = true)
        assertEquals(51, controller.clampBrightness(-100, 255, settings))
        assertEquals(204, controller.clampBrightness(Int.MAX_VALUE, 255, settings))
        assertEquals(128, controller.clampBrightness(128, 255, settings))
    }

    @Test fun gearsFollowEachDevicesRange() {
        val settings = FlashSettings(limitMin = false, limitMax = false)
        assertEquals(listOf(10, 50, 100), List(3) { controller.gearBrightness(it, 100, settings) })
        assertEquals(listOf(1, 1, 1), List(3) { controller.gearBrightness(it, 1, settings) })
    }

    @Test fun safetyLimitsAlsoApplyToGears() {
        val settings = FlashSettings(limitMin = true, limitMax = true)
        assertEquals(listOf(20, 50, 80), List(3) { controller.gearBrightness(it, 100, settings) })
    }

    @Test fun invalidDeviceMaximumNeverCreatesAnEmptyRange() {
        for (max in listOf(-10, 0, 1, 2)) {
            assertTrue(controller.clampBrightness(Int.MIN_VALUE, max, FlashSettings()) >= 1)
            assertTrue(controller.clampBrightness(Int.MAX_VALUE, max, FlashSettings()) >= 1)
        }
    }
}
