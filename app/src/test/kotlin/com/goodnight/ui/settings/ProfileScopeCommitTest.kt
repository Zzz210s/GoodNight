package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileEntity
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
 * v2.2 Task 5 复审修复 W2:编辑提交的「改归属 + 改名」必须**整体成功或整体不动**。
 *
 * 旧实现分两步(先搬迁、改名被拒再把归属搬回),回滚本身也可能被拒(原作用域已有同名活跃
 * 时钟,如历史数据/导入遗留的重名行)—— 那会留下「已换归属、名字未改」的第三种状态。现在由
 * 仓库层按**最终状态**一次写入:[ProfileRepository.moveAndRename] 先预判目标作用域冲突,
 * 冲突就一行不动,没有回滚可达。
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

    /** 目标作用域已有最终名字 → 整步被拒,归属与原名字都不动(没有中间态) */
    @Test fun renameConflictLeavesScopeUnchanged() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        clock("番茄", b) // B 里占住「番茄」

        assertFalse("B 里已有「番茄」:整步拒绝", vm().commitScopeAndName(p, b, "番茄"))

        assertEquals("归属没被换走", a, g.profileRepo.byId(p.id)!!.taskId)
        assertEquals("名字也没改", "专注", g.profileRepo.byId(p.id)!!.name)
    }

    /**
     * W2 的「回滚失败路径」:原作用域已有同名活跃行(历史数据/导入遗留的重名)。
     * 旧实现先搬迁成功、改名被拒后把归属搬回 —— 但搬回这一步也会被同名行拒,于是留在目标
     * 作用域且名字未改。预判实现下这条路径不可达:整步一行不动。
     */
    @Test fun conflictWithDamagedOriginalScopeLeavesEverythingUntouched() = runTest {
        val a = task("写周报")
        val b = task("读论文")
        val p = clock("专注", a)
        clock("番茄", b)
        // 绕过仓库的作用域唯一校验造一条重名行:让「搬回原作用域」这一步也会被拒
        g.db.profileDao().insert(
            ProfileEntity(name = "专注", workMinutes = 25, restMinutes = 5, createdAt = 0, taskId = a),
        )

        assertFalse("目标作用域「番茄」已被占用:整步拒绝", vm().commitScopeAndName(p, b, "番茄"))

        assertEquals("归属没有被换走(旧实现的回滚会失败在这里)", a, g.profileRepo.byId(p.id)!!.taskId)
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
