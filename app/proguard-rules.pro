# EmberTimer R8 规则(v1.11.0:重新启用 minify + 资源收缩)
#
# 历史说明:v1.9.x 曾因"点开闪退"关闭 R8,后经真机取证确认真正原因是
# notification_actions.xml 里 android:tint="?android:attr/..." 在 RemoteViews 中 inflate 失败,
# 与 R8 无关。修复布局后 R8 可以安全启用。

# --- WorkManager 通过类名反射实例化 Worker / WorkerFactory ---
-keep class com.goodnight.data.AutoBackupWorker { *; }
-keepnames class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.WorkerParameters { *; }

# --- 应用入口 / 组件(manifest 声明,R8 一般会自动保留,显式声明更稳) ---
-keep class com.goodnight.GoodNightApp { *; }
-keep class com.goodnight.MainActivity { *; }
-keep class com.goodnight.service.** { *; }

# --- 通知/RemoteViews:布局与图标按资源 id 引用,保类保方法名以便系统反射调用 ---
-keep class androidx.core.app.NotificationCompat** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# --- Room 实体与 DAO(使用生成代码访问字段) ---
-keep class com.goodnight.data.db.** { *; }

# --- kotlinx.coroutines 的调试/内部名不必保留 ---
-dontwarn kotlinx.coroutines.**

# --- 通用:保留必要的注解与签名 ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
