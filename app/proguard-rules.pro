# The app's own classes are small; keep them whole so serialization and the worker stay intact.
-keep class com.jobpilot.** { *; }

# JavaMail (SMTP sending, IMAP reply scan) loads providers and content handlers by name.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**
-dontwarn javax.naming.**

# Optional dependencies of OkHttp and jsoup that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
