package com.goodnight.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1 Task 6(评审修复):「进行中」与「已完成」是两条独立 Room 流,勾选完成时同一 id 会先后
 * (两帧之间甚至同时)出现在两条流里,而两段共处**同一个 LazyColumn 的 key 空间** ——
 * 不带段前缀就撞 key,Compose 抛 `Key "..." was already used` 硬崩。
 *
 * 项目没有 Compose UI 测试依赖(androidTest 的 ui-test-junit4 未引入),故这里锁住
 * 「段前缀让 key 从构造上全局唯一」这一不变量:旧实现(两段都用裸 id)下第一个用例必红。
 */
class TaskRowKeyTest {
    @Test fun sameIdInBothSectionsNeverCollides() {
        val ids = listOf(1L, 2L, 7L, Long.MAX_VALUE)
        // 勾选完成的两帧之间:同一 id 可能同时在两条流里 —— 合并后的 key 必须仍唯一
        val keys = ids.map(::activeRowKey) + ids.map(::doneRowKey)
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(2, listOf(activeRowKey(42L), doneRowKey(42L)).distinct().size)
    }

    @Test fun keysAreStablePerIdAndSection() {
        assertEquals("a7", activeRowKey(7L))
        assertEquals("d7", doneRowKey(7L))
        assertEquals(activeRowKey(7L), activeRowKey(7L))
    }
}
