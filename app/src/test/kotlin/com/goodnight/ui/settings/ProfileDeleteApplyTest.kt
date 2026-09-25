package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5:删除**写库的结局**在库上的表现 —— 无历史且未被引擎使用 → 真删;有历史 → 归档
 * (行保留、账一行不删);归档行不算「至少保留 1 个活跃时钟」的名额。
 *
 * 单独成文件:删除预告的纯计算在 `ProfileDeletePlanTest`,「正被引擎使用」的语义(不停机 +
 * 一律归档)在 `ProfileDeleteInUseTest`;三者合在一起会超过单文件 200 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeleteApplyTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_apply_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    private suspend fun session(profileId: Long, millis: Long = 25 * 60_000L) =
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = profileId, startAt = 0, endAt = millis)),
        )

    /** 归档:先造一段历史(有引用才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(clock: ProfileEntity) {
        session(clock.id)
        assertEquals(ProfileRemoval.Archived, g.profileRepo.removeOrArchive(clock.id))
    }

    /**
     * 复审修复:归档同样受「至少保留 1 个**活跃**时钟」约束 —— 归档是列表隐藏,把唯一的活跃
     * 时钟归档也会让活跃列表清零(首页开始键失效),所以一行不动。
     */
    @Test fun lastActiveClockIsNeitherArchivedNorDeleted() = runTest {
        val only = clock("唯一")
        session(only.id)

        assertNull("唯一活跃时钟不归档", vm().deleteProfile(only))
        assertFalse("行仍在且未归档", g.profileRepo.byId(only.id)!!.archived)
        assertEquals("账一行不动", 1, g.db.focusSessionDao().getAll().size)
    }

    /** 指针 3:有历史的时钟删除走归档 —— 行保留、历史账一行不删、不再调清账路径 */
    @Test fun deleteProfileArchivesHistoryAndKeepsRecords() = runTest {
        val p = clock("有历史")
        clock("另一个") // 活跃时钟 > 1,归档才被允许(复审修复:最后一个活跃时钟不归档)
        session(p.id, millis = 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-24", p.id, 30 * 60_000L)

        assertEquals("有历史 → 归档(本路径不发 stop)", ProfileRemoval.Archived, vm().deleteProfile(p))

        assertTrue("行保留并标记归档", g.profileRepo.byId(p.id)!!.archived)
        assertEquals("会话段一行不少", 1, g.db.focusSessionDao().getAll().size)
        assertEquals(
            "每日合计一行不少",
            30 * 60_000L,
            g.totalsRepo.profileTotals().first().first { it.profileId == p.id }.total,
        )
        assertTrue("归档后从管理页活跃流消失", g.profileRepo.observeAllActive().first().none { it.id == p.id })
    }

    /**
     * 指针 1 + 复审修复:归档行不算「至少保留 1 个」—— 界面上看不见的行不能替活跃时钟挡删除。
     * 活跃时钟有 2 个时,归档行既不占名额也不被这次删除影响。
     */
    @Test fun archivedClockDoesNotSatisfyKeepOneRule() = runTest {
        val dead = clock("旧")
        archive(dead)
        val live = clock("在用")
        val other = clock("另一个")

        assertEquals(
            "无历史 → 真删(归档行不参与「留一个」判定)",
            ProfileRemoval.Deleted,
            vm().deleteProfile(live),
        )
        assertNull("活跃时钟已删", g.profileRepo.byId(live.id))
        assertNotNull("留下的活跃时钟还在", g.profileRepo.byId(other.id))
        assertTrue("归档行未被动", g.profileRepo.byId(dead.id)!!.archived)
    }
}
