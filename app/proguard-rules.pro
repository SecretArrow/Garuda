# Motion Browser keep rules (minify disabled for v1; rules kept for future enabling)
-keep class com.motion.browser.agent.** { *; }
-keep class com.motion.browser.ai.** { *; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
