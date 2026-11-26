-keep class com.zerointerrupt.clicker.shizuku.TouchMuxUserService { *; }
-keep class com.zerointerrupt.clicker.shizuku.RootMuxServerMain { *; }
-keep class com.zerointerrupt.clicker.shizuku.RootBinderProvider { *; }
-keep class com.zerointerrupt.clicker.ITouchMuxService** { *; }
-keep class com.zerointerrupt.clicker.ITouchMuxCallback** { *; }
-keep class com.zerointerrupt.clicker.ClickTargetParceled** { *; }
-keep class com.zerointerrupt.clicker.QuickTileService { *; }
-keep class com.zerointerrupt.clicker.automation.AutomationReceiver { *; }
-keep class rikka.shizuku.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
