package com.veltrix.ultron.remote

/**
 * Restores a previously paired bridge when the Android app process is recreated.
 * Credential read/decrypt failures fail closed: the bridge stays stopped and the
 * user can replace the pairing from Control instead of crashing process startup.
 */
internal fun restoreRemoteBridgeLoopIfNeeded(
    configuredCheck: () -> Boolean,
    runningCheck: () -> Boolean,
    start: () -> Unit
): Boolean {
    val configured = runCatching(configuredCheck).getOrDefault(false)
    if (!configured || runningCheck()) return false
    start()
    return true
}
