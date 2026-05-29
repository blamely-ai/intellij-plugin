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

# plugin.xml + persisted settings
-keep class ai.blamely.settings.BlamelySettings { *; }
-keep class ai.blamely.settings.BlamelySettings$State { *; }
-keep class ai.blamely.settings.BlamelyConfigurable { *; }
-keep class ai.blamely.core.BlameMapService { *; }
-keep class ai.blamely.core.BranchSessionLifecycleService { *; }
-keep class ai.blamely.core.TraceStoreService { *; }
-keep class ai.blamely.ui.BlameDecorations { *; }
-keep class ai.blamely.BlamelyStartupActivity { *; }
-keep class ai.blamely.ui.BlamelyToolWindowFactory { *; }
-keep class ai.blamely.ui.BlamelyStatusBarWidgetFactory { *; }
-keep class ai.blamely.actions.ShowBlameAction { *; }
-keep class ai.blamely.completion.CompletionDetector { *; }
-keep class ai.blamely.cli.** { *; }

-keep interface ai.blamely.core.BlameUpdateListener { *; }

# Gson / JSON on disk
-keep class ai.blamely.persistence.HomeBranchSession { *; }
-keep class ai.blamely.persistence.StashLinkEntry { *; }
-keep class ai.blamely.persistence.BlameSerializer$SessionData { *; }

