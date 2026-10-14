# Rules for the optional shrinking of the release build (-PshrinkRelease).
#
# The only goal is to drop the library code that is never used (guava, material, appcompat, kotlin etc):
# less dex to map and to verify on a device with small RAM.
# Nothing is renamed and nothing is rewritten, so the stack traces are the same as without the shrinking.

-dontobfuscate
-dontoptimize

# The app. Models are created and filled by reflection (json path/gson adapters, prefs), keep all of it.
-keep class com.liskovsoft.** { *; }
-keep interface com.liskovsoft.** { *; }
-keep enum com.liskovsoft.** { *; }

# Modded copies of the libs. The app reads and writes their private fields (Helpers.getField/setField).
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.leanback.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.recyclerview.widget.RecyclerView$ViewHolder { *; }
-keep class com.stfalcon.chatkit.** { *; }

# JNI: the members are looked up by name from the native side
-keep class com.eclipsesource.v8.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.chromium.net.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Reflection inside of the libs that have no rules of their own
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.xbill.DNS.** { *; }
-keep class info.guardianproject.netcipher.** { *; }
-keep class com.florianingerl.util.regex.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class com.localebro.okhttpprofiler.** { *; }

# Retrofit: the interfaces are implemented at runtime
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Enums: valueOf/values by reflection, Serializable/Parcelable
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepattributes InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# Optional dependencies of the libs that aren't on the class path
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn sun.misc.**
-dontwarn lombok.**
-dontwarn net.minidev.asm.**
-dontwarn org.objectweb.asm.**
-dontwarn com.google.android.gms.**
