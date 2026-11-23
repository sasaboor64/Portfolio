# BouncyCastle (used only to build the pairing certificate) references optional
# JDK classes that don't exist on Android.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
