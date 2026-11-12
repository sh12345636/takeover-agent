# LSPosed/Xposed 模块必须保留入口与被 Hook 框架反射的类
-keep class takeover.agent.ModuleEntry { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# 被 system_server / 目标进程反射调用的对象不混淆
-keep class takeover.agent.hooks.** { *; }
-keep class takeover.agent.root.** { *; }

# native daemon 名称引用
-keepclasseswithmembernames class * { native <methods>; }
