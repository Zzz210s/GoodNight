package com.goodnight.ui.morph

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单源守卫(D5):被通知引用的 XML(drawable/ic_play 等)的 pathData 全集必须与 Kotlin 常量
 * 逐字节一致,防两源漂移。单个 XML 文件可含多个 <path>,按文档顺序以单空格拼接后比对。
 */
class IconPathsTest {
    /** 定位 drawable 源文件:兼容测试工作目录为模块根(app/)或仓库根两种情形。 */
    private fun drawableXml(name: String): File {
        val candidates = listOf(
            File("app/src/main/res/drawable/$name.xml"),
            File("src/main/res/drawable/$name.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$name.xml not found under res/drawable (cwd=${File(".").absolutePath})")
    }

    /** 提取文件中全部 pathData 属性值(文档序),以单空格拼接为一条逻辑 pathData。 */
    private fun xmlPathData(name: String): String {
        val s = drawableXml(name).readText()
        val all = Regex("android:pathData=\"([^\"]*)\"").findAll(s)
            .map { it.groupValues[1] }
            .toList()
        require(all.isNotEmpty()) { "no pathData in $name.xml" }
        return all.joinToString(" ")
    }

    @Test fun notificationReferencedXmlMatchesKotlinSource() {
        assertEquals(IconPaths.PLAY, xmlPathData("ic_play"))
        assertEquals(IconPaths.PAUSE, xmlPathData("ic_pause"))
        assertEquals(IconPaths.SKIP, xmlPathData("ic_skip_next"))
        assertEquals(IconPaths.STOP, xmlPathData("ic_stop"))
    }

    @Test fun allIconsLiveOn24GridAndParseable() {
        IconPaths.ALL.forEach { d ->
            assertTrue("nonempty", d.isNotBlank())
            Regex("\\d+(\\.\\d+)?").findAll(d).forEach { m ->
                val v = m.value.toDouble()
                assertTrue("within 0..24: $v in $d", v in 0.0..24.0)
            }
        }
    }
}
