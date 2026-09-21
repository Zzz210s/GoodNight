package com.goodnight.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.13.0 备份写盘结果归类:SecurityException(授权失效)必须与一般写入失败区分,
 * 否则设置页只能显示笼统的"备份失败",用户不知道该重选目录还是检查目录可写性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupWriterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun securityExceptionIsPermissionDenied() {
        assertEquals(BackupWriteResult.PERMISSION_DENIED, BackupWriter.classify(SecurityException("no grant")))
    }

    @Test fun otherFailuresAreGeneric() {
        assertEquals(BackupWriteResult.FAILED, BackupWriter.classify(java.io.IOException("disk full")))
        assertEquals(BackupWriteResult.FAILED, BackupWriter.classify(IllegalStateException("provider died")))
    }

    @Test fun writeToUnknownTreeFailsWithoutCrash() {
        val result = BackupWriter.write(ctx, Uri.parse("content://no.such.provider/tree/x"), "{}")
        assertEquals(BackupWriteResult.FAILED, result)
    }
}
