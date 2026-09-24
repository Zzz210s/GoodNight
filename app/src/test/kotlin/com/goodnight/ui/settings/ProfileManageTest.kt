package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5:时钟管理页 —— 「通用 / 任务专属(按任务分组)」两段、归属选择与改归属、
 * 删除的归档/真删预告、以及三条前序指针(活跃流口径 / 归档不满足「至少保留 1 个」/ 归档不清账)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_manage_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun task(title: String) = g.taskRepo.create(title, g.time.now())!!

    private suspend fun clock(name: String, taskId: Long? = null) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN, taskId)!!)!!

    private suspend fun session(profileId: Long, millis: Long = 25 * 60_000L, taskId: Long? = null) =
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = profileId, startAt = 0, endAt = millis, taskId = taskId))
        )

    /** 归档:先造一段历史(有引用才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(id: Long) {
        session(id)
        assertEquals(ProfileRemoval.Archived, g.profileRepo.removeOrArchive(id))
    }

    private fun pumpUntil(cond: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    /** 两段:通用在前,专属按任务顺序分组、段内保持 createdAt/id 升序;归档两边都不出现 */
    @Test fun sectionsSplitGenericThenTaskGroups() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val gen = clock("通用 25/5")
        val ownA1 = clock("A 专属1", a)
        val ownB = clock("B 专属", b)
        val ownA2 = clock("A 专属2", a)
        val dead = clock("旧通用")
        archive(dead)

        val sections = clockSections(
            g.profileRepo.observeAllActive().first(),
            g.taskRepo.observeAllOrdered().first(),
        )

        assertNull("通用段恒在首", sections[0].taskId)
        assertEquals(listOf(gen.name), sections[0].clocks.map { it.name })
        assertEquals("专属段按任务顺序", listOf("写周报", "读论文"), sections.drop(1).map { it.title })
        assertEquals(listOf(ownA1.name, ownA2.name), sections[1].clocks.map { it.name })
        assertEquals(listOf(ownB.name), sections[2].clocks.map { it.name })
        assertTrue("归档时钟不进任何段", sections.flatMap { it.clocks }.none { it.id == dead.id })
    }

    /** 新建可选归属;同名在不同作用域允许、同作用域拒绝 */
    @Test fun createRespectsScope() = runTest {
        val a = task("写周报")
        val v = vm()
        assertNotNull(v.createProfile("专注", 25, 5, ProfileMode.COUNTDOWN, taskId = null))
        assertNotNull("不同作用域可同名", v.createProfile("专注", 25, 5, ProfileMode.COUNTDOWN, taskId = a))
        assertNull("同作用域重名被拒", v.createProfile("专注", 30, 10, ProfileMode.COUNTDOWN, taskId = a))
        assertEquals(a, g.profileRepo.byId(g.profileRepo.availableFor(a).first().first { it.name == "专注" }.id)!!.taskId)
    }

    /** 改归属不改变任何会话行;目标作用域重名时拒绝并保持原归属(指针 5:必须返回 false 给 UI 提示) */
    @Test fun moveScopeKeepsSessionsAndRejectsNameConflict() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        session(p.id, taskId = a)
        val v = vm()

        assertTrue(v.moveToProfile(p.id, b))
        assertEquals(b, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("改归属不动会话行(仍归原任务)", a, g.db.focusSessionDao().getAll().single().taskId)

        clock("专注", null) // 通用层已有同名
        assertFalse("目标作用域重名:拒绝且保持原归属", v.moveToProfile(p.id, null))
        assertEquals(b, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("失败时一行都没动", 1, g.db.focusSessionDao().getAll().size)
    }

    /** 删除预告:被会话段或每日合计引用的时钟 → 归档;两者都没有 → 真删(与原子删除同口径) */
    @Test fun deletePlanArchivesOnlyClocksWithHistory() = runTest {
        val withSession = clock("有会话")
        val withDailyOnly = clock("只有合计")
        val clean = clock("无历史")
        val plan = deletePlan(
            listOf(withSession, withDailyOnly, clean),
            sessionMillis = mapOf(withSession.id to 25 * 60_000L),
            dailyTotals = mapOf(withDailyOnly.id to 90 * 60_000L),
        )
        assertEquals(3, plan.count)
        assertEquals(2, plan.archiveCount)
        assertEquals("归档分钟 = 会话优先 + 每日合计", 115L, plan.archiveMinutes)
        assertTrue(plan.anyArchive)

        val plain = deletePlan(listOf(clean), emptyMap(), emptyMap())
        assertEquals(0, plain.archiveCount)
        assertEquals(0L, plain.archiveMinutes)
        assertFalse(plain.anyArchive)
    }

    /** 指针 3:有历史的时钟删除走归档 —— 行保留、历史账一行不删、不再调清账路径 */
    @Test fun deleteProfileArchivesHistoryAndKeepsRecords() = runTest {
        val p = clock("有历史")
        session(p.id, millis = 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-24", p.id, 30 * 60_000L)

        assertFalse("归档不需要发 stop", vm().deleteProfile(p))

        assertTrue("行保留并标记归档", g.profileRepo.byId(p.id)!!.archived)
        assertEquals("会话段一行不少", 1, g.db.focusSessionDao().getAll().size)
        assertEquals(
            "每日合计一行不少",
            30 * 60_000L,
            g.totalsRepo.profileTotals().first().first { it.profileId == p.id }.total,
        )
        assertTrue("归档后从管理页活跃流消失", g.profileRepo.observeAllActive().first().none { it.id == p.id })
    }

    /** 指针 1:归档行不算「至少保留 1 个」—— 界面上看不见的行不能替活跃时钟挡删除 */
    @Test fun archivedClockDoesNotSatisfyKeepOneRule() = runTest {
        val live = clock("在用")
        val dead = clock("旧")
        archive(dead)

        assertFalse("唯一活跃时钟不可删(归档行不算数)", vm().deleteProfile(live))
        assertNotNull("活跃时钟未被删", g.profileRepo.byId(live.id))
        assertFalse(g.profileRepo.byId(live.id)!!.archived)
    }

    /** 指针 1:管理页列表只喂活跃时钟(归档行不进 UI) */
    @Test fun settingsUiListsActiveClocksOnly() = runTest {
        val live = clock("在用")
        val dead = clock("旧")
        archive(dead)
        val v = vm()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.ui.collect {} }
        assertTrue("UI 就绪", pumpUntil { v.ui.value.profiles.isNotEmpty() })

        assertEquals(listOf(live.id), v.ui.value.profiles.map { it.id })
        assertEquals("分组只含活跃时钟", listOf("在用"), v.ui.value.sections.flatMap { it.clocks }.map { it.name })
        job.cancel()
    }
}
