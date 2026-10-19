# Unlim Cloud — Android (Unofficial)

[Unlim Cloud](https://unlim-cloud.web.app)-এর জন্য একটি সহজ Android WebView অ্যাপ। এটি
[inulute/unlim-cloud](https://github.com/inulute/unlim-cloud) (MIT) ডেস্কটপ অ্যাপের আদলে তৈরি —
ডেস্কটপ সংস্করণও আসলে `https://unlim-cloud.web.app` লোড করে।
এটি **অফিসিয়াল অ্যাপ নয়**।

## ফিচার
- স্প্ল্যাশ স্ক্রিন (লোগোসহ) ও অফলাইন স্ক্রিন + "আবার চেষ্টা করুন" বাটন
- লগইন সেশন সংরক্ষিত থাকে (cookies / localStorage)
- ফাইল আপলোড (একাধিক ফাইল নির্বাচনসহ)
- ফাইল ডাউনলোড (সাধারণ লিংক ও `blob:` লিংক) → `Downloads` ফোল্ডারে
- Telegram লগইনের popup window সাপোর্ট; `tg://` লিংক Telegram অ্যাপে খোলে
- Back বাটনে পেছনে যাওয়া, হোমে দুইবার চাপলে বের হওয়া

## GitHub Actions দিয়ে APK বানানো
1. এই ফোল্ডারের সবকিছু একটি নতুন GitHub repository-তে আপলোড (push) করুন।
2. repository-র **Actions** ট্যাবে যান → **Build Android APK** workflow নিজে থেকেই চলবে
   (না চললে "Run workflow" চাপুন)।
3. শেষ হলে ওই run-এর পাতায় নিচে **Artifacts → UnlimCloud-apk** ডাউনলোড করুন।
   ভেতরে `UnlimCloud-release.apk` ও `UnlimCloud-debug.apk` পাবেন — যেকোনো একটি ফোনে ইনস্টল করুন
   (ইনস্টলের সময় "Unknown sources" অনুমতি দিতে হবে)।

> `v1.0.0`-এর মতো ট্যাগ push করলে APK স্বয়ংক্রিয়ভাবে GitHub **Releases**-এও যুক্ত হয়ে যাবে।

## লোকালি বানাতে চাইলে
JDK 17 + Android SDK থাকলে: `gradle assembleDebug` (অথবা Android Studio-তে খুলে Run)।

## কাস্টমাইজ
- ওয়েবসাইটের লিংক বদলাতে: `app/src/main/java/com/unofficial/unlimcloud/MainActivity.kt`-এ `HOME_URL`
- অ্যাপের নাম: `app/src/main/res/values/strings.xml`
- Package name: `app/build.gradle.kts`-এর `applicationId`

## লাইসেন্স
MIT — মূল প্রজেক্টের কপিরাইট নোটিশ `LICENSE` ফাইলে রাখা হয়েছে।
