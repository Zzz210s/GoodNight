package com.embertimer.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.12.2 **仅 debug 构建**的诊断日志;v1.12.3 增强为**落盘 + 双通道**。
 *
 * 为什么落盘:负计时/通知消失这类问题往往发生在屏幕关闭、进程被 OEM 冻结之后,
 * 而 logcat 环形缓冲(本机 256 KiB)几分钟就被系统日志冲掉,内存环形缓冲(80 条)也会随进程消失 ——
 * 事后无从取证。现在每条同时写:
 *   1) logcat(tag `EmberDiag`,便于 adb 实时跟)
 *   2) `Android/data/com.embertimer/files/diag.log`(adb pull 可取,超 512 KiB 滚动为 diag.log.1)
 *
 * 正式版:`markEnabled()` 检测到非 debuggable 时 `enabled=false`,add() 直接返回(零开销、不写盘)。
 */
object DiagLog {
    data class Entry(val at: Long, val tag: String, val text: String)

    @Volatile var enabled: Boolean = false
        private set

    private const val CAP = 80
    private const val FILE_CAP = 512 * 1024L
    private const val TAG_LOG = "EmberDiag"
    private const val FILE_NAME = "diag.log"

    private val buf = ArrayDeque<Entry>()

    @Volatile private var appContext: Context? = null

    @Volatile private var file: File? = null

    /** 应用启动时调用一次:按 FLAG_DEBUGGABLE 决定是否启用并定位落盘文件 */
    fun markEnabled(context: Context) {
        appContext = context.applicationContext
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!enabled) return
        runCatching { file = File(context.getExternalFilesDir(null), FILE_NAME) }
    }

    fun add(tag: String, text: String) {
        if (!enabled) return
        val at = System.currentTimeMillis()
        android.util.Log.i(TAG_LOG, "$tag | $text")
        synchronized(buf) {
            buf.addLast(Entry(at, tag, text))
            while (buf.size > CAP) buf.removeFirst()
        }
        appendToFile(at, tag, text)
    }

    private fun appendToFile(at: Long, tag: String, text: String) {
        runCatching {
            val f = file ?: return
            if (f.length() > FILE_CAP) {
                val prev = File(f.parentFile, "$FILE_NAME.1")
                prev.delete()
                f.renameTo(prev)
            }
            f.appendText("${stamp(at)} $tag | $text\n")
        }
    }

    /** 运行环境标签(Doze 空闲 / 屏幕交互)—— 负计时窗口几乎都发生在 idle=true,screen=false */
    fun env(): String {
        val pm = appContext?.getSystemService(PowerManager::class.java) ?: return "idle=? screen=?"
        return "idle=${pm.isDeviceIdleMode} screen=${pm.isInteractive}"
    }

    /** 落盘文件路径(诊断面板展示,便于 adb pull) */
    fun filePath(): String? = file?.absolutePath

    /** 最近 [limit] 条,按时间正序(界面自上而下 = 由旧到新) */
    fun recent(limit: Int = 30): List<Entry> = synchronized(buf) { buf.toList().takeLast(limit) }

    fun clear() = synchronized(buf) { buf.clear() }

    fun format(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

    fun stamp(at: Long): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(at))
}
