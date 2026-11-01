package com.mtajchert.flocket.core.ui.trust

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.session.CoreSession

/**
 * The shared trust flow: after the user confirmed the fingerprint of [error], pin it and tell the session to
 * retry. Only call this from a user-confirmed dialog.
 */
suspend fun HostTrustRepository.pinAndRetry(
    host: Host,
    error: AppError.HostKeyUnknown,
    session: CoreSession?,
    clock: Clock = Clock.System,
): AppResult<Unit> {
    val result = pin(PinnedHostKey(host.hostname, host.port, error.keyType, error.fingerprint, clock.nowMillis()))
    if (result is AppResult.Success) session?.retryNow()
    return result
}

/** Forget the pinned key (after the second confirmation) and retry so the user is asked to verify the new one. */
suspend fun HostTrustRepository.forgetAndRetry(
    host: Host,
    session: CoreSession?,
): AppResult<Unit> {
    val result = forget(host.hostname, host.port)
    if (result is AppResult.Success) session?.retryNow()
    return result
}
