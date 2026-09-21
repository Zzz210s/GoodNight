package com.goodnight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.ReminderIntensity
import com.goodnight.data.RuntimeStateStore
import com.goodnight.data.SettingsRepository
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
class SettingsStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun defaultsAndWrites() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(ctx.cacheDir, "t1.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        val settings = SettingsRepository(ds)
        assertEquals(-1L, settings.activeProfileId.first())
        assertEquals(ReminderIntensity.STANDARD, settings.reminderIntensity.first())
        settings.setActiveProfile(5)
        settings.setReminderIntensity(ReminderIntensity.STRONG)
        assertEquals(5L, settings.activeProfileId.first())
        assertEquals(ReminderIntensity.STRONG, settings.reminderIntensity.first())
        scope.cancel()
    }

    @Test fun runtimeStateRoundTrip() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(ctx.cacheDir, "t2.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = RuntimeStateStore(ds)
        assertNull(store.flow.first())
        val snap = RuntimeSnapshot(
            profileId = 1, workMillis = 900_000, restMillis = 300_000,
            phase = Phase.REST, status = EngineStatus.RUNNING, cycleCount = 1,
            startElapsed = 0, endElapsed = 300_000, endWall = 100,
            timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
            savedAtWall = 50, savedAtElapsed = 10,
            ckptDate = null, ckptAccum = 0,
        )
        store.save(snap)
        assertEquals(snap, store.flow.first())
        store.save(null)
        assertNull(store.flow.first())
        scope.cancel()
    }
}
