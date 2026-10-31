package io.custom.flashlight

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared app/tile state only changes after a successful hardware command. */
internal object TorchSession {
    val commandLock = Any()
    @Volatile var devices: List<FlashDevice> = emptyList()
    private val active = MutableStateFlow(emptySet<String>())
    val activeIds = active.asStateFlow()

    fun record(id: String, enabled: Boolean) = synchronized(commandLock) {
        active.value = if (enabled) active.value + id else active.value - id
    }
}
