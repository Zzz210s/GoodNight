package com.goodnight.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.timer.EngineEvent
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// 用裸 Application 绕过 GoodNightApp.onCreate 的真实 AppGraph 装配(后台 Room/DataStore 写
// 会在环境重置后泄漏未捕获异常,污染其他测试);本测试自建受控 graph
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraphTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var graph: AppGraph? = null

    /** appScope/DataStore/Room 均为真实线程,必须显式拆除,
     *  否则泄漏的后台写会在 Robolectric 环境重置后炸掉并污染后续测试类 */
    @After fun tearDown() {
        val g = graph ?: return
        runBlocking {
            g.appScope.coroutineContext.job.cancelAndJoin()
            runCatching { g.db.close() }
        }
    }

    @Test fun bootstrapSkipsSeedingAndEnginePersists() = runTest {
        val g1 = AppGraph(ctx, useInMemoryDb = true, storeFileName = "graph_persist").also { graph = it }
        g1.bootstrap()
        // #3 首装空库:bootstrap 只做引擎恢复,不种默认配置
        assertEquals(0, g1.profileRepo.count())
        // 用非 Flow 访问取行:observeAll 会启动 Room Invalidation Tracker,
        // 其后台刷新线程在 Robolectric 按线程登记的 legacy SQLite 影子下会 Illegal connection pointer
        val id = g1.profileRepo.create("测试", 25, 5)!!
        g1.engine.restore(null)
        g1.engine.start(id, 60_000L, 30_000L)
        // appScope 是真实 Default dispatcher,轮询等待 persist 完成
        val deadline = System.currentTimeMillis() + 2_000
        var saved: RuntimeSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            saved = g1.runtimeStore.flow.first()
            if (saved != null && saved.profileId == id) break
            Thread.sleep(50)
        }
        assertNotNull(saved)
        assertEquals(id, saved!!.profileId)
        // 冷启动恢复路径由 SettingsStoreTest.runtimeStateRoundTrip + RuntimeStateCodecTest 覆盖
    }
}
