package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.protocol.transport.DirectFileException
import com.mtajchert.flocket.core.protocol.transport.DirectFileReader
import com.mtajchert.flocket.core.protocol.transport.RemoteFileStat
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** An sshj [SFTPClient] on the host's SSH connection that reads files (ADR-0012). One call at a time. */
class SshSftpReader internal constructor(
    private val sftp: SFTPClient,
) : DirectFileReader {
    private val closed = AtomicBoolean(false)

    override suspend fun stat(path: String): RemoteFileStat? =
        withContext(Dispatchers.IO) {
            try {
                val attrs = sftp.statExistence(path) ?: return@withContext null
                RemoteFileStat(attrs.size, attrs.mtime, attrs.type == FileMode.Type.REGULAR)
            } catch (e: Exception) {
                throw map(e, "stat")
            }
        }

    override suspend fun copyTo(
        path: String,
        offset: Long,
        out: OutputStream,
        onBytes: (Int) -> Unit,
    ): Long =
        withContext(Dispatchers.IO) {
            val file: RemoteFile =
                try {
                    runInterruptible { sftp.open(path, EnumSet.of(OpenMode.READ)) }
                } catch (e: Exception) {
                    throw map(e, "open")
                }
            var input: java.io.InputStream? = null
            try {
                input = file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READS, offset)
                val ctx = currentCoroutineContext()
                val buf = ByteArray(BLOCK)
                var total = 0L
                runInterruptible {
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(n)
                    }
                }
                total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw map(e, "read")
            } finally {
                withContext(NonCancellable) {
                    runCatching { input?.close() }
                    runCatching { file.close() }
                }
            }
        }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) { runCatching { sftp.close() } }
    }

    private fun map(
        e: Exception,
        what: String,
    ): Exception =
        when {
            e is CancellationException || e is DirectFileException || e is TransportException -> {
                e
            }

            e is SFTPException -> {
                when (e.statusCode) {
                    Response.StatusCode.NO_SUCH_FILE -> {
                        DirectFileException(
                            DirectFileException.Reason.MISSING,
                            "$what: no such file",
                            e,
                        )
                    }

                    Response.StatusCode.PERMISSION_DENIED -> {
                        DirectFileException(
                            DirectFileException.Reason.DENIED,
                            "$what: permission denied",
                            e,
                        )
                    }

                    else -> {
                        DirectFileException(DirectFileException.Reason.IO, "$what failed: ${e.message}", e)
                    }
                }
            }

            e is IOException -> {
                TransportException("sftp $what failed: ${e.message}", e)
            }

            else -> {
                DirectFileException(DirectFileException.Reason.IO, "$what failed: ${e.message}", e)
            }
        }

    private companion object {
        const val MAX_UNCONFIRMED_READS = 64
        const val BLOCK = 64 * 1024
    }
}
