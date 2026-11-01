-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Palleria launches this screen by its explicit component name at runtime.
-keep class com.yunfie.illustia.lsposed.PluginSettingsActivity { *; }
-keep class com.yunfie.illustia.lsposed.api.** { *; }
