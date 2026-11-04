package com.mtajchert.flocket.core.common.error

/**
 * The single error taxonomy of the app.
 *
 * Every public API of `core:session`, `core:data` and the repositories reports failure as one
 * of these cases (wrapped in [com.mtajchert.flocket.core.common.result.AppResult]). Lower layers
 * may throw internally, but convert to [AppError] at their public boundary.
 *
 * `core:ui` maps each case to a user-facing message and suggested action; screens never format
 * errors themselves. Adding a case is an architectural change: it needs a UI mapping and review.
 *
 * Never put secrets, key material or terminal content into an error.
 */
sealed interface AppError {
    // ---- Reaching the host -------------------------------------------------------------------

    /** DNS, TCP or SSH transport could not be established (host down, no route, refused). */
    data class Unreachable(
        val host: String,
        val detail: String? = null,
    ) : AppError

    /** An established connection dropped (network change, sleep, host reboot). */
    data object ConnectionLost : AppError

    /** An operation did not complete in time. Reads may be retried; writes must not be. */
    data class Timeout(
        val operation: String,
    ) : AppError

    // ---- SSH trust and authentication -------------------------------------------------------

    /** First contact: the user must confirm this fingerprint before any credential is sent. */
    data class HostKeyUnknown(
        val host: String,
        val keyType: String,
        val fingerprint: String,
    ) : AppError

    /** The host presented a different key than the pinned one. Hard stop; never auto-accept. */
    data class HostKeyChanged(
        val host: String,
        val keyType: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : AppError

    /** The server rejected every offered authentication method. */
    data class AuthFailed(
        val username: String,
        val methodsTried: List<String>,
    ) : AppError

    // ---- herdr-core ---------------------------------------------------------------------------

    /** `hc` could not be found or started on the host. */
    data class CoreNotFound(
        val searchedPaths: List<String>,
        val stderr: String? = null,
    ) : AppError

    /** The core speaks an incompatible protocol generation or lacks required methods/features. */
    data class CoreIncompatible(
        val reason: String,
        val coreVersion: String? = null,
    ) : AppError

    /**
     * The core rejected a call. [kind] is the stable `error.data.kind` (e.g. `not_found`,
     * `unavailable`, `finished`, `blocked`, `conflict`); unknown kinds are passed through.
     */
    data class Rpc(
        val kind: String,
        val message: String,
        val hint: String? = null,
    ) : AppError {
        companion object Kinds {
            const val NOT_FOUND = "not_found"
            const val MACHINE_UNKNOWN = "machine_unknown"
            const val FINISHED = "finished"
            const val UNAVAILABLE = "unavailable"
            const val LIVE = "live"
            const val BLOCKED = "blocked"
            const val CONFLICT = "conflict"
            const val NAME_TAKEN = "name_taken"
            const val INVALID_PARAMS = "invalid_params"
            const val UNKNOWN_METHOD = "unknown_method"
        }
    }

    /** The byte stream violated the protocol (bad frame, garbage before hello, oversize frame). */
    data class Protocol(
        val detail: String,
    ) : AppError

    /**
     * A mutating request was dispatched but the connection broke before its reply.
     * It may or may not have happened on the host. Never retry automatically: re-read state and
     * let the user decide.
     */
    data class OutcomeUnknown(
        val operation: String,
    ) : AppError

    /** The core supports the protocol but not this optional capability (method missing). */
    data class Unsupported(
        val capability: String,
    ) : AppError

    // ---- Files ----------------------------------------------------------------------------------

    /** The file to download does not exist (or cannot be read) on the host. */
    data class FileMissing(
        val path: String,
    ) : AppError

    /** The file is over the transfer limit ([limitBytes], raw bytes); nothing was transferred. */
    data class FileTooLarge(
        val limitBytes: Long,
    ) : AppError

    /** The file changed (size or modification time) while it was downloaded; the copy is discarded. */
    data class FileChanged(
        val path: String,
    ) : AppError

    // ---- Device -------------------------------------------------------------------------------

    /** Local persistence failed (database, DataStore, file). */
    data class Storage(
        val detail: String,
    ) : AppError

    /** Android Keystore is unavailable or the wrapped key was invalidated. */
    data class SecureStorageUnavailable(
        val detail: String? = null,
    ) : AppError

    /** A precondition in the app itself was violated (e.g. host not configured). */
    data class InvalidInput(
        val field: String,
        val reason: String,
    ) : AppError

    /** Anything not classified above. Logged with its cause; shown as a generic failure. */
    data class Unexpected(
        val cause: Throwable,
    ) : AppError
}

/**
 * Exception carrier for [AppError] inside a layer that uses exceptions internally.
 * Convert back with [com.mtajchert.flocket.core.common.result.appResult] at the boundary.
 */
class AppException(
    val error: AppError,
    cause: Throwable? = null,
) : RuntimeException(error.toString(), cause)
