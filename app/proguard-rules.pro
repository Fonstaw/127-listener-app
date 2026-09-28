# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# General reflection & generic signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Application Models, Entities, and DTOs
-keep class com.example.data.** { *; }
-keep class com.example.api.** { *; }
-keep class com.example.parser.** { *; }

# Moshi rules
-dontwarn javax.annotation.**
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class * extends com.squareup.moshi.JsonAdapter {
    public <init>(...);
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json *;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.squareup.moshi.** { *; }
-keepnames class * {
    @com.squareup.moshi.JsonClass *;
}

# Retrofit 2 rules
-keepattributes RuntimeVisible*Annotations*
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep interface com.example.api.ApiService { *; }

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Room Database rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.room.EntityDeletionOrUpdateAdapter {
    public <init>(...);
}
-keep class * extends androidx.room.EntityInsertionAdapter {
    public <init>(...);
}
-keep class * extends androidx.room.SharedSQLiteStatement {
    public <init>(...);
}
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase_Impl { *; }
-keep class **_Impl { *; }
