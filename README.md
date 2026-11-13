# RTL Synth Studio

Android app for RTL synthesis/simulation workbench work. Built with Kotlin and Jetpack Compose, with Firebase backend integration.

## Download APK

Download the latest APK: [rtl-synth-studio.apk](https://github.com/a1vi/rtl-synth-studio/releases/latest/download/rtl-synth-studio.apk)

## Build

GitHub Actions builds the APK on every push to `main` and publishes it to GitHub Releases.

For a local build, install JDK 17+, Android SDK platform 36, and Gradle 9.3.1, then run:

```bash
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
