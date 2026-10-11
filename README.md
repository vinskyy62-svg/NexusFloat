# NEXUS FLOAT — Gaming Floating Panel

Bubble melayang + panel alat bantu (Catatan, Stopwatch/Timer, Kalkulator, Pengaturan) yang tampil di atas aplikasi lain.
UI = HTML/CSS/JS di WebView; overlay, izin, service, dan penyimpanan = Kotlin native. **Tanpa dependensi pihak ketiga.**

## Struktur proyek
```
NexusFloat/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/nexusfloat/app/
        │   ├── MainActivity.kt      # izin overlay + tombol Aktifkan/Hentikan
        │   ├── FloatingService.kt   # foreground service, bubble, drag/snap, panel WebView, timer alert
        │   ├── BubbleView.kt        # bubble ringan (Canvas)
        │   ├── PanelBridge.kt       # jembatan JS <-> native (load/save/close/stop/inputMode/info)
        │   ├── ScreenBounds.kt      # area aman layar (di luar status/navigation bar)
        │   └── Prefs.kt             # SharedPreferences + parser pengaturan
        ├── assets/www/ index.html, style.css, app.js
        └── res/ (layout, drawable, values, mipmap-anydpi-v26)
```

## Build & jalankan (Android Studio)
1. Buka **Android Studio (Hedgehog/Iguana atau lebih baru)** → *Open* → pilih folder `NexusFloat`.
2. Tunggu Gradle Sync. (JDK 17 bawaan Android Studio sudah cukup. Jika diminta, setujui pengunduhan Gradle 8.7 / Android SDK 34. Wrapper `gradlew` bisa dibuat lewat `gradle wrapper --gradle-version 8.7` bila ingin build via terminal.)
3. Aktifkan *Developer options* + *USB debugging* di HP (Android 8.0+), sambungkan, lalu tekan **Run ▶**.
   Atau: *Build > Build APK(s)* lalu pasang `app/build/outputs/apk/debug/app-debug.apk`.

## Memberi izin overlay
1. Buka NEXUS FLOAT → ketuk **Beri izin “Tampil di atas aplikasi lain”**.
2. Di layar sistem, aktifkan **NEXUS FLOAT**, tekan Back.
3. Ketuk **Aktifkan Floating Panel**. Bubble “N” muncul; buka Mobile Legends/aplikasi lain.
Jika tombol izin abu-abu (APK di-sideload, Android 13+): *Info aplikasi → ⋮ → Izinkan pengaturan terbatas*, lalu ulangi.
Menghentikan: tombol **Hentikan** di aplikasi, tombol **Hentikan** di notifikasi, atau **Atur → Hentikan floating panel** di panel.

## Cara kerja singkat
- Bubble & panel = dua jendela `TYPE_APPLICATION_OVERLAY`. Panel `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL` → game tetap memegang fokus & sentuhan di luar panel diteruskan ke game. Fokus hanya diminta sementara saat mengetik catatan.
- WebView **dibuat saat panel dibuka dan di-destroy saat ditutup** (hemat RAM). Seluruh data (catatan, stopwatch/timer, pengaturan, posisi bubble) disimpan lewat bridge ke SharedPreferences, jadi tetap ada setelah panel/aplikasi ditutup.
- Stopwatch/timer dihitung dari `Date.now()` (timestamp), bukan hitungan animasi; satu `setTimeout` saja yang aktif dan berhenti bila tidak ada yang berjalan. Saat timer habis dan panel tertutup, service memberi tanda pada bubble + Toast.
- Posisi bubble disimpan relatif (0..1) lalu dihitung ulang saat rotasi/perubahan ukuran layar, dibatasi ke area di luar status/navigation bar.
- Drag vs klik: gerakan > `touchSlop` dianggap drag; ketukan di bawah itu membuka/menutup panel.

