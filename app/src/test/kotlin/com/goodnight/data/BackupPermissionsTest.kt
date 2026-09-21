package com.goodnight.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.13.0 修复"每次重启后备份目录失效":选目录时必须 takePersistableUriPermission,
 * 否则授权只活到进程结束(重启后备份写入抛 SecurityException)。
 * 真机取证:修复前 `dumpsys activity` 的 Granted Uri Permissions 里查不到本应用任何授权。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupPermissionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun tree(name: String): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A$name")

    @Test fun persistMakesGrantSurviveProcessRestart() {
        val uri = tree("PersistCase")
        assertFalse("初始不应有持久化授权", BackupPermissions.hasPersisted(ctx, uri))
        assertTrue("持久化调用应成功", BackupPermissions.persist(ctx, uri))
        assertTrue("持久化后应查得到写授权", BackupPermissions.hasPersisted(ctx, uri))
        assertTrue(BackupPermissions.ensure(ctx, uri))
    }

    @Test fun ensureRepersistsAfterGrantReleased() {
        val uri = tree("ReleaseCase")
        BackupPermissions.persist(ctx, uri)
        ctx.contentResolver.releasePersistableUriPermission(uri, flags)
        assertFalse(BackupPermissions.hasPersisted(ctx, uri))
        assertTrue("授权丢失后 ensure 应补做一次", BackupPermissions.ensure(ctx, uri))
        assertTrue(BackupPermissions.hasPersisted(ctx, uri))
    }

    @Test fun healOnStartupClearsStalePermissionError() = runTest {
        val uri = tree("HealCase")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(ctx.cacheDir, "perm.preferences_pb")
        file.delete()
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        val settings = SettingsRepository(ds)
        settings.setBackupUri(uri.toString())
        settings.setBackupError(BackupError.PERMISSION)

        BackupPermissions.healOnStartup(ctx, settings)

        assertTrue(BackupPermissions.hasPersisted(ctx, uri))
        assertNull("授权补上后应清掉旧的授权失效错误", settings.backupError.first())
        scope.cancel()
    }
}
