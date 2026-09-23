package com.goodnight.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.goodnight.data.DailyTotalRepository
import com.goodnight.data.ProfileRepository
import com.goodnight.data.RuntimeStateStore
import com.goodnight.data.SettingsRepository
import com.goodnight.data.TaskRepository
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.SystemTimeProvider
import com.goodnight.timer.TimerEngine
import com.goodnight.ui.home.HomeViewModel
import com.goodnight.ui.report.ReportViewModel
import com.goodnight.ui.settings.SettingsViewModel
import com.goodnight.ui.tasks.TaskListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

private val directExecutor = Executor { it.run() }

class AppGraph(
    context: Context,
    useInMemoryDb: Boolean = false,
    storeFileName: String = "ember_settings",
) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time = SystemTimeProvider()

    val db: GoodNightDatabase = if (useInMemoryDb)
        // Robolectric legacy SQLite 影子按线程登记连接指针,Room 池化连接跨 arch_disk_io 线程
        // 复用会触发 Illegal connection pointer;测试路径用直通执行器把查询钉在调用线程上
        Room.inMemoryDatabaseBuilder(context, GoodNightDatabase::class.java)
            .addMigrations(GoodNightDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    else GoodNightDatabase.build(context)

    val profileRepo = ProfileRepository(db.profileDao(), time)
    val totalsRepo = DailyTotalRepository(db, db.dailyTotalDao(), db.focusSessionDao(), time)
    val taskRepo = TaskRepository(db)

    private val ds = PreferenceDataStoreFactory.create(scope = appScope) {
        context.preferencesDataStoreFile(storeFileName)
    }
    val settingsRepo = SettingsRepository(ds)
    val runtimeStore = RuntimeStateStore(ds)

    val engine = TimerEngine(
        time, appScope,
        persist = { runtimeStore.save(it) },
        // F7:事件缓冲溢出丢弃必须可观测;引擎自身保持无 Android 依赖(纯 JVM 可测)
        onEventDropped = { Log.w("TimerEngine", "engine event dropped: $it") },
    )

    val alarmScheduler = com.goodnight.service.AlarmScheduler(context, time)

    /**
     * v1.12.0:引擎协调器(进程级)。在 [GoodNightApp.onCreate] 里 [EngineCoordinator.install]
     * —— 事件订阅随图建立,因此**只有闹钟广播唤起的进程**也能直接推进阶段(不依赖前台服务)。
     */
    val coordinator = com.goodnight.service.EngineCoordinator(this)
    val reminderPlayer = com.goodnight.service.ReminderPlayer(context)

    val vmFactory = viewModelFactory {
        // graph 即 this@AppGraph,闭包捕获(字面 graph 无成员可解析,需显式标签)
        initializer { HomeViewModel(this@AppGraph) }
        initializer { SettingsViewModel(this@AppGraph) }
        initializer { ReportViewModel(this@AppGraph) }
        initializer { TaskListViewModel(this@AppGraph) }
    }

    // #3:首装不再种默认配置,空库由主页空态引导;bootstrap 只负责引擎冷启动恢复
    suspend fun bootstrap() {
        engine.restore(runtimeStore.flow.first())
    }

    fun bootstrapAsync() {
        appScope.launch { bootstrap() }
    }
}
