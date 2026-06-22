# kotlinx.serialization — 프로토콜 모델 유지
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class dev.eigger.hassble.net.** { *; }
-keepclassmembers class dev.eigger.hassble.net.** {
    *** Companion;
}
-keepclasseswithmembers class dev.eigger.hassble.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
