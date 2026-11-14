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
  <img src="https://img.shields.io/badge/GDSII%20%2F%20KLayout-Physical_Flow-7C3AED" alt="GDSII KLayout" />
  <img src="https://img.shields.io/badge/Offline--first-On--device-3DDC84" alt="Offline first" />
  <img src="https://img.shields.io/badge/Gradle-9.3.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
</div>

---

RTL Synth Studio is a local-first Android RTL-to-GDS workbench for multi-file hardware projects. It parses Verilog, SystemVerilog, VHDL, SDC constraints, and testbenches; maps logic to gate-level netlists across ASIC and FPGA target libraries; runs static timing analysis and simulation; renders interactive schematics and waveforms; and carries designs through an on-device physical flow to GDSII with KLayout-style snapshots — all stored on-device.

## Highlights

- **Multi-file projects** — create, import, duplicate, and organize RTL projects with per-file language detection.
- **HDL front end** — Verilog/SystemVerilog parsing, VHDL frontend, SDC constraints, and testbench support.
- **Synthesis engines** — SiliconFlow FastMapper, ABC timing-driven mapper, and area/power compactor profiles.
- **Target libraries** — ASAP7 7nm, SKY130 130nm, GF180MCU 180nm, Nangate45 45nm, and Artix-7 FPGA (LUT6).
- **Static timing analysis** — SDC-driven timing tables, critical-path summaries, and PPA metrics (Fmax, WNS/TNS, area, power, cell/LUT/DFF counts).
- **Simulation** — event-driven RTL simulation with stimulus patterns and a waveform viewer.
- **Netlist schematics** — interactive gate-level netlist visualization.
- **RTL-to-GDSII physical flow** — floorplan, placement, CTS, routing, signoff DRC/LVS, and mask-stream export.
- **GDSII & KLayout** — binary GDSII writer/reader, KLayout-palette PNG snapshot, `.lyp` layer properties, and OpenROAD/Yosys/Magic Tcl export.
- **PDK packages** — SHA-256 verified on-device standard-cell rule packages plus RAM/thermal guards for long jobs.
- **Reports & export** — engineering JSON, STA CSV, executive summary, netlist `.v`, and a shareable GDSII bundle via Android share sheet.
- **Local-first** — on-device JSON storage, auto-save drafts, dark industrial theme, and editor preferences.

## Physical design flow

`Synthesis → Floorplan → Placement → CTS → Routing → Signoff DRC/LVS → GDSII`

The flow produces placed-cell layouts, routed wire/via geometry, die/core metrics, utilization, clock skew, hold/setup slack, DRC/LVS status, a binary `.gds` stream, a companion `.lyp`, a KLayout-style PNG snapshot, and a reusable Tcl script.

## App screens

| Tab | Purpose |
| --- | --- |
| Projects | Create/import RTL projects, open presets, manage saved designs and run history |
| Editor | Multi-file code editor with syntax highlighting, tabs, import, and auto-save |
| Synthesis | Select top module, engine, target library, goal, clock period, SDC, and physical flow options |
| Reports | STA/PPA results, GDS layout viewer, simulation waveforms, node inspection, and export/share |
| Settings | Theme, editor preferences, auto-save, default engine/library, and optional local bridge |

## Tech stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3), Navigation |
| Architecture | MVVM, ViewModel, repository-style data layer |
| Storage | On-device JSON files in app-private storage |
| Physical | Custom Kotlin GDSII writer/reader, KLayout-style renderer, PDK rule packages |
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
├── data/
│   ├── local/        # on-device JSON workspace store (drafts, designs, runs, settings)
│   └── model/        # RTL designs, files, synthesis runs, libraries, settings
├── domain/synth/     # HDL parsers (Verilog/SystemVerilog/VHDL), preprocessor,
│                     # synthesis engine, SDC/STA, simulator, RTL-to-GDS flow,
│                     # GDSII writer, KLayout renderer, PDK catalog
└── ui/               # Compose screens, components, theme, ViewModel
```

## Local storage & privacy

All projects, synthesis runs, active drafts, settings, and generated PDK packages are stored locally on-device in app-private storage (see `LocalWorkspaceStore` and `PdkCatalogManager`). No account or cloud service is required.
