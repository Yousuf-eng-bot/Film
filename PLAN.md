# Spektrafilm মোবাইল অ্যাপ — গবেষণা ও প্ল্যান (বাংলা)

> **প্রজেক্ট:** `Yousuf-eng-bot/Film`
> **লক্ষ্য:** DaVinci Resolve-এর Spektrafilm-এর মতো আসল ফিল্ম সিমুলেশন মোবাইলে আনা — প্রথমে **ছবি (Photo)**, পরে **ভিডিও (Video)**
> **স্ট্যাটাস:** ফেজ-১ সম্পন্ন (v1.1.0 রিলিজ হয়েছে); ফেজ-২ ভিডিও প্রোটোটাইপ (Video Lab beta) কোডবেসে ঢুকেছে
> **তারিখ:** 2026-11-27

## ০. অগ্রগতি স্ন্যাপশট (2026-11-27)

- ✅ **v1.0.1** — বেস APK রিলিজ (Spektrafilm-android ফর্ক, Actions বিল্ড, Releases-এ APK)
- ✅ **v1.1.0** — রিব্র্যান্ড: অ্যাপের নাম **Film** (bn: ফিল্ম), ব্র্যান্ড-সবুজ আইকন/স্প্ল্যাশ, updater + লিংক এই রিপোতে, **বাংলা UI** (৬২টি সর্বাধিক-দৃশ্যমান স্ট্রিং, বাকি ইংরেজি ফলব্যাক)
- ✅ **ফেজ-১ (ছবি)** — সম্পূর্ণ: ফিল্ম+পেপার পিক, GPU/CPU রেন্ডার, RAW/DNG, মাস্ক/ক্রপ, JPEG/PNG/TIFF/LUT এক্সপোর্ট (আপস্ট্রিম থেকে)
- ✅ **v1.2.0** — **Video Lab beta** (ফেজ-২ প্রথম মাইলস্টোন): SDR ভিডিও পিক → ফিল্ম+পেপার → ৩৩³ LUT GPU প্রিভিউ (play/scrub) → সাইলেন্ট MP4 এক্সপোর্ট (Pictures/Film)। ফ্রেম ফটো-পাইপলাইনের একই `bitmapToLinearProPhoto` দিয়ে LUT-এ যায় — রঙের ডোমেইন এক। CI সম্পূর্ণ সবুজ (ইউনিট টেস্ট + স্ক্রিনশট + assembleDebug)।
- 📌 পরের ধাপ: MediaCodec ডিকোড-ভিত্তিক রিয়েল-টাইম প্রিভিউ, ফুল-স্পেকট্রাল ফ্রেম রেন্ডার (গ্রেইন/হ্যালেশন সহ), অডিও কপি, HDR, 1080p/H.265।
- 🛠 CI নোট: `build.yml` arena/** পুশেও চলে; স্ক্রিনশট রেফারেন্স স্বয়ংক্রিয়ভাবে রিজেনারেট হয়ে ব্রাঞ্চে কমিট হয়; ইউনিট-টেস্ট ব্যর্থতা commit comment হিসেবে রিপোর্ট হয় (স্যান্ডবক্স blob-লগ পড়তে পারে না)।

---

## ১. গবেষণা সারাংশ — Spektrafilm আসলে কী?

### ১.১ মূল প্রজেক্ট: `andreavolpato/spektrafilm`

- **কী:** স্পেকট্রাল (আলোর বর্ণালী-ভিত্তিক) ফিল্ম সিমুলেশন — LUT দিয়ে "ফিল্ম লুক" অনুকরণ নয়, বরং আলো + রসায়নের পুরো প্রক্রিয়াটা মডেল করে।
- **পাইপলাইন:** `Camera RAW (linear RGB) → Spectral upsampling → Virtual Negative → Enlarger (CMY dichroic filters) → Print Paper → Scan → sRGB/Display P3`
- **ভাষা:** Python 3.13, GPLv3 (কোড) + CC BY-SA 4.0 (ফিল্ম প্রোফাইল/LUT)
- **স্ট্যাটাস:** ডেস্কটপ GUI (Qt + napari), রিসার্চ-রেফারেন্স ইমপ্লিমেন্টেশন। ফুল-রেজোলিউশন এক্সপোর্ট খুব ধীর (CPU, NumPy)।
- **কেন মোবাইলে সরাসরি চলবে না:** Python + ভারী NumPy/scipy/colour-science নির্ভরতা, GPU নেই, মোবাইলে Python রানটাইম অসম্ভব। তাই **পোর্ট** দরকার — C++/Vulkan বা Rust।

**রেফারেন্স:** https://github.com/andreavolpato/spektrafilm

### ১.২ মোবাইল/ফাস্ট পোর্টগুলো (গবেষণায় যা পেলাম)

| প্রজেক্ট | ভাষা/ইঞ্জিন | প্ল্যাটফর্ম | স্টক | লাইসেন্স | মূল্যায়ন |
|---|---|---|---|---|---|
| **`thetechgeekko/Spektrafilm-android`** | Kotlin + C++ Vulkan (GPU) + CPU fallback | **Android 7+** | 25 ফিল্ম + 9 পেপার | **GPLv3** | ⭐ **সেরা বেস** — প্রোডাকশন v1.0, RAW/DNG (LibRaw), masks, crop, 16-bit export, oracle tolerance `max_abs ≤ 1e-4` |
| `noahhaasis/negs` | Rust (spektrafilm-rs) + Vulkan/WebGPU | Web/Mac/Win/Linux/**Android APK** | 70+ স্টক | GPLv3 | খুব ভালো, কিন্তু Rust ইঞ্জিন + ডেস্কটপ-কেন্দ্রিক; Android UI আলাদা |
| `YZH2006517/Photo-All-Android` | Kotlin + C++/Vulkan (vkdt filmsim) | Android 10+ | 22 ফিল্ম + 9 পেপার | CC BY-NC 4.0 (non-commercial) | ফিচার বেশি (ক্যামেরা, 416 LUT) কিন্তু **commercial-এ ব্যবহার করা যাবে না** — আমাদের জন্য ঝুঁকি |
| `turbasvin/spektrafilm-rs` | Rust | লাইব্রেরি | — | GPLv3 | ইঞ্জিন হিসেবে ভালো, কিন্তু Android UI নেই |
| `chaert-s/spektrafilm-ofx` / `oldandangry/Film-Juicer` | C++/CUDA/Vulkan OFX | DaVinci Resolve | 20+8 | GPLv3 | ডেস্কটপ প্লাগিন, মোবাইলে অপ্রাসঙ্গিক |
| `vividcolour/spektrafilm-GrainOnly` | C++/Metal OFX | macOS Resolve | grain only | GPLv3 | শুধু grain — পুরো সিমুলেশন নয় |

### ১.৩ সিদ্ধান্ত: কেন `Spektrafilm-android` বেস হিসেবে?

1. **সবচেয়ে দ্রুত পথ:** ইতিমধ্যে প্রোডাকশন-রেডি ফটো অ্যাপ — 25 ফিল্ম, 9 পেপার, 6 প্রিসেট, RAW/DNG, লোকাল মাস্ক, ক্রপ, JPEG/PNG/TIFF 16/32-bit এক্সপোর্ট, `.cube`/CLF LUT এক্সপোর্ট।
2. **বৈজ্ঞানিকভাবে সঠিক:** C++ পোর্ট প্রতিটি স্টেজে Python oracle-এর সাথে যাচাই করা (`rms ≤ 1e-5`), GPU vs CPU অটো-ফলব্যাক।
3. **লাইসেন্স পরিষ্কার:** GPLv3 — Spektrafilm-এর সাথে মিলে যায়। Attribution (`NOTICE.md`) সহ শিপ করলেই বৈধ।
4. **GitHub Actions তৈরি:** `build.yml` (debug APK) + `release.yml` (signed draft release) ইতিমধ্যে আছে — আমরা এটাকে সরল করে দ্রুত রিলিজের জন্য সাজাবো।
5. **ভিডিওর জন্য প্রসারযোগ্য:** একই Vulkan ইঞ্জিন ফ্রেম-বাই-ফ্রেম চালিয়ে ভিডিও করা যায় (ফেজ-২ প্ল্যান নিচে)।

> **বিকল্প বিবেচনা:** শূন্য থেকে Kotlin + LUT-ভিত্তিক অ্যাপ বানালে ২-৩ মাস লাগত এবং ফল "ফিল্টার" হতো, আসল ফিল্ম নয়। `negs`-এর Rust ইঞ্জিন নিলে Android UI নতুন করে লিখতে হতো। তাই **ফর্ক-ভিত্তিক দ্রুত পথই** তোমার "কম ইনপুট, দ্রুত অ্যাপ" চাওয়ার সাথে মিলে যায়।

---

## ২. লক্ষ্য ও স্কোপ

### ফেজ-১ — ছবি (Photo MVP) — **এখনই চলছে**
- গ্যালারি থেকে ছবি/RAW/DNG সিলেক্ট → ফিল্ম + পেপার পিক → স্লাইডার টিউন → এক্সপোর্ট
- 25 ফিল্ম (Portra, Ektar, Gold, Vision3, Velvia, Kodachrome, Tri-X, HP5...) + 9 পেপার (Endura, Edge, Crystal Archive, 2383/2393...)
- 6 বিল্ট-ইন লুক: Portra 400, Gold 200, Ektar 100, CineStill 800T, Kodachrome 64, Tri-X 400
- লাইট/কালার/ফিল্ম/প্রিন্ট/ইফেক্ট এডিটর, ক্রপ/স্ট্রেইটেন, রেডিয়াল/গ্রেডিয়েন্ট/কালার-রেঞ্জ/স্কিন মাস্ক
- এক্সপোর্ট: JPEG, PNG 8/16, TIFF 16/32-bit float, 6 কালার স্পেস + ICC, বর্ডার/ফিল্ম-স্ট্রিপ ফ্রেম, LUT (`.cube`/CLF)
- **আউটপুট:** GitHub Actions-এ অটো APK → Releases পেজে ডাউনলোড

### ফেজ-২ — ভিডিও (Video) — **ফেজ-১ শিপ হওয়ার পর**
- ভিডিও সিলেক্ট → প্রিভিউ (LUT-ভিত্তিক রিয়েল-টাইম) → ফুল স্পেকট্রাল এক্সপোর্ট (MP4/H.264/H.265)
- বিস্তারিত প্ল্যান: `docs/VIDEO_PLAN.md` (সংক্ষেপে §৬ দেখো)

### নন-গোল (এই মুহূর্তে নয়)
- iOS (Apple-এর Vulkan নেই; Metal পোর্ট আলাদা প্রজেক্ট — `Silverphase`/`SpektraMac` দেখো)
- ক্লাউড প্রসেসিং (প্রাইভেসি + খরচ — সব লোকালই থাকবে)
- ক্যামেরা-মোড (Photo-All-এর মতো) — আগে ইমপোর্ট-এডিট-এক্সপোর্ট পাকা করবো

---

## ৩. টেক স্ট্যাক (ফেজ-১)

```
┌─────────────────────────────────────────────────┐
│  UI: Kotlin + Jetpack Compose (Material 3)      │
│  • Looks / Edit / Crop / Masks ট্যাব             │
│  • Photo Picker (system), MediaStore, SAF       │
├─────────────────────────────────────────────────┤
│  App logic: Kotlin (coroutines, Room/sidecar)   │
│  • প্রিসেট সেভ/শেয়ার, হিস্ট্রি, ভার্সন          │
├─────────────────────────────────────────────────┤
│  Engine: C++ Vulkan (GPU) + CPU fallback        │
│  engine/spektra-core — spektrafilm-এর C++ পোর্ট  │
│  • Negative → Enlarger → Print → Scan           │
│  • Grain, halation, diffusion, DIR couplers     │
├─────────────────────────────────────────────────┤
│  RAW: LibRaw (DNG + NEF/CR2/ARW/RAF...)        │
│  Export: JPEG/PNG/TIFF writers, ICC, LUT        │
└─────────────────────────────────────────────────┘
```

- **মিন SDK:** 24 (Android 7.0), **টার্গেট:** 36, **NDK:** r28.2, **JDK:** 21 (CI), **Gradle/AGP:** প্রজেক্টের পিন করা ভার্সন
- **ABI:** `arm64-v8a`, `armeabi-v7a`, `x86_64` (এক APK-তে সব)
- **ভাষা:** 8টি (en, de, es, fr, ja, ko, pt-BR, zh-CN) — বাংলা যোগ করা ফেজ-১.৫-এ সহজ (শুধু `values-bn` ফোল্ডার)
- **সাইজ/পারফরম্যান্স:** প্রিভিউ (ফোন-সাইজ) রিয়েল-টাইম, ফুল-রেজোলিউশন "Scan" এক্সপোর্টে — ছবি প্রতি ~১-৩ সেকেন্ড (ডিভাইসভেদে)

---

## ৪. ফিল্ম পাইপলাইন (সংক্ষেপ)

1. **Negative:** RGB → স্পেকট্রাম আপস্যাম্পলিং → 3 লেয়ার সেনসিটিভিটি → ডেনসিটি কার্ভ + DIR couplers → CMY ডাই + grain/halation/scatter
2. **Enlarger:** নেগেটিভ → Y/M/C ডাইক্রোইক ফিল্টার → প্রিন্ট এক্সপোজার + প্রিফ্ল্যাশ + কন্ট্রাস্ট মাস্ক
3. **Print:** ভার্চুয়াল পেপার সেনসিটিভিটি + কার্ভ → প্রিন্ট ডাই (স্লাইড হলে স্কিপ)
4. **Scan:** প্রিন্ট ডাই → স্পেকট্রাম → স্ট্যান্ডার্ড ইলুমিন্যান্ট → এক্সপোর্ট কালার স্পেস

> প্রতিটি স্টেজ Python oracle-এর বিরুদ্ধে যাচাই করা — তাই DaVinci-র Spektrafilm-এর সাথে ফল মিলবে।

---

## ৫. ফেজ-১ ফিচার চেকলিস্ট (এই রিপোতে যা আছে)

- [x] 25 ফিল্ম + 9 পেপার (স্পেকট্রাল ডেটা সহ)
- [x] 6 প্রিসেট লুক + কাস্টম প্রিসেট সেভ/শেয়ার
- [x] এক্সপোজার, হাইলাইট/শ্যাডো, WB, টোন কার্ভ, grain, halation, diffusion, enlarger filtration, paper grade, date stamp
- [x] ক্রপ/স্ট্রেইটেন/রোটেট, রেডিয়াল/গ্রেডিয়েন্ট/কালার/স্কিন মাস্ক
- [x] RAW/DNG ডিকোড (LibRaw), অরিজিনাল কখনো বদলায় না (sidecar এডিট)
- [x] এক্সপোর্ট: JPEG/PNG/TIFF + ICC + বর্ডার/ফিল্ম-ফ্রেম + LUT
- [x] GPU (Vulkan) + CPU ফলব্যাক, ডিভাইসে সেলফ-চেক
- [ ] **আমাদের কাজ:** GitHub Actions সরলীকরণ + Release APK, রিব্র্যান্ডিং, বাংলা UI, টেস্ট

---

## ৬. ফেজ-২ — ভিডিও প্ল্যান (সংক্ষেপ)

> পুরো ডক: [`docs/VIDEO_PLAN.md`](docs/VIDEO_PLAN.md)

**কৌশল — দুই-স্তর:**
1. **প্রিভিউ (রিয়েল-টাইম):** বর্তমান ফিল্ম+পেপার সেটিংস থেকে **33³ 3D LUT** বেক করে (ইঞ্জিনের LUT এক্সপোর্ট ব্যবহার করে) OpenGL/MediaCodec পাথ দিয়ে ভিডিও প্রিভিউ — 30fps-এ চলবে।
2. **এক্সপোর্ট (ফুল স্পেকট্রাল):** প্রতিটি ফ্রেম একই Vulkan ইঞ্জিন দিয়ে প্রসেস → MediaCodec (H.264/H.265) এনকোড → MP4। Grain-এর জন্য ফ্রেম-ভিত্তিক seed (অ্যানিমেটেড), halation প্রতি ফ্রেমে।

**পারফরম্যান্স হিসাব:** 1080p ~৩০fps ভিডিওতে প্রতি ফ্রেম ~০.১-০.৩s (GPU) → ১০ সেকেন্ড ক্লিপ ≈ ৩-৯ মিনিট এক্সপোর্ট। তাই **1080p/720p প্রিসেট + ব্যাকগ্রাউন্ড এক্সপোর্ট + প্রগ্রেস** জরুরি। 4K পরে অপ্টিমাইজ হবে।

**UI:** ভিডিও ট্যাব (পিক → লুক পিক → প্রিভিউ → এক্সপোর্ট), ফিল্ম-স্ট্রিপ থাম্বনেইল, অডিও পাস-থ্রু (মিউট অপশন সহ)।

---

## ৭. GitHub Actions + Release (এই রিপোতে সেটআপ করা হয়েছে)

### ৭.১ ওয়ার্কফ্লো

| ফাইল | ট্রিগার | কী করে |
|---|---|---|
| `.github/workflows/build.yml` (আপস্ট্রিম) | push to `main`, PR (`run-ci` লেবেল), manual | ইউনিট টেস্ট + debug APK → Artifact |
| `.github/workflows/android-release.yml` (**আমাদের নতুন**) | tag `v*` + manual | **Release APK বিল্ড → GitHub Release-এ আপলোড** (সরল, সাইনিং-সিক্রেট ছাড়া debug-সাইনড) |
| `.github/workflows/release.yml` (আপস্ট্রিম) | manual | প্রোডাকশন signed রিলিজ (সিক্রেট + approval লাগে) — পরে ব্যবহার হবে |

### ৭.২ রিলিজ দেওয়ার নিয়ম (দ্রুত পথ)

```bash
# ১. main-এ মার্জ/পুশ
# ২. ট্যাগ দাও (versionName-এর সাথে মিলিয়ে)
git tag v1.0.1
git push origin v1.0.1
# ৩. Actions → Android Release রান হবে → Releases পেজে APK
```

- প্রথম রিলিজগুলো **debug-সাইনড** (ইনস্টল করা যাবে, Play Store-এর জন্য নয়) — দ্রুত টেস্টের জন্য যথেষ্ট।
- Play Store/প্রোডাকশন সাইনিং পরে: `release-signing` Environment-এ keystore সিক্রেট যোগ করে আপস্ট্রিম `release.yml` ব্যবহার হবে।

### ৭.৩ লোকালে বিল্ড

```bash
# Android SDK + NDK r28.2 + JDK 17+ লাগবে
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/*.apk
```

---

## ৮. লাইসেন্স ও অ্যাট্রিবিউশন (গুরুত্বপূর্ণ)

- **কোড:** GPLv3 — এই রিপো পুরোটাই GPLv3-এর অধীনে থাকবে। সোর্স ওপেন রাখতেই হবে।
- **ফিল্ম প্রোফাইল/LUT:** CC BY-SA 4.0 (`SPEKTRAFILM_LICENSE.txt`) — অ্যাট্রিবিউশন বাধ্যতামূলক।
- **অ্যাপে অবশ্যই দেখাতে হবে** (About/Licences স্ক্রিন + স্টোর লিস্টিং):
  > **Spektrafilm for Android** by **Akshay Sharma** — https://github.com/thetechgeekko/Spektrafilm-android
  > Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm
- বিস্তারিত: [`NOTICE.md`](NOTICE.md), [`LICENSE`](LICENSE)

---

## ৯. দ্রুত বানানোর টাইমলাইন

| ধাপ | কাজ | সময় | তোমার ইনপুট |
|---|---|---|---|
| **সপ্তাহ ১** | ফেজ-১ ফর্ক + Actions + প্রথম APK (এই রিপো) | ১-২ দিন | APK টেস্ট (ইনস্টল + ২-৩টা ছবি) |
| **সপ্তাহ ২** | রিব্র্যান্ড (নাম/আইকন/প্যাকেজ), বাংলা UI, ৬ প্রিসেট টিউন | ৩-৫ দিন | নাম/আইকন পছন্দ, প্রিসেট ফিডব্যাক |
| **সপ্তাহ ৩-৪** | ভিডিও প্রোটোটাইপ (LUT প্রিভিউ + 720p এক্সপোর্ট) | ১-২ সপ্তাহ | ১টা ভিডিও ক্লিপ টেস্ট |
| **মাস ২** | ভিডিও ফুল স্পেকট্রাল এক্সপোর্ট + 1080p + ব্যাকগ্রাউন্ড | ২-৩ সপ্তাহ | রিলিজ টেস্ট |

> **কম-ইনপুট নীতি:** ডিফল্ট সেটিংস, প্রিসেট, এক্সপোর্ট — সব আমি ঠিক করে দেবো। তুমি শুধু APK টেস্ট করে "ভালো/খারাপ" বললেই হবে।

---

## ১০. রিস্ক ও মিটিগেশন

| রিস্ক | মিটিগেশন |
|---|---|
| পুরোনো ফোনে Vulkan নেই/ধীর | CPU ফলব্যাক ইতিমধ্যে আছে; প্রিভিউ রেজোলিউশন অটো-স্কেল |
| 4K ছবি/ভিডিওতে OOM | টাইল্ড প্রসেসিং + "Use less memory" মোড (আপস্ট্রিমে আছে) |
| GPLv3 — ক্লোজড-সোর্স করা যাবে না | শুরু থেকেই ওপেন রাখবো; স্টোরে অ্যাট্রিবিউশন দেবো |
| আপস্ট্রিম ব্রেকিং চেঞ্জ | পিন করা কমিট থেকে ফর্ক; আপস্ট্রিম ফলো-আপ ম্যানুয়াল মার্জ |
| Play Store পলিসি (RAW/মিডিয়া) | Photo Picker + SAF ব্যবহার — ইতিমধ্যে কমপ্লায়েন্ট |

---

## ১১. নেক্সট স্টেপ চেকলিস্ট

- [x] গবেষণা + বেস সিলেকশন (এই ডক)
- [x] কোডবেস ফর্ক (`Spektrafilm-android` → এই রিপো)
- [x] সরল GitHub Actions + Release ওয়ার্কফ্লো
- [ ] প্রথম APK রিলিজ (`v1.0.1` ট্যাগ) → তোমার টেস্ট
- [ ] রিব্র্যান্ড + বাংলা UI (ফেজ-১.৫)
- [ ] ভিডিও প্রোটোটাইপ (ফেজ-২)

---

## ১২. রেফারেন্স

- Spektrafilm (মূল): https://github.com/andreavolpato/spektrafilm
- Spektrafilm for Android (বেস): https://github.com/thetechgeekko/Spektrafilm-android
- Negs (Rust বিকল্প): https://github.com/noahhaasis/negs
- spektrafilm-rs: https://github.com/turbasvin/spektrafilm-rs
- Photo-All-Android (CC BY-NC — রেফারেন্স মাত্র): https://github.com/YZH2006517/Photo-All-Android
- vkdt filmsim (Vulkan রেফারেন্স): https://jo.dreggn.org/vkdt/src/pipe/modules/filmsim/readme.html
- আলোচনা: https://discuss.pixls.us/t/spectral-film-simulations-from-scratch/48209

---

*এই প্ল্যানটি `Spektrafilm-android` v1.0-এর কোডবেসের উপর ভিত্তি করে তৈরি। ইঞ্জিনের বৈজ্ঞানিক মডেলের কৃতিত্ব Andrea Volpato (spektrafilm) এবং Android পোর্টের কৃতিত্ব Akshay Sharma-র।*
