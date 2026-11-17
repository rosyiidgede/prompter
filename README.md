# Prompter (Android teleprompter)

Kotlin + Jetpack Compose. Naskah melayang di atas aplikasi kamera bawaan HP (overlay), jadi kamu bisa merekam sambil membaca dekat lensa.

Fitur: auto-scroll, kecepatan ±, ukuran teks, opasitas, mode cermin, double-tap = kembali ke awal, pinch = resize, seret untuk pindah, tombol kembali ke app, preview layar penuh.

## Build
- Android Studio: buka folder ini, Run.
- CLI: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
- GitHub: Actions > "Build Android APK" > Run workflow, lalu unduh artifact.

Pertama kali jalan, beri izin "Tampil di atas aplikasi lain".

Build ulang otomatis berjalan setiap push ke folder ini.
