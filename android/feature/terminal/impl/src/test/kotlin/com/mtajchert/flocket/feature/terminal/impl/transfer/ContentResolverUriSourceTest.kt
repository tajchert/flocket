package com.mtajchert.flocket.feature.terminal.impl.transfer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class ContentResolverUriSourceTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val source = ContentResolverUriSource(resolver)
    private val uri = Uri.parse("content://test/file")

    @Test
    fun `unknown size stops reading at the cap`() =
        runTest {
            var served = 0L
            shadowOf(resolver).registerInputStream(
                uri,
                object : InputStream() {
                    override fun read(): Int = 0.also { served++ }

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int = len.also { served += it }
                },
            )
            val r = source.read(uri, maxBytes = 1_000)
            assertEquals(AppResult.Failure(AppError.FileTooLarge(1_000)), r)
            assertTrue("read $served bytes of an endless stream", served < 1_000 + 64 * 1024)
        }

    @Test
    fun `reads small content whole`() =
        runTest {
            shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            assertEquals(listOf<Byte>(1, 2, 3), (source.read(uri, 10) as AppResult.Success).value.toList())
        }
}
