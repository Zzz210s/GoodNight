package com.goodnight.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.snapOf
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
 * v2.2 Task 5(复审第四轮 W1/W2):删除判据必须在**写库这一刻重做**,不能信对话框时刻的计划。
 *
 * W2:既有用例全是「先 restore 快照 → 再 deleteProfiles」,区分不出「写时重判」与「按计划时刻判定」。
 * 本用例先构建计划、**之后**才把引擎指到目标行,断言该行仍是归档 —— 按计划时刻判定会真删,用例红。
 *
 * W1:`activeProfileId` 也算「可能马上被使用」:通知栏/首页的开始键只能启动它(见
 * [com.goodnight.service.TimerNotifIdle.showIdle]),对话框停留期间用户点一下就会把引擎指过来;
 * 即使引擎快照还空着也必须归档,否则之后结算会写悬空 profileId。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = ProfileDeleteStaleUseTest.TestApp::class)
class ProfileDeleteStaleUseTest {
    /** 空 onCreate:注入受控 AppGraph(空闲态删除会走 TimerNotifIdle,上下文必须是 GoodNightApp) */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val app = ctx as GoodNightApp
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_stale_${testName.methodName}")
        app.graph = g
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    /**
     * W2 要求用例:计划构建之后引擎才起来 → 执行时按写时状态归档(不是按计划里的「真删」)。
     */
    @Test fun planBuiltBeforeEngineStartsStillArchivesAtWriteTime() = runBlocking {
        val live = clock("在用")
        clock("另一个")
        // 1) 对话框时刻:引擎空闲,按计划判定会把 live 归入「真删」
        val plan = planDeletion(
            selected = listOf(live), activeCount = 2,
            sessionMillis = emptyMap(), dailyTotals = emptyMap(),
            engineProfileId = g.engine.snapshot.value?.profileId,
        )
        assertEquals("计划时刻没人在用", emptySet<Long>(), plan.archivedIds)

        // 2) 计划构建之后引擎才指向它(通知栏「启动」/竞态)
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = live.id))

        // 3) 执行:按写时状态重判,而不是照计划里的「真删」
        vm().deleteProfiles(plan.clocks)

        val row = g.profileRepo.byId(live.id)
        assertNotNull("计划时刻的快照不是真值:写时要重判", row)
        assertTrue("写时已在用 → 归档", row!!.archived)
    }

    /**
     * W1 要求用例:`activeProfileId` 指向的行即使引擎快照为空也归档 —— 它随时会被通知栏/首页的
     * 「开始」键启起来,真删会在之后留下悬空结算。
     */
    @Test fun activeProfileTargetIsArchivedNotDeleted() = runBlocking {
        val target = clock("选中的")
        clock("另一个")
        g.settingsRepo.setActiveProfile(target.id)
        assertNull("引擎还没起来:只有 activeProfileId 可供判断", g.engine.snapshot.value)

        vm().deleteProfiles(listOf(target))

        val row = g.profileRepo.byId(target.id)
        assertNotNull("开始键瞄准它:不能真删", row)
        assertTrue("视为在用 → 归档", row!!.archived)
        assertNull("引擎仍空闲", g.engine.snapshot.value)
        assertTrue("归档后从活跃列表消失", g.profileRepo.observeAllActive().first().none { it.id == target.id })
    }
}