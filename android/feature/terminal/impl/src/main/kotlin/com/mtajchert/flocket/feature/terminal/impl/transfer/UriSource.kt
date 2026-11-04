package com.mtajchert.flocket.feature.terminal.impl.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/** What the system pickers (or the clipboard) told us about a content URI. */
data class PickedFile(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

/** Reads picked or clipboard content URIs; the only place that touches a `ContentResolver`. */
interface UriSource {
    suspend fun describe(uri: Uri): AppResult<PickedFile>

    /** Reads the whole content, failing with [AppError.FileTooLarge] as soon as it passes [maxBytes]. */
    suspend fun read(
        uri: Uri,
        maxBytes: Long,
    ): AppResult<ByteArray>
}

internal class ContentResolverUriSource
    @Inject
    constructor(
        private val resolver: ContentResolver,
    ) : UriSource {
        override suspend fun describe(uri: Uri): AppResult<PickedFile> =
            withContext(Dispatchers.IO) {
                try {
                    var name: String? = null
                    var size: Long? = null
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = c.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                            if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
                        }
                    }
                    AppResult.Success(
                        PickedFile(
                            displayName = name ?: uri.lastPathSegment ?: "file",
                            mimeType = resolver.getType(uri),
                            sizeBytes = size,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppResult.Failure(e.toReadError(uri))
                }
            }

        override suspend fun read(
            uri: Uri,
            maxBytes: Long,
        ): AppResult<ByteArray> =
            withContext(Dispatchers.IO) {
                try {
                    val input =
                        resolver.openInputStream(uri)
                            ?: return@withContext AppResult.Failure(AppError.FileMissing(uri.toString()))
                    input.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return@withContext AppResult.Failure(AppError.FileTooLarge(maxBytes))
                            out.write(buffer, 0, n)
                        }
                        AppResult.Success(out.toByteArray())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppResult.Failure(e.toReadError(uri))
                }
            }

        private fun Exception.toReadError(uri: Uri): AppError =
            when (this) {
                // A URI that can no longer be opened (moved, grant expired) reads as a missing file.
                is FileNotFoundException, is SecurityException -> AppError.FileMissing(uri.toString())

                is IOException -> AppError.Storage(message ?: "read failed")

                else -> AppError.FileMissing(uri.toString())
            }

        private companion object {
            const val BUFFER = 64 * 1024
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal object UriSourceModule {
    @Provides
    fun uriSource(
        @ApplicationContext context: Context,
    ): UriSource = ContentResolverUriSource(context.contentResolver)
}
