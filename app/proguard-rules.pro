# MUS Proguard Rules

# Keep Media3 service
-keep class com.mus.android.playback.MusPlaybackService { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Hilt
-dontwarn dagger.hilt.**

# Coil
-dontwarn coil.**
