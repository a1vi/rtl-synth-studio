<div align="center">
  <img src="docs/assets/banner.png" alt="RTL Synth Studio" width="100%" />
</div>

<div align="center">
  <a href="https://github.com/a1vi/rtl-synth-studio/releases/latest/download/rtl-synth-studio.apk">
    <img src="https://img.shields.io/badge/Download-APK-2EA44F?logo=android&logoColor=white" alt="Download APK" />
  </a>
  <a href="https://github.com/a1vi/rtl-synth-studio/actions/workflows/build-apk.yml">
    <img src="https://github.com/a1vi/rtl-synth-studio/actions/workflows/build-apk.yml/badge.svg" alt="Build APK" />
  </a>
  <a href="https://github.com/a1vi/rtl-synth-studio/releases">
    <img src="https://img.shields.io/github/v/release/a1vi/rtl-synth-studio?label=Release&color=blue" alt="Release" />
  </a>
  <br />
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-2024.09.00-4285F4" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Firebase-Auth%20%26%20Firestore-FFCA28?logo=firebase&logoColor=black" alt="Firebase" />
  <img src="https://img.shields.io/badge/Gradle-9.3.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
</div>

---

RTL Synth Studio is an Android RTL synthesis workbench for parsing Verilog HDL, mapping to gate-level netlists, exploring interactive schematics, running static timing analysis, simulating waveforms, and syncing designs to the cloud.

## Highlights

- **Verilog HDL front end** — preprocessing, parsing, and structure extraction for synthesizable RTL.
- **Synthesis engine** — technology mapping to gate-level netlists with layout for schematic rendering.
- **Interactive schematic viewer** — pan/zoom gate-level netlist visualization.
- **Static timing analysis** — SDC constraints, timing tables, and PPA reporting.
- **RTL simulation** — event-driven simulator with waveform viewer.
- **Cloud workspace** — Firebase Auth (Google sign-in) and Firestore design sync.
- **Local-first editing** — workbench editor with syntax highlighting and preset designs.

## App screens

| Tab | Purpose |
| --- | --- |
| Workbench | Write/edit Verilog, run synthesis, save designs |
| Schematic | Explore gate-level netlist schematics |
| Simulate | Run simulations and inspect waveforms |
| Timing & PPA | Review STA results and power/performance/area metrics |
| Library | Browse logic primitives and preset RTL designs |

## Tech stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3), Navigation |
| Architecture | MVVM, ViewModel, Repository pattern |
| Backend | Firebase Auth, Cloud Firestore |
| Build | Gradle 9.3.1, Android Gradle Plugin 9.1.1 |
| Min/Target SDK | 24 / 36 |

## Download

Download the latest APK:

[![Download APK](https://img.shields.io/badge/Download-rtl--synth--studio.apk-2EA44F?logo=android&logoColor=white)](https://github.com/a1vi/rtl-synth-studio/releases/latest/download/rtl-synth-studio.apk)

Or open the [Releases page](https://github.com/a1vi/rtl-synth-studio/releases).

## Build from source

Prerequisites: JDK 17+, Android SDK platform 36, Gradle 9.3.1.

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds and publishes the APK automatically on pushes to `main` (see `.github/workflows/build-apk.yml`).

## Repository layout

```text
app/src/main/java/com/example/
├── data/          # models, local store, repository, Firestore error handling
├── domain/synth/  # Verilog parser, preprocessor, synthesis engine, STA, simulator
└── ui/            # Compose screens, components, theme, ViewModel
```

## Firebase

The app uses Firebase Authentication (Google sign-in) and Cloud Firestore for cloud design sync. Firebase config is in `app/google-services.json` and `app/src/main/res/values/firebase_applet_config.xml`; Firestore security rules are in `firestore.rules`.
