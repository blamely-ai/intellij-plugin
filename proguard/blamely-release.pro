# Obfuscation for the composed plugin JAR (-Pblamely.obfuscate=true).
# Names referenced from plugin.xml, persisted settings, and Gson must stay stable.

-dontshrink
-dontoptimize

-target 17

# Required for JVM bytecode verification (Java 7+); omitting breaks searchable-options / runtime.
-keepattributes StackMapTable,KotlinMetadata,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

-dontnote kotlin.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**

-dontwarn com.intellij.**
-dontwarn git4idea.**
-dontwarn org.jetbrains.**

# Platform pulls in many optional paths; keep the build practical.
-ignorewarnings

# plugin.xml / extension points load types by fully qualified name — never rename plugin classes.
-keepnames class ai.blamely.** { *; }

# Persisted settings + Gson models (field names must stay stable)
-keep class ai.blamely.settings.BlamelySettings { *; }
-keep class ai.blamely.settings.BlamelySettings$State { *; }
-keep class ai.blamely.settings.BlamelyConfigurable { *; }
-keep class ai.blamely.core.BlameMapService { *; }
-keep class ai.blamely.core.BranchSessionLifecycleService { *; }
-keep class ai.blamely.core.TraceStoreService { *; }
-keep class ai.blamely.ui.BlameDecorations { *; }
-keep class ai.blamely.cli.** { *; }
-keep class ai.blamely.completion.CompletionDetector { *; }

-keep interface ai.blamely.core.BlameUpdateListener { *; }

# Gson / JSON on disk
-keep class ai.blamely.persistence.HomeBranchSession { *; }
-keep class ai.blamely.persistence.StashLinkEntry { *; }
-keep class ai.blamely.persistence.BlameSerializer$SessionData { *; }

