package com.goodnight.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.snapOf
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * v2.2 Task 5 复审修复 W1:删除前先停机结算。
 *
 * 引擎暂停在某个待删时钟上时,`reset()` 会把暂停段的窗口交给 EventApplier 落库 —— 而它**不校验
 * profile 是否还在**(只校验 taskId),所以「先删行、再 stop」会写出悬空 profileId 的会话段与每日
 * 合计。现在批量删除先停机([com.goodnight.service.EngineCoordinator.stopAndSettle])并等结算落库,
 * 再按**结算后**的 hasHistory 决定归档还是真删。
 *
 * 用 runBlocking(与 [com.goodnight.service.EngineCoordinatorTest] 同套路):结算在 appScope
 * (Dispatchers.Default)上真跑,不靠虚拟时间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = ProfileDeleteStopTest.TestApp::class)
class ProfileDeleteStopTest {
    /** 空 onCreate:注入受控 AppGraph(删除完会走 TimerNotifIdle → 上下文必须是 GoodNightApp) */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val app = ctx as GoodNightApp
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_stop_${testName.methodName}")
        app.graph = g
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    /** 引擎的 Reset 事件由协调器的事件订阅者落库:真机在 AppGraph 建立时装,测试要显式装 */
    private suspend fun installCoordinator() {
        g.coordinator.install()
        g.coordinator.awaitReadyAndSubscribed()
    }

    /** 库里不得有指向已删时钟的会话段 / 每日合计行 */
    private suspend fun assertNoDanglingProfileRefs() {
        val ids = g.db.profileDao().getAll().map { it.id }.toSet()
        val sessions = g.db.focusSessionDao().getAll()
        val dailies = g.db.dailyTotalDao().getAll()
        assertTrue("会话段指向已删时钟:${sessions.map { it.profileId }} 不在 $ids", sessions.all { it.profileId in ids })
        assertTrue("每日合计指向已删时钟:${dailies.map { it.profileId }} 不在 $ids", dailies.all { it.profileId in ids })
    }

    /**
     * 要求用例:暂停中删除「无历史且正被引擎选中」的时钟 —— 结算后库里不能有指向已删 profileId 的行。
     *
     * 修复后的顺序:先停机结算(暂停段那 5 分钟在**行还在**时落库 → 它变成「有历史」)→ 归档,
     * 行留在库里;旧顺序(先删行、再 stop)会把那 5 分钟写到已删的 profileId 上。
     */
    @Test fun pausedBoundClockIsSettledBeforeRemoval() = runBlocking {
        val live = clock("在用")
        clock("另一个") // 「至少保留 1 个活跃时钟」不拦这次删除
        val wall = g.time.now()
        g.engine.restore(
            snapOf(status = EngineStatus.PAUSED).copy(
                profileId = live.id,
                sessionStartWall = wall - 5 * 60_000L,
                pauseStartWall = wall,
            ),
        )
        installCoordinator()

        vm().deleteProfiles(listOf(live))

        assertNull("删除前必须先停机", g.engine.snapshot.value)
        val row = g.profileRepo.byId(live.id)
        assertNotNull("暂停段结算成历史后必须归档:行还在,才没有悬空引用", row)
        assertTrue("已归档", row!!.archived)
        assertEquals(
            "结算出的那一段落在仍在库里的时钟上",
            listOf(live.id),
            g.db.focusSessionDao().getAll().map { it.profileId },
        )
        assertNoDanglingProfileRefs()
    }

    /** 引擎认的是别的时钟:不动它,那条暂停中的计时照旧挂着 */
    @Test fun pausedClockTheEngineDoesNotUseIsNotStopped() = runBlocking {
        val live = clock("在用")
        val other = clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = live.id))

        vm().deleteProfiles(listOf(other))

        assertNull("无历史 → 行真删", g.profileRepo.byId(other.id))
        assertEquals("引擎仍暂停在「在用」", live.id, g.engine.snapshot.value?.profileId)
        assertNoDanglingProfileRefs()
    }

    /** 运行中的时钟卡片不可勾选:就算硬删也不动它、不停机 */
    @Test fun runningBoundClockIsNeverDeletedNorStopped() = runBlocking {
        val live = clock("在用")
        clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.RUNNING).copy(profileId = live.id))

        vm().deleteProfiles(listOf(live))

        assertNotNull("一行不动", g.profileRepo.byId(live.id))
        assertEquals("也没被带停", EngineStatus.RUNNING, g.engine.snapshot.value?.status)
    }
}
