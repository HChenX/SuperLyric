# 保留源文件名与行号，供日志和异常栈定位
-keepattributes SourceFile,LineNumberTable

# Xposed 模块入口；LSPosed 通过 java_init.list 反射加载
-keep class com.hchen.superlyric.HookEntrance {
    <init>();
}

# 动态加载的模块与 Provider；HookEntrance 按 HookMaps 反射实例化
-keep class * extends com.hchen.hooktool.ModuleEntrance {
    <init>();
}
-keep class * extends com.hchen.hooktool.AbsModule {
    <init>();
}

# 4.0 API、Binder 与 Parcelable 模型
-keep class com.hchen.superlyricapi.** { *; }

# DexKit 磁盘缓存数据类
-keep class com.hchen.dexkitcache.DexkitCache$MemberData { *; }

# Spotify 歌词 DTO 由 Gson 反射填充；保留字段防止 R8 移除
-keepclassmembers class com.hchen.superlyric.kernel.parse.SpotifyLyricParser$Json* {
    <init>();
    <fields>;
}

# 隐藏 API 与仅编译依赖的警告抑制
-dontwarn android.os.ServiceManager
-dontwarn de.robv.android.xposed.XposedHelpers