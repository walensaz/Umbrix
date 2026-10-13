# Licensing & Commercial Support

Umbrix follows an **honor-system evaluation and support model** inspired by developer-first tools like Sublime Text, TablePlus, and Reaper.

---

## 1. The Core Philosophy

* **Free to Evaluate & Use:** Umbrix is free to download, build, and use with zero feature restrictions, zero artificial file size limits, and no time-based lockouts.
* **Respecting Engineers:** No forced cloud accounts, no DRM, no phone-home telemetry, and no interruptions during critical production triage.
* **Public & Auditable Source:** The source code is completely public and buildable from source.

---

## 2. Licensing & Support Tiers

If Umbrix saves you or your company time, please support continued independent development:

| Tier | Description | Price |
| :--- | :--- | :--- |
| **Community & Evaluation** | Free for personal projects, open-source work, education, and unlimited evaluation in any environment. | **$0** (Free Forever) |
| **Personal License** | For individual developers paying with personal funds. Valid across any machine or employer you work with. | **$39 one-time** (perpetual) |
| **Commercial Single Seat** | For companies or individuals expensing a single work license. | **$69 one-time** (perpetual) |
| **Team 5-Pack (Pod)** | Up to 5 commercial seats for SRE / DevOps squads (Save ~$46). | **$299 one-time** |
| **Team 10-Pack (Squad)** | Up to 10 commercial seats for engineering departments (Save ~$141). | **$549 one-time** |
| **Enterprise (20+ seats)** | Volume deployment with centralized invoicing and offline license management. | **$49 / seat** |

---

## 3. Honest Engineering Comparison: Umbrix vs. Other Log Viewers

| Capability | **Umbrix** | **klogg / glogg** (C++/Qt) | **lnav** (Terminal Curses) | **Sublime / UltraEdit** | **VS Code / Electron** |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Max File Capacity** | **100GB+** (64-bit continuous window) | ~10GB–20GB (C++ `mmap` limits) | ~5GB–10GB (bounded by terminal/RAM) | 2GB–5GB (slows on >2GB) | **<1.5GB** (OOMs V8 heap) |
| **Memory Footprint** | **<150MB RSS** (0 bytes in GC heap) | 300MB – 1.5GB heap | 200MB – 1GB | 1GB – 4GB+ | **2GB+ / Crash** |
| **Search Engine** | **AVX-512 SIMD** (15–25 GB/s) + RE2 | C++ Boyer-Moore / PCRE | SQLite + PCRE | Custom C++ regex | JavaScript RegExp (slow) |
| **File Editing** | **Off-heap Piece Table** (never rewrites file) | Read-only | Read-only | In-memory full rewrite | In-memory buffer |
| **Stack Traces** | **Two-Tier Folding** (SIMD + RoaringBitmap) | Basic wrap | Automatic format folding | Basic syntax folding | Basic syntax folding |
| **UI & Rendering** | JetBrains Jewel + Skia GPU (60–120 FPS) | Functional 2000s Qt GUI | Terminal curses (keyboard only) | Polished text editor UI | Modern editor, slow on logs |
| **AI Integration** | **Native Embedded MCP Server** | None | None | Copilot (fails on big files) | Copilot (fails on big files) |
| **Trade-offs / Cons** | Bundled JDK 25 runtime (~85MB); not an IDE/LSP code editor | Dated UI; Windows mmap truncation bugs | Terminal only; no visual timeline scrubbers | Proprietary closed-source ($99/yr) | Chokes on files >1.5GB |
