# Retrofit, OkHttp, kotlinx.serialization, Room and Hilt ship their own consumer rules.
# Keep DTO serializers explicitly as a safety net for the shared dataset contract.
-keep,includedescriptorclasses class com.fundamentalanalysis.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.fundamentalanalysis.app.data.remote.dto.** {
    *** Companion;
}
