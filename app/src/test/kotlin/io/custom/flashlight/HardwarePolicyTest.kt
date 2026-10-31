package io.custom.flashlight

import org.junit.Assert.*
import org.junit.Test

class HardwarePolicyTest {
    @Test fun sparseLedIndicesDoNotCreatePhantomLights() {
        val nodes = SysfsNodes.controls(listOf("led:torch_0", "led:torch_3", "led:switch_0", "led:switch_1")) { 255 }
        assertEquals(listOf(0, 3), nodes.map { it.index })
        assertEquals("/sys/class/leds/led:switch_1/brightness", nodes[1].switchPath)
    }

    @Test fun missingSwitchIsNotReplacedWithAnUnrelatedOne() {
        val node = SysfsNodes.controls(listOf("led:torch_4", "led:switch_0")) { 255 }.single()
        assertNull(node.switchPath)
    }

    @Test fun directBrightnessNodeIsOnlyWrittenOnce() {
        val node = SysfsNodes.controls(listOf("torch")) { 255 }.single()
        val device = FlashDevice("torch", "torch", FlashDeviceKind.Sysfs,
            sysfsPath = node.torchPath, sysfsSwitchPath = node.switchPath, sysfsTorchPath = node.torchPath)
        assertEquals(listOf("echo 42 > '/sys/class/leds/torch/brightness'"), SysfsNodes.commands(device, true, 42, false))
        assertEquals(listOf("echo 0 > '/sys/class/leds/torch/brightness'"), SysfsNodes.commands(device, false, 0, false))
    }

    @Test fun separateSwitchKeepsDynamicCommandOrder() {
        val device = FlashDevice("led", "led", FlashDeviceKind.Sysfs, sysfsTorchPath = "/torch", sysfsSwitchPath = "/switch")
        assertEquals(listOf("echo 0 > '/switch'", "echo 60 > '/torch'", "echo 127 > '/switch'"),
            SysfsNodes.commands(device, true, 60, true))
    }

    @Test fun switchOnlyNodeDoesNotAdvertiseAdjustableBrightness() {
        val node = SysfsNodes.controls(listOf("led:switch_2")) { error("Must not read absent torch") }.single()
        assertNull(node.torchPath)
        val device = FlashDevice("switch", "switch", FlashDeviceKind.Sysfs, sysfsPath = node.switchPath, sysfsSwitchPath = node.switchPath)
        assertFalse(device.supportsBrightness)
    }

    @Test fun tileStartsAtLowAfterItHasBeenTurnedOff() {
        assertEquals(0, TileBrightnessPolicy.nextGear(true, false, 2))
        assertEquals(1, TileBrightnessPolicy.nextGear(true, true, 0))
        assertEquals(2, TileBrightnessPolicy.nextGear(true, true, 1))
        assertEquals(-1, TileBrightnessPolicy.nextGear(true, true, 2))
        assertEquals(-1, TileBrightnessPolicy.nextGear(false, true, 0))
        assertEquals(2, TileBrightnessPolicy.nextGear(false, false, -1))
    }

    @Test fun shellFailuresAreNotHiddenByFollowingCommands() {
        val result = ShellRunner.run(listOf("sh"), "set -e\nfalse\nprintf success\n", 2000)
        assertNotEquals(0, result.exitCode)
        assertFalse(result.output.contains("success"))
    }

    @Test fun shellOutputCanExceedPipeCapacityWithoutDeadlocking() {
        val result = ShellRunner.run(listOf("sh", "-c", "head -c 100000 /dev/zero"), "", 3000)
        assertEquals(0, result.exitCode)
        assertEquals(65_536, result.output.length)
    }

    @Test fun stalledCommandsTimeOut() {
        val start = System.nanoTime()
        val result = ShellRunner.run(listOf("sh", "-c", "exec sleep 5"), "", 100)
        assertEquals(-1, result.exitCode)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }
}
