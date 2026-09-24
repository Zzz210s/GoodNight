package com.goodnight.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.data.db.FocusSessionEntity
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
 * v2.2 Task 5(复审第四轮 W3):批量删除的**混合批次** —— 仓内既有用例清一色单元素列表,漏掉了
 * 「一次确认里既有归档行又有真删行」与本文件第 3 例的**逐行递减门控**。
 *
 * 门控是逐行判的(`countActive() <= 1` 时一行不动),所以同一批里前面的行会改变后面的判定:
 * 归档同样让活跃数减一。第 3 例直接喂三行(绕过对话框计划)复现「前两行删掉、最后一行被留下」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = ProfileDeleteBatchTest.TestApp::class)
class ProfileDeleteBatchTest {
    /** 空 onCreate:注入受控 AppGraph(空闲态删除会走 TimerNotifIdle,上下文必须是 GoodNightApp) */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val app = ctx as GoodNightApp
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_batch_${testName.methodName}")
        app.graph = g
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    /** 混合批次一:在用 + 不在用,各无历史 → 前者归档、后者真删,第三个活跃时钟不动 */
    @Test fun mixedBatchArchivesInUseAndDeletesUnused() = runBlocking {
        val inUse = clock("在用")
        val clean = clock("无历史")
        val spare = clock("留着的")
        g.engine.restore(snapOf(status = EngineStatus.PAUSED).copy(profileId = inUse.id))

        vm().deleteProfiles(listOf(inUse, clean))

        assertTrue("在用 → 归档", g.profileRepo.byId(inUse.id)!!.archived)
        assertNull("不在用且无历史 → 真删", g.profileRepo.byId(clean.id))
        assertNotNull("没选中的活跃时钟一行不动", g.profileRepo.byId(spare.id))
        assertEquals("引擎仍暂停在它身上(归档不停机)", inUse.id, g.engine.snapshot.value?.profileId)
        assertEquals("活跃数 = 留着的那个(归档不算活跃)", 1, g.profileRepo.countActive())
    }

    /** 混合批次二:有历史 + 无历史,都不在用 → 前者归档(账保留)、后者真删 */
    @Test fun mixedBatchArchivesHistoryAndDeletesUnused() = runBlocking {
        val hist = clock("有历史")
        val clean = clock("无历史")
        clock("留着的")
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = hist.id, startAt = 0, endAt = 30 * 60_000L)),
        )

        vm().deleteProfiles(listOf(hist, clean))

        assertTrue("有历史 → 归档", g.profileRepo.byId(hist.id)!!.archived)
        assertEquals("归档账一行不删", 1, g.db.focusSessionDao().getAll().size)
        assertNull("无历史 → 真删", g.profileRepo.byId(clean.id))
        assertEquals(1, g.profileRepo.countActive())
    }

    /**
     * 混合批次三(逐行递减门控):三个无历史、未在用的活跃时钟整批选中 → 前两行删掉、最后一行的
     * `countActive() <= 1` 命中,一行不动 —— 不能把活跃列表清零。
     */
    @Test fun perRowGateKeepsLastActiveClockOfAllSelected() = runBlocking {
        val first = clock("一")
        val second = clock("二")
        val last = clock("三")

        vm().deleteProfiles(listOf(first, second, last))

        assertNull("第一行:活跃 3 → 删", g.profileRepo.byId(first.id))
        assertNull("第二行:活跃 2 → 删", g.profileRepo.byId(second.id))
        val kept = g.profileRepo.byId(last.id)
        assertNotNull("第三行被门控留下", kept)
        assertTrue("留下 ≠ 归档(一行未动)", !kept!!.archived)
        assertEquals("活跃数停在 1", 1, g.profileRepo.countActive())
    }
}