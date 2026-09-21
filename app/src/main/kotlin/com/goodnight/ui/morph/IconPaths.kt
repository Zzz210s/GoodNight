package com.goodnight.ui.morph

/**
 * 图标 path 数据单源(Compose 渲染唯一来源;24 栅格中心线几何,圆头由 Stroke cap/join 渲染)。
 * 被通知引用的 XML(drawable/ic_play 等)必须与对应常量逐字节一致——见 IconPathsTest 守卫。
 *
 * 策展规则(design spec D5):PLAY 为中心线三角(M/L/Z,无弧,与 PAUSE 共享 y 5..19 高度带,
 * 保证形变对两端端点语义对齐);其余常量直接取自既有 XML pathData(逐字节一致,不二次策展)。
 */
object IconPaths {
    /** 播放:中心线三角(顺时针,起点在左上;与 pause 双竖条同高 5..19,便于子路径就近配对) */
    const val PLAY = "M6.5,5 L19,12 L6.5,19 Z"
    /** 暂停:双竖条(两条子路径合并为一条 pathData;与 ic_pause.xml 两个 <path> 内容逐字节一致) */
    const val PAUSE = "M9,5 L9,19 M15,5 L15,19"
    /** 跳过:右竖条 + 右三角(与 ic_skip_next.xml 两个 <path> 内容逐字节一致) */
    const val SKIP = "M21,4 L21,20 M6.029,4.285 A2,2 0 0 0 3,6 L3,18 a2,2 0 0 0 3.029,1.715 l9.997,-5.998 a2,2 0 0 0 0.003,-3.432 z"
    /** 终止:圆角方形(与 ic_stop.xml pathData 逐字节一致) */
    const val STOP = "M5,3 h14 a2,2 0 0 1 2,2 v14 a2,2 0 0 1 -2,2 h-14 a2,2 0 0 1 -2,-2 v-14 a2,2 0 0 1 2,-2 z"
    /** 循环:双箭头 + 圆角回流线(与 ic_repeat.xml 四个 <path> 内容逐字节一致,静态图标) */
    const val REPEAT = "M17,2 l4,4 l-4,4 M3,11 v-1 a4,4 0 0 1 4,-4 h14 M7,22 l-4,-4 l4,-4 M21,13 v1 a4,4 0 0 1 -4,4 h-14"
    /** 设置:齿轮 + 内孔(与 ic_settings.xml 两个 <path> 内容逐字节一致,静态图标) */
    const val SETTINGS = "M9.671,4.136 a2.34,2.34 0 0 1 4.659,0 a2.34,2.34 0 0 0 3.319,1.915 a2.34,2.34 0 0 1 2.33,4.033 a2.34,2.34 0 0 0 0,3.831 a2.34,2.34 0 0 1 -2.33,4.033 a2.34,2.34 0 0 0 -3.319,1.915 a2.34,2.34 0 0 1 -4.659,0 a2.34,2.34 0 0 0 -3.32,-1.915 a2.34,2.34 0 0 1 -2.33,-4.033 a2.34,2.34 0 0 0 0,-3.831 a2.34,2.34 0 0 1 2.33,-4.033 a2.34,2.34 0 0 0 3.319,-1.915 z M12,9 a3,3 0 1 0 0.001,6 a3,3 0 1 0 -0.001,-6 z"
    /** 返回箭头:左箭头 + 横杆(与 ic_arrow_back.xml 两个 <path> 内容逐字节一致,静态图标) */
    const val BACK = "M12,19 l-7,-7 l7,-7 M19,12 L5,12"
    /** 汉堡菜单:三横线(v1.1 顶栏 actions 报表入口,静态图标,无 XML 对应) */
    const val MENU = "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18"
    /** 下拉展开箭头:V 形(v1.1 顶栏中央配置下拉指示,静态图标,无 XML 对应) */
    const val CHEVRON_DOWN = "M6,9 L12,15 L18,9"
    /** 对勾(v1.2 面板当前配置选中指示,静态图标,无 XML 对应) */
    const val CHECK = "M4.5,12.5 L10,18 L19.5,6.5"
    /** 加号(v1.3 时钟管理页标题栏新建入口,静态图标,无 XML 对应) */
    const val PLUS = "M12,5 L12,19 M5,12 L19,12"
    /** 垃圾桶(v1.4 时钟管理删除模式入口,静态图标,无 XML 对应) */
    const val TRASH = "M5,7 L19,7 M9,7 L9,5 L15,5 L15,7 M7,7 L8,19 L16,19 L17,7 M10,10 L10,16 M14,10 L14,16"
    /** 空闲相位:月牙(Lucide moon 中心线,静态图标,首页计时模块状态行) */
    const val PHASE_IDLE = "M12,3 a6,6 0 0 0 9,9 a9,9 0 1 1 -9,-9 z"
    /** 工作中相位:火焰(Lucide flame 中心线,静态图标) */
    const val PHASE_WORK = "M8.5,14.5 A2.5,2.5 0 0 0 11,12 c0,-1.38 -0.5,-2 -1,-3 -1.072,-2.143 -0.224,-4.054 2,-6 0.5,2.5 2,4.9 4,6.5 2,1.6 3,3.5 3,5.5 a7,7 0 1 1 -14,0 c0,-1.153 0.433,-2.294 1,-3 a2.5,2.5 0 0 0 2.5,2.5 z"
    /** 休息中相位:咖啡杯(Lucide coffee 中心线,静态图标) */
    const val PHASE_REST = "M17,8 h1 a4,4 0 0 1 0,8 h-1 M3,8 h14 v6 a4,4 0 0 1 -4,4 h-6 a4,4 0 0 1 -4,-4 z M6,2 L6,4 M10,2 L10,4 M14,2 L14,4"

    /** 全部图标,供测试遍历(24 栅格合法性/可解析性守卫)。 */
    val ALL: List<String> = listOf(PLAY, PAUSE, SKIP, STOP, REPEAT, SETTINGS, BACK, MENU, CHEVRON_DOWN, CHECK, PLUS, TRASH, PHASE_IDLE, PHASE_WORK, PHASE_REST)
}
