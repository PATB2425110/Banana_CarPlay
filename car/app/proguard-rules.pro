# 设置按枚举名落盘，再 valueOf 读回来。名字被改掉，下次启动会读失败。
-keepclassmembernames enum com.dbtool.carplay.** {
    <fields>;
}
-keepclassmembernames enum com.shilapi.xcertplay.** {
    <fields>;
}

# VehicleReadCrashClassifier matches this namespace in uncaught JVM stacks. Preserve only names;
# unused classes/members can still be removed, and this is not a promise of complete crash attribution.
-keepnames class com.dbtool.carplay.geely.vehicle.**

# 热点驱动按字段名反射读取。类名可以变，字段名不能变。
-keepclassmembers class com.dbtool.carplay.bridge.hotspot.EcarxHotspotReader$Driver {
    <fields>;
}

# JNI 方法名必须和 libxcertplay_i2c.so 里的符号一致。
-keepclasseswithmembernames class * {
    native <methods>;
}

# BouncyCastle 只保留实际调用到的算法；未引用的 PQC 表让 R8 删掉。
-dontwarn org.bouncycastle.**
