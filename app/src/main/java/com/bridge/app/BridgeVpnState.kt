package com.bridge.app

/**
 * Global, thread-safe holder for the current VPN state.
 *
 * The UI polls these fields a few times per second. Keeping the state here
 * (instead of binding the Compose UI directly to the Service) keeps the two
 * sides decoupled and avoids the refresh/instability problems earlier versions
 * had.
 */
object BridgeVpnState {

    enum class Stage { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    @Volatile
    var stage: Stage = Stage.DISCONNECTED

    /** Human-readable status shown under the power button. */
    @Volatile
    var message: String = "Disconnected"

    /** Verified proxy latency in ms once connected, or -1 if unknown. */
    @Volatile
    var latencyMs: Long = -1L

    /** True only when real proxy traffic has been verified. */
    val connected: Boolean
        get() = stage == Stage.CONNECTED

    fun reset() {
        stage = Stage.DISCONNECTED
        message = "Disconnected"
        latencyMs = -1L
    }
}
