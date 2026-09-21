package com.goodnight.timer

import android.content.Context
import com.goodnight.R
import java.util.Locale

object DurationFormat {
    /** "X 小时 Y 分钟"; 0 -> "0 分钟"; 不足 1 分钟向上取整(59,999ms -> "1 分钟") */
    fun hm(millis: Long): String {
        val totalMinutes = (millis + 59_999) / 60_000 // 向上取整
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "$m 分钟"
            else -> "$h 小时 $m 分钟"
        }
    }

    /** 本地化 "X 小时 Y 分钟"(en:"1h 30m");非通知场景默认仍走 [hm] 中文 */
    fun localizedHm(context: Context, millis: Long): String {
        val totalMinutes = (millis + 59_999) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h == 0L) context.getString(R.string.duration_m, m)
        else context.getString(R.string.duration_hm, h, m)
    }

    /** "mm:ss",超过一小时也继续累计分钟;负数钳为 0;Locale.ROOT 防局部阿拉伯数字(Task 4 遗留,收尾落地) */
    fun ms(millis: Long): String {
        val t = (millis / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(Locale.ROOT, t / 60, t % 60)
    }
}
