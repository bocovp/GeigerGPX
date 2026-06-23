
# OpenStreetMap (osmdroid) optimization safety rules
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Google Play Services Location safety rules
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.location.**