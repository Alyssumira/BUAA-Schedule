# 北航课程表 release 混淆规则
#
# 说明：大部分第三方依赖（Room / kotlinx.serialization / Compose）自带 consumer rules，
# 下面只做兜底，避免 release 构建把编译期生成的序列化器/Room 实现裁掉后，
# 备份、分享口令、数据库访问在混淆包上才暴露的运行时崩溃。

# ---- kotlinx.serialization ----
# @Serializable 会在编译期为每个类生成 serializer；备份与分享口令都依赖它。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.buaa.schedule.**$$serializer { *; }
-keepclassmembers class com.buaa.schedule.** {
    *** Companion;
}
-keepclasseswithmembers class com.buaa.schedule.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Room ----
# 只有 RoomDatabase 子类是反射实例化的（RoomDatabase.Builder 用 Class.forName("<名>_Impl")），
# 实体不是：实测 app/build/generated/ksp/release 里的 CourseDao_Impl 直接 new CourseEntity(...)、
# 经属性 getter 读字段，全程没有对实体成员的名字反射 —— 所以 `{ *; }` 是过度 keep：
# 它把 166 个成员（7 个自有实体 + 7 个 WorkManager 内部实体）的 getter / copy() / copy$default() /
# componentN() / $stable 全部钉死，release dex 多背 3,800 字节（2,546,506 → 2,542,706 压缩后）。
# 收窄后仍钉 <init>(...)：Room 生成的 bind() 依赖构造参数表的顺序，签名钉住最省事；
# 且这 166 个成员里没有任何一个是 kotlinx.serialization 字段（@Serializable 全在
# data/backup、data/import、widget、update、reminder 的 DTO 上，实体一个都不是）。
# 按名字反射这一族今天**不是零**（旧写法那句"全仓唯一的 Class.forName 是 IslandDiagnostics"已过期，
# 台账 #339 那批散文核对时现量）：尺是 `grep -rn "getMethod(\|getDeclaredMethod(\|Class.forName(" app/src/main/java`，
# 实到 9 行、2 枚文件 —— IslandDiagnostics.kt 取 android.os.SystemProperties（2 行），
# ReminderNotifications.kt 反射 Notification.Builder / Notification 的 setShortCriticalText /
# getShortCriticalText / hasPromotableCharacteristics / isRequestPromotedOngoing（7 行）。
# 九处的目标类**全是平台类**（android.os.*），没有一处指向本应用自己的实体或 DTO 的成员名，
# 而 R8 收缩的是应用侧的类，平台侧本来就不在这份规则的射程里 ⇒ 上面那条收窄的结论不受影响。
# 这一句是那条结论的护栏句，所以改它之前先把那把尺重跑一遍，别把"没有"抄回来。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * {
    <init>(...);
}
-dontwarn androidx.room.paging.**

# ---- Kotlin metadata（R5 F-C5）----
# Kotlin 2.3.10 写出的 kotlin.Metadata 版本高于 AGP 8.13.0 内置 R8 的认知上限，
# release 构建每次都刷一批 "Version of Kotlin ... is not supported"。
# 只静音这一条，不要用 -ignorewarnings 把真正的 keep 冲突也压掉；
# 升级 AGP 到内置较新 R8 的版本后请删除本条。
-dontwarn kotlin.Metadata
