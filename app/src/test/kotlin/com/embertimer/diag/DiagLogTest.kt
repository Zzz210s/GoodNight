package com.embertimer.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.12.3 诊断日志增强:落盘 + 环境标签。
 * 负计时/通知消失发生在进程被冻结之后,内存环形缓冲与 logcat 都留不住,必须落盘才可事后取证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun debugBuildEnablesAndLocatesFile() {
        DiagLog.markEnabled(ctx)
        assertTrue("debug 变体应启用诊断日志", DiagLog.enabled)
        val path = DiagLog.filePath()
        assertNotNull("应定位到落盘文件", path)
        assertTrue("落盘文件名应为 diag.log", path!!.endsWith("diag.log"))
    }

    @Test
    fun entriesAppendToFileAndRingBuffer() {
        DiagLog.markEnabled(ctx)
        val file = java.io.File(DiagLog.filePath()!!)
        file.delete()
        DiagLog.clear()
        val marker = "迟到=" + System.nanoTime()
        DiagLog.add("Tick", "到期推进 $marker")
        DiagLog.add("Notif", "到期钳制重发 $marker")
        val lines = file.readLines()
        val mine = lines.filter { it.contains(marker) }
        assertEquals("两次 add 应恰好追加两行(其余行来自应用启动日志)", 2, mine.size)
        assertTrue("落盘行应含时间戳前缀", mine[0].matches(Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} Tick \| .*""")))
        assertEquals(2, DiagLog.recent(30).count { it.text.contains(marker) })
        DiagLog.clear()
        assertEquals(0, DiagLog.recent(30).count { it.text.contains(marker) })
    }

    @Test
    fun envReportsIdleAndScreen() {
        DiagLog.markEnabled(ctx)
        val env = DiagLog.env()
        assertTrue("环境标签应含 Doze 空闲位:$env", env.contains("idle="))
        assertTrue("环境标签应含屏幕交互位:$env", env.contains("screen="))
    }
}
