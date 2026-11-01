package com.mtajchert.flocket.core.common.result

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.error.AppException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of a fallible operation at a module boundary. Public APIs return this instead of
 * throwing. Cancellation is never represented here: [CancellationException] always propagates.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

/** Runs [block], converting thrown exceptions to [AppResult.Failure]. Rethrows cancellation. */
inline fun <T> appResult(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppException) {
        AppResult.Failure(e.error)
    } catch (e: Exception) {
        AppResult.Failure(AppError.Unexpected(e))
    }

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(value)
        is AppResult.Failure -> this
    }

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

inline fun <T, R> AppResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R,
): R =
    when (this) {
        is AppResult.Success -> onSuccess(value)
        is AppResult.Failure -> onFailure(error)
    }

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

/** Unwraps or throws [AppException]; for use *inside* a layer that converts back at its boundary. */
fun <T> AppResult<T>.getOrThrow(): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AppException(error)
    }
