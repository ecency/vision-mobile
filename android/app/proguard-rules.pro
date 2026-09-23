# Project specific R8 rules. React Native, Expo modules, Firebase, Sentry, Glide,
# OkHttp, Reanimated and SVG ship their own consumer rules; the ones below cover
# what does not.

# config for rn background upload
-keep class net.gotev.uploadservice.** { *; }

# react-native-config reads BuildConfig fields by reflection (Class.forName).
-keep class app.esteem.mobile.android.BuildConfig { *; }

# Native modules that ship no consumer rules. Their Java is small, so keeping it
# whole costs little obfuscation and avoids reflection/JNI surprises at runtime.
-keep class com.RNFetchBlob.** { *; }
-keep class com.peel.react.** { *; }
-keep class com.tradle.react.** { *; }
-keep class com.ocetnik.timer.** { *; }
-keep class com.bitgo.randombytes.** { *; }
-keep class com.lugg.RNCConfig.** { *; }
-keep class com.learnium.RNDeviceInfo.** { *; }
-keep class com.reactnativepagerview.** { *; }
-keep class com.reactnativereceivesharingintent.** { *; }
-keep class com.reactnativerestart.** { *; }
-keep class com.reactlibrary.createthumbnail.** { *; }
-keep class com.reactnative.ivpusic.imagepicker.** { *; }
-keep class com.henninghall.date_picker.** { *; }
-keep class com.thebylito.navigationbarcolor.** { *; }
-keep class org.wonday.orientation.** { *; }
-keep class com.apsl.versionnumber.** { *; }
-keep class agency.flexible.react.modules.email.** { *; }
-keep class com.emojipopup.** { *; }
-keep class com.zoontek.** { *; }
-keep class org.linusu.** { *; }
-keep class com.airbnb.android.react.lottie.** { *; }
-keep class com.brentvatne.** { *; }
-keep class com.mrousavy.camera.** { *; }
-keep class com.reactnativecommunity.webview.** { *; }
-keep class com.swmansion.gesturehandler.** { *; }
-keep class com.swmansion.rnscreens.** { *; }
-keep class com.th3rdwave.safeareacontext.** { *; }

# In-app purchases: purchase and product payloads are mapped by reflection.
-keep class expo.modules.iap.** { *; }
-keep class dev.hyo.openiap.** { *; }
# openiap parses with Gson 2.10, which ships no consumer rules (TypeToken generics).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# react-native-tcp's androidasync references Apache HTTP classes that left
# android.jar at API 23; those code paths are never used by the app.
-dontwarn org.apache.http.**
