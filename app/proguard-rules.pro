# 保留源文件与行号信息，供 Xposed 日志与异常崩溃栈排查
-keepattributes SourceFile,LineNumberTable

# Xposed 模块入口：由 LSPosed 框架通过 java_init.list 反射调用无参构造函数加载
-keep class com.hchen.superlyric.HookEntrance {
    <init>();
}

# 动态加载的模块与 Provider：HookEntrance 通过 HookMaps 映射表反射类名并调用无参构造实例化
-keep class * extends com.hchen.hooktool.ModuleEntrance {
    <init>();
}
-keep class * extends com.hchen.hooktool.AbsModule {
    <init>();
}

# 开放 API 与跨进程通信：AIDL 接口、Binder Stub 与 Parcelable 序列化模型
-keep class com.hchen.superlyricapi.** { *; }

# Dexkit 磁盘缓存数据类
-keep class com.hchen.dexkitcache.DexkitCache$MemberData { *; }

# Spotify 在线歌词接口 DTO 仅由 Gson 反射填充：保留字段以防 R8 判定未写入导致解析失效
-keepclassmembers class com.hchen.superlyric.provider.spotify.SpotifyLyricAnalysis$Json* {
    <init>();
    <fields>;
}

# 隐藏 API / 仅编译依赖警告抑制
-dontwarn android.os.ServiceManager
-dontwarn de.robv.android.xposed.XposedHelpers