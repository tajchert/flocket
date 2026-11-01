package com.mtajchert.flocket.core.ui.error

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.ui.R

/** The core's `needs_update` kind for a federated machine that must update herdr first. */
private const val NEEDS_UPDATE = "needs_update"

/**
 * The message for a failed rename of [ref], or null when the generic error path should show it.
 * [hostLabel] names the host for a lost connection; a federated space names its machine instead.
 */
fun AppError.toRenameText(
    ref: LayoutRef,
    hostLabel: String,
): UiText? =
    when {
        this is AppError.OutcomeUnknown -> {
            UiText.Res(R.string.rename_outcome_unknown)
        }

        this is AppError.Rpc && kind == AppError.Rpc.CONFLICT -> {
            UiText.Res(R.string.rename_error_conflict)
        }

        this is AppError.Rpc && kind == AppError.Rpc.NOT_FOUND -> {
            UiText.Res(if (ref.tabId != null) R.string.rename_error_gone_tab else R.string.rename_error_gone_space)
        }

        this is AppError.ConnectionLost -> {
            UiText.Res(R.string.rename_error_unreachable, listOf(hostLabel))
        }

        this is AppError.Rpc && ref.machine != null &&
            kind in setOf(AppError.Rpc.UNAVAILABLE, AppError.Rpc.MACHINE_UNKNOWN, NEEDS_UPDATE) -> {
            UiText.Res(R.string.rename_error_unreachable, listOf(ref.machine.orEmpty()))
        }

        else -> {
            null
        }
    }
