package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileEntity
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
 * v2.2 Task 5 用例夹具(拆两份用例文件以守住单文件 200 行:删除计划另见 ProfileDeletePlanTest)。
 *
 * @param storePrefix DataStore 单例按**文件名**缓存,同一个名字被多个用例共用会撞
 * 「multiple DataStores active for the same file」,故每个用例一份(见 #432)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

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

    /** 归档:先造一段历史(有引用才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(clock: ProfileEntity) {
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = clock.id, startAt = 0, endAt = 25 * 60_000L))
        )
        g.profileRepo.removeOrArchive(clock.id)
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

    /** 没有专属时钟的任务不出段(空段头纯属噪音) */
    @Test fun emptyTasksAreNotListed() = runTest {
        val a = task("写周报")
        task("读论文")
        clock("A 专属", a)

        val sections = clockSections(
            g.profileRepo.observeAllActive().first(),
            g.taskRepo.observeAllOrdered().first(),
        )

        assertEquals(listOf("写周报"), sections.drop(1).map { it.title })
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
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = p.id, startAt = 0, endAt = 25 * 60_000L, taskId = a))
        )
        val v = vm()

        assertTrue(v.moveToProfile(p.id, b))
        assertEquals(b, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("改归属不动会话行(仍归原任务)", a, g.db.focusSessionDao().getAll().single().taskId)

        clock("专注", null) // 通用层已有同名
        assertFalse("目标作用域重名:拒绝且保持原归属", v.moveToProfile(p.id, null))
        assertEquals(b, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("失败时一行都没动", 1, g.db.focusSessionDao().getAll().size)
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
