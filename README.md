# Unlim Cloud — Native Android (Unofficial)

Telegram-এর **Saved Messages**-কে ক্লাউড স্টোরেজ বানিয়ে ফাইল আপলোড / ডাউনলোড / মোছার একটি
**সম্পূর্ণ নেটিভ** Android অ্যাপ (Kotlin + Jetpack Compose + অফিসিয়াল [TDLib](https://github.com/tdlib/td))।
WebView নেই। ধারণাটি [inulute/unlim-cloud](https://github.com/inulute/unlim-cloud) (MIT) থেকে নেওয়া; এটি অফিসিয়াল অ্যাপ নয়।

## ফিচার
- নিজের ফোন নম্বর + কোড (+ 2FA পাসওয়ার্ড) দিয়ে Telegram লগইন
- Saved Messages-এ থাকা ফাইলের তালিকা (সব / ডকুমেন্ট / ছবি-ভিডিও / অডিও), পেজিনেশনসহ
- একাধিক ফাইল আপলোড (ডকুমেন্ট হিসেবে, মূল কোয়ালিটি অক্ষত), অগ্রগতি দেখা
- ডাউনলোড → ফোনের `Downloads` ফোল্ডারে
- ফাইল মোছা, লগআউট

## আগে একবার করুন: Telegram API credentials
1. https://my.telegram.org এ নিজের নম্বর দিয়ে লগইন করুন।
2. **API development tools** → একটি অ্যাপ তৈরি করুন → `api_id` ও `api_hash` কপি করুন।
3. অ্যাপ প্রথমবার খুললে এ দুটি বসাবেন। এগুলো কোডে বা GitHub-এ রাখার দরকার নেই, শুধু আপনার ফোনে সংরক্ষিত থাকে।

## GitHub Actions দিয়ে APK বানানো
1. এই ফোল্ডারের সবকিছু (`.github` ফোল্ডারসহ) একটি GitHub repository-তে push করুন।
2. **Actions** ট্যাবে "Build Android APK" চলবে (না চললে "Run workflow")।
3. **প্রথমবার ৪০–৯০ মিনিট লাগবে**, কারণ Telegram-এর অফিসিয়াল সোর্স থেকে TDLib কম্পাইল হয়। এরপর cache থেকে কয়েক মিনিটে হয়ে যায়।
4. শেষে run-এর পাতা থেকে **UnlimCloud-apk** artifact ডাউনলোড করে APK ইনস্টল করুন।

> TDLib আবার নতুন করে build করাতে চাইলে `build-apk.yml`-এ `TDLIB_CACHE_VERSION` সংখ্যাটি বাড়িয়ে দিন।

## সীমাবদ্ধতা
- ওয়েবসাইটের (unlim-cloud.web.app) নিজস্ব ফোল্ডার-কাঠামো অজানা, তাই এখানে ফোল্ডার নেই; সব ফাইল একটি তালিকায় দেখায়।
- একটি ফাইল সর্বোচ্চ ২ GB (Telegram Premium হলে ৪ GB)।
- থাম্বনেইল/গ্যালারি ভিউ নেই; অ্যাপ বন্ধ করে দিলে চলমান বড় আপলোড থেমে যেতে পারে।
- TDLib-এর লোকাল ডেটাবেস এনক্রিপ্ট করা নয় (অ্যাপের private স্টোরেজে থাকে)।

## লাইসেন্স
MIT (মূল প্রজেক্টের কপিরাইট নোটিশ `LICENSE`-এ)। TDLib: Boost Software License 1.0।
