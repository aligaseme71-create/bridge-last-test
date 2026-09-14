package com.bridge.app

/**
 * One proxy server parsed from a subscription.
 *
 * @param latency verified proxy latency in ms, or -1 if not yet tested / unreachable.
 */
data class ServerProfile(
    val name: String,
    val uri: String,
    val protocol: String,
    val latency: Long = -1L
)
