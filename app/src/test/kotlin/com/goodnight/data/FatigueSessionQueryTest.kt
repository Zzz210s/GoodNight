package com.goodnight.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.FocusSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.14.0 疲劳提醒的数据来源:按**任务(profile)**取最近工作段 —— 倒序、限量、只含本任务。
 * 这三条决定了"同一任务连续工作"的语义,钉死在数据层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FatigueSessionQueryTest {
    private lateinit var db: GoodNightDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, GoodNightDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun onlyOwnProfileNewestFirst() = runTest {
        val dao = db.focusSessionDao()
        dao.insertAll(
            listOf(
                FocusSessionEntity(profileId = 1, startAt = 0, endAt = 1_000),
                FocusSessionEntity(profileId = 2, startAt = 2_000, endAt = 3_000),
                FocusSessionEntity(profileId = 1, startAt = 4_000, endAt = 5_000),
            ),
        )
        val rows = dao.recentForProfile(profileId = 1, limit = 10)
        assertEquals("只取本任务的段,且按开始时间倒序", listOf(4_000L, 0L), rows.map { it.startAt })
    }

    @Test fun limitKeepsNewest() = runTest {
        val dao = db.focusSessionDao()
        dao.insertAll(
            (1..4).map { i -> FocusSessionEntity(profileId = 7, startAt = i * 1_000L, endAt = i * 1_000L + 500) },
        )
        val rows = dao.recentForProfile(profileId = 7, limit = 2)
        assertEquals(listOf(4_000L, 3_000L), rows.map { it.startAt })
    }

    @Test fun noSessionsYieldsEmpty() = runTest {
        assertEquals(emptyList<FocusSessionEntity>(), db.focusSessionDao().recentForProfile(9, 10))
    }
}
