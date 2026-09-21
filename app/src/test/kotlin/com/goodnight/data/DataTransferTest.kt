package com.goodnight.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.DailyTotalEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DataTransferTest {
    private lateinit var src: GoodNightDatabase
    private lateinit var dst: GoodNightDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        src = Room.inMemoryDatabaseBuilder(ctx, GoodNightDatabase::class.java).allowMainThreadQueries().build()
        dst = Room.inMemoryDatabaseBuilder(ctx, GoodNightDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { src.close(); dst.close() }

    @Test fun roundTripPreservesRows() = runTest {
        val pid = src.profileDao().insert(ProfileEntity(name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 1L, mode = 0))
        src.dailyTotalDao().upsert(DailyTotalEntity("2026-09-06", pid, 3_600_000L, 2L))
        src.focusSessionDao().insertAll(listOf(FocusSessionEntity(profileId = pid, startAt = 100L, endAt = 200L)))

        val json = DataTransfer.exportJson(src)
        val counts = DataTransfer.importJson(dst, json)

        assertEquals(1, counts.profiles)
        assertEquals(1, counts.dailyTotals)
        assertEquals(1, counts.sessions)
        assertEquals(1, dst.profileDao().getAll().size)
        assertEquals(1, dst.dailyTotalDao().getAll().size)
        assertEquals(1, dst.focusSessionDao().getAll().size)
        assertEquals("番茄", dst.profileDao().getAll().first().name)
        assertEquals(3_600_000L, dst.dailyTotalDao().getAll().first().workMillis)
    }
}
