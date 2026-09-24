package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5 复审修复:编辑提交的「先搬迁再改名」必须**整体成功或整体不动**。
 *
 * 搬迁已落库后改名被拒(目标作用域已有同名)时,旧实现只置 nameTaken 就返回 —— 用户随手取消
 * 就留下「已换归属、名字未改」的半成品(报告 4.2 声称「任一步失败即整体不动」,与实现不符)。
 * 现在两步收进 [SettingsViewModel.commitScopeAndName],改名失败把归属搬回原值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileScopeCommitTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_commit_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun task(title: String) = g.taskRepo.create(title, g.time.now())!!

    private suspend fun clock(name: String, taskId: Long? = null) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN, taskId)!!)!!

    /** 搬迁成功 + 改名撞名 → 归属必须搬回原值,名字保持原样 */
    @Test fun renameConflictRollsScopeBack() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        clock("番茄", b) // B 里占住「番茄」

        assertFalse("B 里已有「番茄」:整步拒绝", vm().commitScopeAndName(p, b, "番茄"))

        assertEquals("归属必须搬回原值", a, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("名字也没改", "专注", g.profileRepo.byId(p.id)!!.name)
    }

    /** 无冲突时两步都落库(搬迁 + 改名):回滚逻辑不得影响正常路径 */
    @Test fun scopeAndNameCommitTogetherWhenNoConflict() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)

        assertTrue(vm().commitScopeAndName(p, b, "深度专注"))

        val row = g.profileRepo.byId(p.id)!!
        assertEquals(b, row.taskId)
        assertEquals("深度专注", row.name)
    }

    /** 单步路径(只换归属 / 只改名)不被回滚逻辑带歪 */
    @Test fun singleStepEditsStillWork() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        val v = vm()

        assertTrue(v.commitScopeAndName(p, b, p.name))
        assertEquals(b, g.profileRepo.byId(p.id)!!.taskId)

        assertTrue(v.commitScopeAndName(g.profileRepo.byId(p.id)!!, b, "深度专注"))
        assertEquals("深度专注", g.profileRepo.byId(p.id)!!.name)
    }

    /** 搬迁这一步就被拒(目标作用域同名):一行不动,不需要回滚 */
    @Test fun moveConflictLeavesEverythingUntouched() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        clock("专注", b)

        assertFalse(vm().commitScopeAndName(p, b, "专注"))

        assertEquals(a, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("专注", g.profileRepo.byId(p.id)!!.name)
    }
}
