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
# 实体构造函数由生成的 Dao_Impl / AppDatabase_Impl 反射调用。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin metadata（R5 F-C5）----
# Kotlin 2.3.10 写出的 kotlin.Metadata 版本高于 AGP 8.13.0 内置 R8 的认知上限，
# release 构建每次都刷一批 "Version of Kotlin ... is not supported"。
# 只静音这一条，不要用 -ignorewarnings 把真正的 keep 冲突也压掉；
# 升级 AGP 到内置较新 R8 的版本后请删除本条。
-dontwarn kotlin.Metadata
