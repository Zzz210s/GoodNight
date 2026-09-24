package com.goodnight.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_STOP
import com.goodnight.service.TimerCommand
import com.goodnight.service.snapOf
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * v2.2 Task 5(复审修复 C1):删除**正在被引擎使用**的时钟一律走归档 —— 不停机、不等结算。
 *
 * 旧方案「先停机结算再决定真删/归档」有两个无法关闭的窗口:`snapshot == null` 与「结算写入还在飞」
 * 不可区分,超时也会落回真删;停机还是不可逆副作用(协程被取消 → 引擎已停、一个时钟都没删)。
 * 现在行永远在库里(归档),之后的结算写进来都合法 —— 竞态窗口整体消失。用 runBlocking(与
 * [com.goodnight.service.EngineCoordinatorTest] 同套路):结算在 appScope(Dispatchers.Default)
 * 上真跑,不靠虚拟时间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = ProfileDeleteInUseTest.TestApp::class)
class ProfileDeleteInUseTest {
    /** 空 onCreate:注入受控 AppGraph(归档完可能走 TimerNotifIdle → 上下文必须是 GoodNightApp) */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val app = ctx as GoodNightApp
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_inuse_${testName.methodName}")
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

    /** 用户主动停止(协调器直发,与服务路径同一把 mutex),等 Reset 结算落库 */
    private suspend fun stopAndAwaitSettle() {
        g.coordinator.run(TimerCommand(ACTION_STOP))
        val drained = g.coordinator.stopDrained
        if (drained != null) withTimeout(3_000) { drained.await() }
    }

    /**
     * 要求用例:正被引擎选中的**无历史**时钟 → 删除后仍是归档(行在、archived=1),引擎继续指向它,
     * 之后结算能正常落库(不产生悬空引用)。
     */
    @Test fun pausedInUseClockWithoutHistoryIsArchivedAndStaysSettleable() = runBlocking {
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

        val row = g.profileRepo.byId(live.id)
        assertNotNull("在用时钟即使无历史也不能真删(真删 + 结算在飞 = 悬空引用)", row)
        assertTrue("改为归档", row!!.archived)
        assertEquals("引擎仍暂停在它身上:本路径不停机", live.id, g.engine.snapshot.value?.profileId)
        assertEquals(EngineStatus.PAUSED, g.engine.snapshot.value?.status)
        assertEquals("归档不动账,这次也没有结算", 0, g.db.focusSessionDao().getAll().size)

        // 用户之后主动停止:那 5 分钟结算写进**仍在库里**的归档行,不产生悬空引用
        stopAndAwaitSettle()
        assertEquals(
            "结算段挂在归档行上",
            listOf(live.id),
            g.db.focusSessionDao().getAll().map { it.profileId },
        )
        assertNoDanglingProfileRefs()
    }

    /** 要求用例:正被引擎选中且**有历史**的时钟 → 同样归档(走的是另一个判据分支) */
    @Test fun pausedInUseClockWithHistoryIsArchived() = runBlocking {
        val live = clock("在用")
        clock("另一个")
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = live.id, startAt = 0, endAt = 25 * 60_000L)),
        )
        val wall = g.time.now()
        g.engine.restore(
            snapOf(status = EngineStatus.PAUSED).copy(
                profileId = live.id,
                sessionStartWall = wall - 60_000L,
                pauseStartWall = wall,
            ),
        )
        installCoordinator()

        vm().deleteProfiles(listOf(live))

        assertTrue("有历史 + 在用 → 归档", g.profileRepo.byId(live.id)!!.archived)
        assertEquals("历史一行不删", 1, g.db.focusSessionDao().getAll().size)
        assertEquals("引擎不动", live.id, g.engine.snapshot.value?.profileId)
        assertNoDanglingProfileRefs()
    }

    /** 正被引擎选中的**运行中**时钟也归档(判据含 RUNNING),计时不被打断 */
    @Test fun runningInUseClockIsArchivedAndKeepsRunning() = runBlocking {
        val live = clock("在用")
        clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.RUNNING).copy(profileId = live.id))

        vm().deleteProfiles(listOf(live))

        assertTrue("运行中同样归档(行保留)", g.profileRepo.byId(live.id)!!.archived)
        assertEquals("计时继续跑", EngineStatus.RUNNING, g.engine.snapshot.value?.status)
        assertEquals(live.id, g.engine.snapshot.value?.profileId)
        assertNoDanglingProfileRefs()
    }

    /** 要求用例:引擎**未**使用且无历史的时钟 → 真删(既有行为不回退) */
    @Test fun unusedClockWithoutHistoryIsReallyDeleted() = runBlocking {
        val live = clock("在用")
        val other = clock("另一个")
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = live.id))

        vm().deleteProfiles(listOf(other))

        assertNull("无历史且未被使用 → 行真删", g.profileRepo.byId(other.id))
        assertEquals("引擎仍暂停在「在用」", live.id, g.engine.snapshot.value?.profileId)
        assertNoDanglingProfileRefs()
    }

    /**
     * 要求用例:「最后一个活跃时钟」门控在新语义下仍然生效 —— 引擎正选中的唯一活跃时钟**不归档**
     * (归档同样让它从列表消失,首页开始键会失效),一行不动。
     */
    @Test fun lastActiveInUseClockIsKeptUntouched() = runBlocking {
        val only = clock("唯一")
        val wall = g.time.now()
        g.engine.restore(
            snapOf(status = EngineStatus.PAUSED).copy(
                profileId = only.id,
                sessionStartWall = wall - 5 * 60_000L,
                pauseStartWall = wall,
            ),
        )

        vm().deleteProfiles(listOf(only))

        val row = g.profileRepo.byId(only.id)
        assertNotNull("最后一个活跃时钟不能被归档", row)
        assertTrue("也没被标记归档", !row!!.archived)
        assertEquals("引擎仍指着它", only.id, g.engine.snapshot.value?.profileId)
        assertEquals("一行没动", 1, g.profileRepo.countActive())
    }
}
