# Preserve metadata used by reflection-heavy code paths and stack traces.
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Preserve the app's Firebase/Gson serializable models.
-keep class online.monarchlabs.sentinel.models.** { *; }
-keepclassmembers class online.monarchlabs.sentinel.models.** {
	public <init>();
	public *;
}
-keep class online.monarchlabs.sentinel.AppInfo { *; }
-keep class online.monarchlabs.sentinel.AppStatusEvent { *; }
-keep class online.monarchlabs.sentinel.ChildDevice { *; }
-keep class online.monarchlabs.sentinel.FocusModePreset { *; }
-keep class online.monarchlabs.sentinel.Task { *; }
-keep class online.monarchlabs.sentinel.UsageSnapshot { *; }
-keep class online.monarchlabs.sentinel.BlockCommand { *; }
-keep class online.monarchlabs.sentinel.utils.ParentUsageCacheManager$CacheEntry { *; }
-keep class online.monarchlabs.sentinel.utils.ChildUsageLedgerManager$* { *; }

# Keep Gson-annotated fields stable if annotations are added later.
-keepclassmembers class * {
	@com.google.gson.annotations.SerializedName <fields>;
}

# Gson rules to preserve generic signatures for TypeToken and serialization
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Assistant models serialised via Gson / SharedPreferences.
-keep class online.monarchlabs.sentinel.assistant.history.** { *; }
-keep class online.monarchlabs.sentinel.assistant.execution.AssistantCommandOutbox { *; }
-keep class online.monarchlabs.sentinel.assistant.execution.AssistantCommandOutbox$PendingCommand { *; }
-keep class online.monarchlabs.sentinel.assistant.context.** { *; }

# Reduce noisy logging in release builds.
-assumenosideeffects class android.util.Log {
	public static int d(java.lang.String, java.lang.String);
	public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
	public static int v(java.lang.String, java.lang.String);
	public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
	public static int i(java.lang.String, java.lang.String);
	public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
	public static int w(java.lang.String, java.lang.String);
	public static int w(java.lang.String, java.lang.String, java.lang.Throwable);
}