## Checklist pengujian
- [ ] **Izin**: tanpa izin, tombol Aktifkan membuka layar izin; setelah diberi, bubble muncul.
- [ ] **Drag**: geser bubble ke mana saja → tidak membuka panel; lepas → menempel ke kiri/kanan (auto-snap ON); bila OFF, tetap di tempat.
- [ ] **Batas layar**: bubble tidak bisa masuk ke area status bar / navigation bar.
- [ ] **Buka-tutup**: ketuk bubble → panel muncul (bubble mengecil sedikit); ketuk bubble lagi, tombol ✕, atau Back → panel menutup, bubble kembali normal.
- [ ] **Game**: buka game, sentuh area di luar panel → tetap diterima game; panel tidak mencuri fokus saat tidak mengetik.
- [ ] **Catatan**: buat, ubah, ganti tag, hapus (dua ketukan); tutup panel → buka lagi; *Hentikan* service → mulai lagi; force-stop app → data masih ada.
- [ ] **Stopwatch**: mulai, jeda, lanjut, reset; tutup panel saat berjalan → buka lagi, angka tetap benar.
- [ ] **Timer**: pilih 1m, mulai; tutup panel; saat habis bubble berdenyut + Toast; buka panel → “Waktu habis”.
- [ ] **Kalkulator**: `7 × 8 =` → 56; `1 ÷ 0 =` → Error lalu `C`; `0.1 + 0.2 =` → 0.3; `%`, `±`, `⌫`.
- [ ] **Pengaturan**: warna, ukuran, transparansi bubble & panel langsung berubah dan tersimpan; toggle auto-snap.
- [ ] **Orientasi**: putar portrait ↔ landscape dengan panel terbuka & tertutup → bubble tetap di dalam layar, panel muat dan bisa di-scroll.
- [ ] **Penghentian**: tombol di app, di notifikasi, dan di panel → bubble hilang, notifikasi hilang.
- [ ] **Stabilitas**: buka-tutup panel 20×, putar layar beberapa kali; cek memori di Profiler (tidak naik terus).

## Keterbatasan overlay per versi Android / perangkat
- **Android 8.0+ (minSdk 26)**: wajib `TYPE_APPLICATION_OVERLAY` dan izin “Display over other apps”. Android 7 ke bawah tidak didukung.
- **Android 8–13**: service background dibatasi; karena itu dipakai *foreground service* + notifikasi.
- **Android 12+**: sistem dapat memblokir sentuhan ke aplikasi di bawah overlay yang tembus-sentuh & hampir buram; jendela di sini berukuran kecil dan menerima sentuhan sendiri. Beberapa layar sistem (dialog izin, layar aman/FLAG_SECURE) menyembunyikan overlay.
- **Android 13+**: izin notifikasi **tidak diminta** (sengaja). Service tetap berjalan, namun notifikasinya mungkin tidak tampil di bilah notifikasi; tetap terlihat di *Task Manager* foreground service.
- **Android 14+**: memakai tipe foreground service `specialUse`; pengguna bisa menggeser notifikasi, service tetap hidup sampai dihentikan.
- **OEM (Xiaomi/HyperOS, Oppo, Vivo, Samsung, Huawei)**: bisa ada izin tambahan (“Tampilkan pop-up”/“Tampil di layar kunci”), pembatasan baterai, atau mode game (Game Turbo/Game Space) yang menyembunyikan/menahan overlay. Atur baterai ke *Tanpa batasan*.
- **Keyboard**: tampil-tidaknya keyboard di overlay bergantung perangkat/IME; jika tidak muncul, ketuk kolom sekali lagi.
- **Timer & tidur perangkat**: peringatan timer memakai Handler (waktu aktif perangkat). Bila HP tidur sangat lama, peringatan bisa terlambat; angka di panel tetap benar karena dihitung dari waktu jam sistem (berubah bila jam sistem diubah manual).
- **Rotasi**: posisi dihitung ulang setelah ±150 ms; pada perangkat lipat/multi-window area aman dihitung dari WindowMetrics (Android 11+) atau perkiraan dimensi sistem (Android 8–10).
- Aplikasi **tidak** membaca FPS, ping, suhu, atau data internal game; tidak membaca memori/layar game; tidak ada otomasi atau cheat.

## Catatan verifikasi
Kode ditulis dan diperiksa konsistensi antarfile (sintaks JS, XML, referensi resource/class), namun belum dikompilasi di lingkungan ini (tanpa Android SDK). Jika Gradle Sync/Build melaporkan error, kirimkan pesannya dan akan diperbaiki.
