package com.mtajchert.flocket.core.ui.error

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.ui.R

/** Text that resolves lazily, so ViewModels and mappers stay free of `Context` and are unit-testable. */
sealed interface UiText {
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plain(
        val value: String,
    ) : UiText

    fun resolve(context: Context): String =
        when (this) {
            is Res -> context.getString(id, *args.toTypedArray())
            is Plain -> value
        }
}

@Composable
fun UiText.asString(): String =
    when (this) {
        is UiText.Res -> stringResource(id, *args.toTypedArray())
        is UiText.Plain -> value
    }

/** What the user can do about an error. The screen decides how to perform it. */
enum class UiErrorAction(
    @StringRes val label: Int,
) {
    Retry(R.string.action_retry),
    TrustHostKey(R.string.action_trust_host_key),
    EditHost(R.string.action_edit_host),
    InstallHelp(R.string.action_install_help),
    ReenterPassword(R.string.action_reenter_password),
    ForgetPinnedKey(R.string.action_forget_pinned_key),
}

/** User-facing form of an [AppError]. [detail] is technical text (stderr, core message) shown in small print. */
data class UiError(
    val title: UiText,
    val message: UiText,
    val primaryAction: UiErrorAction? = null,
    val detail: String? = null,
    val source: AppError? = null,
)

private fun res(
    @StringRes id: Int,
    vararg args: Any,
) = UiText.Res(id, args.toList())

/**
 * THE place where errors become text. The `when` is exhaustive: adding an [AppError] case fails the build
 * until it is mapped here. Screens never format errors themselves.
 */
fun AppError.toUiError(): UiError =
    when (this) {
        is AppError.Unreachable -> {
            UiError(
                res(R.string.error_unreachable_title),
                res(R.string.error_unreachable_message, host),
                UiErrorAction.Retry,
                detail,
                this,
            )
        }

        AppError.ConnectionLost -> {
            UiError(
                res(R.string.error_connection_lost_title),
                res(R.string.error_connection_lost_message),
                UiErrorAction.Retry,
                source = this,
            )
        }

        is AppError.Timeout -> {
            UiError(
                res(R.string.error_timeout_title),
                res(R.string.error_timeout_message, operation),
                UiErrorAction.Retry,
                source = this,
            )
        }

        is AppError.HostKeyUnknown -> {
            UiError(
                res(R.string.error_host_key_unknown_title),
                res(R.string.error_host_key_unknown_message, host),
                UiErrorAction.TrustHostKey,
                source = this,
            )
        }

        is AppError.HostKeyChanged -> {
            UiError(
                res(R.string.error_host_key_changed_title),
                res(R.string.error_host_key_changed_message, host),
                UiErrorAction.ForgetPinnedKey,
                source = this,
            )
        }

        is AppError.AuthFailed -> {
            if ("password" in methodsTried) {
                UiError(
                    res(R.string.error_auth_password_title),
                    res(R.string.error_auth_password_message, username),
                    UiErrorAction.ReenterPassword,
                    source = this,
                )
            } else {
                UiError(
                    res(R.string.error_auth_key_title),
                    res(R.string.error_auth_key_message, username),
                    UiErrorAction.EditHost,
                    source = this,
                )
            }
        }

        is AppError.CoreNotFound -> {
            UiError(
                res(R.string.error_core_not_found_title),
                res(R.string.error_core_not_found_message),
                UiErrorAction.InstallHelp,
                stderr,
                this,
            )
        }

        is AppError.CoreIncompatible -> {
            UiError(
                res(R.string.error_core_incompatible_title),
                res(R.string.error_core_incompatible_message, reason),
                UiErrorAction.InstallHelp,
                coreVersion,
                this,
            )
        }

        is AppError.Rpc -> {
            val title =
                when (kind) {
                    AppError.Rpc.NOT_FOUND -> R.string.error_rpc_not_found_title
                    AppError.Rpc.FINISHED -> R.string.error_rpc_finished_title
                    AppError.Rpc.UNAVAILABLE -> R.string.error_rpc_unavailable_title
                    AppError.Rpc.NAME_TAKEN -> R.string.error_rpc_name_taken_title
                    else -> R.string.error_rpc_generic_title
                }
            val action = if (kind == AppError.Rpc.UNAVAILABLE) UiErrorAction.Retry else null
            UiError(res(title), UiText.Plain(message), action, hint, this)
        }

        is AppError.Protocol -> {
            UiError(
                res(R.string.error_protocol_title),
                res(R.string.error_protocol_message),
                UiErrorAction.Retry,
                detail,
                this,
            )
        }

        is AppError.OutcomeUnknown -> {
            UiError(
                res(R.string.error_outcome_unknown_title),
                res(R.string.error_outcome_unknown_message, operation),
                source = this,
            )
        }

        is AppError.Unsupported -> {
            UiError(
                res(R.string.error_unsupported_title),
                res(R.string.error_unsupported_message, capability),
                UiErrorAction.InstallHelp,
                source = this,
            )
        }

        is AppError.FileMissing -> {
            UiError(res(R.string.error_file_missing_title), res(R.string.error_file_missing_message), source = this)
        }

        is AppError.FileTooLarge -> {
            UiError(
                res(R.string.error_file_too_large_title),
                res(R.string.error_file_too_large_message),
                source = this,
            )
        }

        is AppError.Storage -> {
            UiError(
                res(R.string.error_storage_title),
                res(R.string.error_storage_message),
                UiErrorAction.Retry,
                detail,
                this,
            )
        }

        is AppError.SecureStorageUnavailable -> {
            UiError(
                res(R.string.error_secure_storage_title),
                res(R.string.error_secure_storage_message),
                detail = detail,
                source = this,
            )
        }

        is AppError.InvalidInput -> {
            UiError(
                res(R.string.error_invalid_input_title),
                res(R.string.error_invalid_input_message, field, reason),
                UiErrorAction.EditHost,
                source = this,
            )
        }

        is AppError.Unexpected -> {
            UiError(
                res(R.string.error_unexpected_title),
                res(R.string.error_unexpected_message),
                UiErrorAction.Retry,
                cause.message,
                this,
            )
        }
    }
