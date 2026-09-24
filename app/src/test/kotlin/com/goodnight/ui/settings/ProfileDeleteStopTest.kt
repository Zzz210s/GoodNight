package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.snapOf
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5 复审修复:删除确认后要不要补一条 stop。
 *
 * 判据是「被删的正是**引擎当前认的**时钟」—— RUNNING 与 PAUSED 都算。旧判据(UI 侧只认
 * RUNNING)会让「暂停中且无历史」的时钟走 RESET_THEN_DELETE(行已删 + 需要调用方发 stop)
 * 却没人发 stop:引擎快照继续指着不存在的 profileId,之后 resume/终止结算会写出悬空
 * profileId 的会话与每日合计。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeleteStopTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_stop_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    @Test fun pausedBoundClockDeleteAsksForStop() = runTest {
        val live = clock("在用")
        clock("另一个") // 「至少保留 1 个活跃时钟」不拦这次删除
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = live.id))

        assertTrue("暂停中被引擎选中的时钟:删除后必须发 stop", vm().deleteProfiles(listOf(live)))
        assertNull("无历史 → 行真删", g.profileRepo.byId(live.id))
    }

    @Test fun pausedClockTheEngineDoesNotUseNeedsNoStop() = runTest {
        val live = clock("在用")
        val other = clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = live.id))

        assertFalse("引擎认的是另一个:删这个不用 stop", vm().deleteProfiles(listOf(other)))
        assertNull(g.profileRepo.byId(other.id))
    }

    @Test fun runningBoundClockIsNeverDeleted() = runTest {
        val live = clock("在用")
        clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.RUNNING).copy(profileId = live.id))

        assertFalse("运行中的时钟不删、也不发 stop(卡片本就不可勾选)", vm().deleteProfiles(listOf(live)))
        assertNotNull("一行不动", g.profileRepo.byId(live.id))
    }
}
