package com.goodnight.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.ReminderIntensity
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// 绕过 GoodNightApp 真实装配,保持测试封闭(同进程同 DataStore 文件多实例会抛异常,见 SettingsStoreTest 头注)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun snap(status: EngineStatus, profileId: Long = 1) = RuntimeSnapshot(
        profileId, 1, 1, Phase.WORK, status, 0, 0, 1, 0, 0, 0, 0, 0, 0, null, 0,
    )

    @Test fun crudAndTotals() = runTest {
        // 独立 store 文件名:同进程同文件多实例会抛异常(见 AlarmReceiverTest 头注)
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "sv1")
        g.bootstrap()
        val vm = SettingsViewModel(g)
        val id = vm.createProfile("深度", 50, 10, ProfileMode.COUNTDOWN)!!
        assertTrue(id > 0)
        assertNull("重名拒绝", vm.createProfile("深度", 50, 10, ProfileMode.COUNTDOWN))
        vm.renameProfile(id, "深度专注")
        vm.editDurations(ProfileEntity(id, "x", 1, 1, 0), 45, 15, ProfileMode.COUNTDOWN)
        assertEquals(45, g.profileRepo.byId(id)!!.workMinutes)
        assertEquals("深度专注", g.profileRepo.byId(id)!!.name)
        vm.setIntensity(ReminderIntensity.STRONG)
        assertEquals(ReminderIntensity.STRONG, g.settingsRepo.reminderIntensity.first())
    }

    /** Task 7 / #10:模式选择贯穿新建与编辑 —— 建库、编辑不改模式、编辑改模式均落库 */
    @Test fun modePersistsThroughCreateAndEdit() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "sv_mode")
        g.bootstrap()
        val vm = SettingsViewModel(g)
        val id = vm.createProfile("正计时", 45, 10, ProfileMode.COUNTUP)!!
        assertTrue(id > 0)
        assertEquals(ProfileMode.COUNTUP, g.profileRepo.byId(id)!!.mode)
        assertEquals(ProfileMode.COUNTUP, g.profileRepo.modeOf(id)) // modeOf 同源
        // 编辑不改模式(显式传原模式):模式不回退倒计时
        vm.editDurations(ProfileEntity(id, "x", 1, 1, 0, mode = ProfileMode.COUNTUP), 30, 5, ProfileMode.COUNTUP)
        assertEquals(ProfileMode.COUNTUP, g.profileRepo.byId(id)!!.mode)
        assertEquals(30, g.profileRepo.byId(id)!!.workMinutes)
        // 编辑切换模式:DB 跟随对话框选择
        vm.editDurations(ProfileEntity(id, "x", 1, 1, 0, mode = ProfileMode.COUNTUP), 30, 5, ProfileMode.COUNTDOWN)
        assertEquals(ProfileMode.COUNTDOWN, g.profileRepo.byId(id)!!.mode)
    }

    @Test fun editDurationsPolicy() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "sv2")
        g.bootstrap()
        val vm = SettingsViewModel(g)
        // #3 首装空库:自建一行后才谈得上改时长策略
        val id = vm.createProfile("专注", 25, 5, ProfileMode.COUNTDOWN)!!
        g.engine.restore(snap(EngineStatus.RUNNING, profileId = id))
        assertFalse(vm.editDurations(ProfileEntity(id, "a", 1, 1, 0), 30, 10, ProfileMode.COUNTDOWN)) // RUNNING 拒
        assertEquals(25, g.profileRepo.byId(id)!!.workMinutes) // IGNORED 不写(建时 25 保持)
        g.engine.restore(snap(EngineStatus.PAUSED, profileId = id))
        assertTrue(vm.editDurations(ProfileEntity(id, "a", 1, 1, 0), 30, 10, ProfileMode.COUNTDOWN)) // PAUSED 重开
        assertEquals(30, g.profileRepo.byId(id)!!.workMinutes)
    }

    @Test fun deletePolicy() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "sv3")
        g.bootstrap()
        val vm = SettingsViewModel(g)
        // #3 首装空库:先自建“最后一条”,拒删语义不变
        vm.createProfile("A", 25, 5, ProfileMode.COUNTDOWN)
        val only = g.profileRepo.profiles.first().first()
        assertFalse(vm.deleteProfile(only)) // 最后一条拒删
        vm.createProfile("B", 50, 10, ProfileMode.COUNTDOWN)
        g.engine.restore(snap(EngineStatus.PAUSED, profileId = only.id))
        assertTrue(vm.deleteProfile(only)) // 暂停中的活跃配置:先 reset 再删
        assertEquals(1, g.profileRepo.profiles.first().size)
    }

    /**
     * v2.1 Task 9:恢复计数含任务数 —— 走真实文件 Uri 的 restoreFrom 路径(界面「已从备份恢复 N 条记录」)。
     * 同时验证更高版本文件在界面层降级为失败提示(返回 null),不写库。
     */
    @Test fun restoreCountIncludesTasksAndRejectsNewerVersion() = runTest {
        // 文件库:restoreFrom 走 SAF 读流 + 事务,与真机路径一致(见 AppGraph 头注)
        val g = AppGraph(ctx, useInMemoryDb = false, storeFileName = "sv_restore")
        g.bootstrap()
        val vm = SettingsViewModel(g)
        val v2 = """{"version":2,"exportedAt":1,"profiles":[],"dailyTotals":[],
            "tasks":[{"id":1,"title":"写周报","done":0,"createdAt":1,"doneAt":null,"sortOrder":1}],
            "focusSessions":[{"id":1,"profileId":1,"startAt":1,"endAt":2,"taskId":1}]}"""
        val good = java.io.File(ctx.cacheDir, "restore-v2.json").apply { writeText(v2) }
        assertEquals(2, vm.restoreFrom(android.net.Uri.fromFile(good))) // 1 任务 + 1 段
        assertEquals("写周报", g.taskRepo.titleById(1L))
        assertEquals(1L, g.db.focusSessionDao().getAll().single().taskId)

        val v4 = """{"version":4,"exportedAt":1,"profiles":[],"dailyTotals":[],"tasks":[],"focusSessions":[]}"""
        val future = java.io.File(ctx.cacheDir, "restore-v4.json").apply { writeText(v4) }
        assertNull(vm.restoreFrom(android.net.Uri.fromFile(future)))
        assertEquals("写周报", g.taskRepo.titleById(1L)) // 库未被未来版本文件改动
    }
}
