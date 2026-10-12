# Umbrix: High-Throughput AI Log Workstation

> **Project Name:** `Umbrix` (*Umbra* [eclipse occlusion] + *Strix* [nocturnal precision] / *Index*)  
> **Tagline:** *Extinguish the noise. Illuminate the anomaly.*  
> *See [docs/BRANDING_AND_NAMING.md](docs/BRANDING_AND_NAMING.md) for full brand identity & nomenclature analysis.*

---

## 1. Executive Summary

Umbrix is an industrial-grade, GPU-accelerated desktop workstation engineered to open, index, search, and analyze **20 GB – 100 GB+ log files** across macOS, Windows, and Linux with:
- **Instant initial screen render:** **<105 ms time-to-first-render** on 30GB+ logs (500k+ lines ready instantly in Chunk 0 via Two-Phase Progressive Indexing).
- **IntelliJ-style live progress telemetry:** Sleek status bar gradient progress bar (`#2196F3` to `#00E5FF`) tracking live SIMD and Tier 2 semantic indexing with throughput metrics.
- **Fluid 60–120 FPS scrolling** powered by a hardware-accelerated Skia rendering engine (DirectX 12 / Metal / Vulkan) with a bounded 512-entry highlight memoization cache.
- **Selection-follow navigation:** Hold a text selection at a viewport edge to scroll horizontally, vertically, or diagonally without moving the pointer. Double-click replaces a highlight with the word under the cursor, triple-click selects the displayed line, and dragging extends by whole words/lines; `Ctrl/Cmd+C` copies the highlight in normal, filtered, and temporal views (bounded to 8,192 rows / 1 MiB, with 4,096 displayed characters per line).
- **Strict $<150\text{ MB}$ RSS memory footprint** on a 25GB file with **0 bytes of log data ingested into the JVM Garbage Collector heap**.
- **Modular multi-pane docking:** Arbitrary horizontal and vertical split panes (`Ctrl+\` / `Ctrl+Alt+\`), proportional dividers, synchronized scrolling toggle (`⇋`), and floating secondary desktop windows (`DetachedWindowsHost`).
- **Fast temporal search & log trimming:** $O(\log N)$ binary wall-clock seeks and zero-copy kernel slicing (APFS `clonefile`, Linux `splice`, Windows `TransmitFile`).
- **Autonomous AI forensics & embedded MCP server:** Native Model Context Protocol (MCP) server hooks for **GitHub Copilot, OpenAI Codex, Claude Code, Cursor, and Antigravity**, enabling AI agents to query 25GB files with $<1,500$ prompt tokens.
- **Iconic desktop presence:** Custom full-bleed squircle dock icon with satin-matte titanium and chromatic cyan/mint monogram on 3D metallic chassis plate, with multi-resolution native taskbar support.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 Compose Desktop UI (Kotlin) + JetBrains Jewel               │
│   - Authentic IntelliJ "New UI" multi-theme engine (10 presets) & typography│
│   - Modular multi-pane docking tree with synchronized delta-line scrolling  │
│   - Universal Multi-Persona Command Palette (Omni, VS Code, IntelliJ, Vim)  │
│   - Forensic Timeline Reconstruction & Incident Tab across multiple files   │
│   - Multi-Row Timeline Selection & Batch Movement (Up/Down/Top/Bottom)       │
│   - In-App Scratchpad Editor Tabs (Ctrl+N, Ctrl+S) with log provenance      │
│   - Unified Scratchpad Search: Workstation SearchBar + bi-directional Ctrl+R│
│   - Gutter Bookmarks (📌), F2/Shift+F2 stepping & GPU minimap cyan pins      │
│   - One-Click Incident Report Generation (Markdown post-mortem, Log, JSON)   │
│   - Compose-native cascading ☰ menu (recent files, view, live theme switch) │
│   - Custom full-bleed dock branding (satin-matte titanium & chromatic U)    │
│   - Work-area-aware startup sizing and taskbar-safe maximize behavior       │
│   - Custom 64-bit Virtual Viewport (sliding window supporting 2^63 lines)   │
│   - Smooth kinetic sub-pixel scrolling with hardware-clipped partial lines  │
│   - Exact HarfBuzz glyph cluster hit-testing & I-beam text selection        │
│   - Two-Tier Search Toolbar & Forensic Anomaly Navigation (Shift+F4 / F4)   │
│   - Bottom-right index/document telemetry & live indexing progress           │
│   - Highlight memoization LRU cache sustaining 60-120 FPS scrolling         │
│   - GPU-accelerated "Seismograph" Minimap Gutter with anomaly snap & tooltips│
│   - Dual-Handle Temporal Scrubber, Focus Mode & Latency Gap Detection       │
│   - Dual-Path Monotonic Fast-Path (<30ns) & Out-of-Order Log Resilience     │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │ High-Performance Zero-Copy IPC
                                       │ (Java 25 FFM MemorySegment slices)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                    Core Engine Subsystem (Scala 3 Off-Heap)                 │
│   - Java 25 Foreign Function & Memory (FFM) API (Arena.ofShared)            │
│   - Two-Phase Progressive Indexing (<105ms first render on 32GB files)      │
│   - Two-Level Hierarchical Sparse Index (~13 MB RAM for 25GB file)          │
│   - Zero-Boxing Navigation (SearchResult primitive findNextLine/Previous)   │
│   - Temporal Checkpoint Table (O(log N) binary wall-clock seeks in <1.5ms)  │
│   - Java Vector API (AVX-512 / AVX2 / ARM NEON scanning at 15–25 GB/s)     │
│   - Dual-Path Cross-Platform I/O (mmap static; positional reads for active) │
│   - Cross-Platform Zero-Copy Slicing (APFS clonefile, splice, TransmitFile) │
│   - Off-Heap Disruptor Ring Buffer for live multi-source streaming          │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │ Asynchronous Context Assembly
                                       │ & MCP Tool Execution
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                      Autonomous AI & Embedded MCP Server                    │
│   - Embedded Model Context Protocol (MCP) JSON-RPC server (stdio / SSE / IPC│
│   - Live hook for GitHub Copilot, Claude Code, Cursor, Codex, Antigravity   │
│   - Causal Thread/Trace Reconstruction (MDC trace_id backwards scan)        │
│   - Off-Heap Error Clustering (SimHash collapses 50k traces to 3 centroids) │
│   - Natural Language to SIMD Filter AST Compiler (LangChain4j 1.x)          │
│   - Configurable AI Governance: Local SLMs (Ollama) & Enterprise Zero-Retain│
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 🚀 Quick Start & Downloads

> **Zero Java installation required!** Umbrix is distributed with a bundled, stripped **Azul OpenJDK 25 LTS** runtime created via `jlink` (~85 MB) featuring AVX-512 SIMD vector acceleration (`jdk.incubator.vector`) and the Java 25 Foreign Function & Memory (FFM) API. Simply download, extract, and run.

### Official Alpha Release Downloads

| Platform | Format | Description | Direct Download |
| :--- | :--- | :--- | :--- |
| **Windows (x64)** | **Portable ZIP** | Zero-install portable bundle. Extract anywhere and launch `Umbrix.exe`. | [📦 **Download Windows Portable (.zip)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-0.1.0-windows-x64-portable.zip) |
| **Windows (x64)** | **MSI Installer** | Windows installer setup wizard with desktop shortcuts and file associations. | [💿 **Download Windows Installer (.msi)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-0.1.0.msi) |
| **macOS (Apple Silicon)** | **DMG Image** | Native Apple Silicon disk image. Drag-and-drop into `/Applications`. | [🍏 **Download macOS DMG (.dmg)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-0.1.0.dmg) |
| **macOS (Apple Silicon)** | **Portable ZIP** | Zero-install standalone archive for macOS. | [📦 **Download macOS Portable (.zip)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-0.1.0-macos-portable.zip) |

*All release assets, tags, and checksums are available on the [GitHub Releases Page](https://github.com/walensaz/Umbrix/releases/latest).*

---

### Windows Setup & Running
1. Download **`Umbrix-0.1.0-windows-x64-portable.zip`** and extract it to any folder.
2. *(Optional)* Run **`register-open-with.bat`** inside the folder to add **"Open with Umbrix"** to your Windows Explorer right-click context menu for all log formats (writes directly to `HKCU`—**zero administrator/UAC rights required**).
3. Double-click **`Umbrix.exe`** to launch the workstation.
4. Drag and drop any `.log`, `.txt`, `.jsonl`, `.out`, or `.err` file directly into the window.

### macOS Setup & Running
1. Download **`Umbrix-0.1.0.dmg`**, double-click to open, and drag **Umbrix.app** to your `/Applications` folder.
2. **First-Launch Gatekeeper Notice:** Because this is an alpha release not yet signed with a paid Apple Developer certificate, macOS will block the initial launch. To bypass:
   - **Method A (GUI):** In Finder, navigate to `/Applications`, **Right-click (or Control-click)** on `Umbrix.app`, select **Open**, and click **Open** in the confirmation dialog.
   - **Method B (Terminal):** Run the following command once:
     ```bash
     xattr -cr /Applications/Umbrix.app
     ```
3. Open files via Finder right-click ("Open With" $\to$ Umbrix), drag-and-drop onto the dock icon, or using `Ctrl+O` / `Cmd+O`.

---

### Quick Interaction Guide
- **Open Log:** `Ctrl+O` (`Cmd+O`) or drag-and-drop any file into the workspace.
- **Search:** `Ctrl+F` (`Cmd+F`) with regex/case/word toggles. Press `Enter` / `Shift+Enter` (or `F3` / `Shift+F3`) to step through hits.
- **Error / Anomaly Triage:** Press `F4` to jump to the next `ERROR` or `WARN` line (`Shift+F4` for previous).
- **Time Scrubbing:** Press `Ctrl+T` to toggle the dual-handle temporal range scrubber and focus mode.
- **Split & Compare:** Press `Ctrl+\` (horizontal split) or `Ctrl+Alt+\` (vertical split); click `⇋` to synchronize scrolling across panes.
- **Scratchpad:** Press `Ctrl+N` to open an in-memory editor tab to take notes or paste extracted logs with source provenance headers (`Ctrl+S` to save).
- **Bookmarks:** Press `Ctrl+B` to pin a cyan bookmark (`📌`); press `F2` / `Shift+F2` to jump between bookmarks.
- **Command Palette:** Press `Ctrl+Shift+P` (`Cmd+Shift+P`) to open the multi-persona command palette (VS Code, IntelliJ, or Vim `:` modes).
- **Settings & Themes:** Press `Ctrl+,` to switch between 10 built-in dark/light themes, adjust typography and zoom, or configure AI/MCP endpoints.
- **Logs & Diagnostics:** If you encounter an issue, access logs via **☰ Menu $\to$ Open Logs Folder** or check `~/.umbrix/reports/`.

---

## 3. Fast AI Agent Integrations (Cursor, Codex, Claude Code, Antigravity)

Umbrix runs an embedded **Model Context Protocol (MCP)** server on `http://127.0.0.1:4242/mcp` and via `stdio`, allowing external AI tools to query gigabyte log files without context window overflow:

### Cursor (`.cursor/mcp.json` or Cursor Settings)
```json
{
  "mcpServers": {
    "umbrix": {
      "url": "http://127.0.0.1:4242/mcp",
      "transport": "sse"
    }
  }
}
```

### Claude Desktop (`claude_desktop_config.json`)
```json
{
  "mcpServers": {
    "umbrix": {
      "command": "java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-jar",
        "/path/to/umbrix/umbrix-app/build/libs/umbrix-0.1.0-SNAPSHOT-all.jar",
        "--headless",
        "mcp"
      ]
    }
  }
}
```

### Forensic Tools Available to AI Agents:
- `search_logs`: High-speed SIMD vector search over multi-gigabyte files with time bounds.
- `get_time_slice`: Wall-clock range retrieval in $<1.5\text{ms}$ via $O(\log N)$ binary search.
- `extract_causal_context`: Backward causal trace extraction isolating transactions/threads.
- `get_error_clusters`: Exception normalization and root-cause cluster grouping.
- `trim_and_export`: DMA zero-copy sub-file slicing and export.

---

## 4. Workstation Keyboard Shortcuts Cheatsheet

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| **`Ctrl+O`** / `Cmd+O` | **Open Log File** | Native OS file picker or window drag-and-drop. |
| **`Ctrl+N`** / `Cmd+N` | **New Scratchpad** | In-app scratchpad editor tab for notes and log extractions. |
| **`Ctrl+Alt+S`** | **Manage Scratchpads**| Search, reopen, copy snippets, or delete persisted scratchpads. |
| **`Ctrl+S`** / `Cmd+S` | **Save Buffer** | Persists dirty scratchpad contents to disk. |
| **`Ctrl+W`** / `Cmd+W` | **Close Tab** | Closes the active log session or scratchpad buffer. |
| **`Ctrl+F`** / `Cmd+F` | **Find in Log** | Opens single-row search bar with regex/case/word toggles. |
| **`Enter`** / **`Shift+Enter`** | **Next / Prev Match** | Advances to next/previous search hit (`F3` / `Shift+F3`). |
| **`F4`** / **`Shift+F4`** | **Next / Prev Anomaly**| Navigates directly through error and warning lines. |
| **`Ctrl+G`** / `Cmd+G` | **Go to Line / Time** | Numeric line jump or wall-clock timestamp seeker. |
| **`Ctrl+T`** | **Time Range Scrubber**| Opens dual-handle temporal scrubber and Focus Mode. |
| **`Ctrl+B`** | **Toggle Bookmark** | Pins a cyan bookmark (`📌`) on the current row and GPU minimap. |
| **`F2`** / **`Shift+F2`** | **Next / Prev Bookmark**| Steps between bookmarked lines across the document. |
| **`Ctrl+Alt+B`** | **Filter Bookmarks** | Viewport filter mode displaying *only* bookmarked lines. |
| **`Ctrl+Shift+P`** / `Ctrl+P` | **Command Palette** | Multi-persona palette (Omni, VS Code, IntelliJ, Vim `:`). |
| **`Ctrl+Shift+I`** | **AI Forensic Drawer** | Slide-out AI assistant with natural language log querying. |
| **`Ctrl++`** / **`Ctrl+-`** | **Zoom In / Out** | Increases or decreases editor typography font size. |
| **`Ctrl+MouseWheel`** | **Kinetic Zoom** | Smooth mouse-wheel font size adjustment. |
| **`F1`** | **Help & Keymap** | Comprehensive keyboard shortcuts cheatsheet modal. |
| **`Ctrl+,`** | **Settings Dialog** | Themes (10 presets), Fonts, Viewport, Performance, AI/MCP. |

---

## 5. Comprehensive Documentation Map

All architectural analyses, deep-dive specifications, and engineering decisions have been documented across dedicated specifications:

| Document | Description |
| :--- | :--- |
| **[Developer & Agent Guide](AGENTS.md)** | Mandatory guidelines for AI agents and developers: non-negotiable performance invariants, module boundaries, zero-boxing rules, and code checklist. |
| **[System Design & Evolution](docs/SYSTEM_DESIGN.md)** | Central architectural design tracker, subsystem contracts, data pipelines, error reporting architecture, and style invariants. |
| **[Implementation Status & Roadmap](docs/IMPLEMENTATION_STATUS_AND_ROADMAP.md)** | Live milestone tracking, verified deliverables matrix, exact JDK 25 path, and phased feature timeline. |
| **[Code Guidelines & Standards](docs/CODE_GUIDELINES_AND_STANDARDS.md)** | Engineering standards: Hexagonal architecture, SOLID without God objects, explicit constructor injection, zero-boxing opaque types, and Tri-Mode Viewport math. |
| **[System Architecture](docs/ARCHITECTURE.md)** | Deep technical specification: Java 25 FFM API lifecycle, Two-Level Sparse Indexing, AVX-512 vector pipelines, cross-platform I/O, 64-bit viewport, and tri-tier concurrency. |
| **[Memory & 25GB Benchmark](docs/MEMORY_AND_PERFORMANCE_ANALYSIS.md)** | Exact mathematical memory breakdown for a 25GB file (~13 MB index, ~85 MB RSS), comparison vs. VS Code / Sublime / Notepad++, and cross-platform OS paging mechanics. |
| **[Model Context Protocol (MCP) Server](docs/MCP_SERVER_AND_AGENT_ECOSYSTEM.md)** | Embedded MCP server specifications, tool schemas (`search_logs`, `extract_causal_context`, `get_error_clusters`, `trim_and_export`), and integration guides for Copilot, Claude Code, and Cursor. |
| **[Feature Catalog & Ideas](docs/FEATURES_AND_IDEAS.md)** | Comprehensive catalog covering Tri-Mode Viewport navigation, GPU seismograph minimap, RE2 linear-time search, temporal range scrubbing, and zero-copy log trimming. |
| **[Ecosystem & Libraries Strategy](docs/LIBRARIES_ECOSYSTEM.md)** | Rigorous build vs. adopt matrix: JetBrains Jewel, Skia/Skiko, Java 25 FFM API, Java Vector API, RE2J, RoaringBitmap, LangChain4j, Ollama, and fastutil. |
| **[Big Data & Live Streaming](docs/BIG_DATA_AND_LIVE_STREAMING.md)** | Multi-source ingestion (SSH/SFTP, Kubernetes Pods, Docker sockets, Syslog UDP/TCP, Cloud Object Storage S3 range requests), off-heap ring buffers, and dynamic extent paging. |
| **[UI Architecture & Viewport](docs/UI_ARCHITECTURE_AND_VIEWPORT.md)** | Compose Desktop UI architecture, 64-bit sliding window viewport, `UmbrixThemeSpec` dynamic theming, modal dialogs, and component contracts. |
| **[UI Overhaul Master Plan](docs/UI_OVERHAUL_PLAN.md)** | Master roadmap for the 7-phase workstation UI overhaul: theme engine, 10 presets, font configuration, hamburger menu, modal standard, settings expansion, and UX polish. |
| **[CI, Benchmarking & Testing Strategy](docs/TESTING_STRATEGY.md)** | Headless test architecture, SIMD alignment benchmarks, memory leak regression tests, and verification workflows. |
| **[Performance & Product Audit Roadmap](docs/PERFORMANCE_AND_PRODUCT_AUDIT.md)** | Proposed, evidence-based roadmap for large-file performance, benchmarks, resource policy, and product positioning. |
| **[Questions, Risks & Requirements](docs/QUESTIONS_AND_RISKS.md)** | Resolved architectural decisions matrix, failure modes analysis (Windows sharing violations, Unix SIGBUS truncation, 20MB giant lines, ReDoS), and formal Requirements Matrix. |
| **[Brand Identity & Nomenclature](docs/BRANDING_AND_NAMING.md)** | Selection of **`Umbrix`** (*Umbra* + *Strix* / *Index*), aesthetic lineage (*Sublime / Obsidian*), and the space/astronomy portfolio. |

---

## 6. Technology Stack Summary

| Subsystem | Technology | Architectural Role |
| :--- | :--- | :--- |
| **UI Presentation** | **Kotlin Multiplatform (Desktop)** | Powers declarative UI, Skia windowing, and native event loops. |
| **Component Kit** | **JetBrains Jewel** | Standalone IntelliJ IDEA "New UI" desktop components (tabs, dense lists, toolbars, dark theme). |
| **Graphics Engine** | **Skiko / Skia (`org.jetbrains.skia`)** | Hardware-accelerated GPU canvas rendering (DirectX 12 / Metal / Vulkan). |
| **Core Engine** | **Scala 3 (JVM)** | High-throughput parsing, off-heap data structures, SIMD vector kernels, and actor pipelines. |
| **Memory Management**| **Java 25 LTS FFM API (`java.lang.foreign.*`)**| Zero-copy 64-bit continuous memory mapping, deterministic off-heap allocation, zero GC overhead. |
| **SIMD Acceleration** | **Java Vector API (`jdk.incubator.vector.*`)**| AVX-512 / AVX2 / ARM NEON vector instructions for newline indexing and substring searching at 15–25 GB/s. |
| **Search Engine** | **RE2J / Rust Regex + RoaringBitmap** | ReDoS-immune linear-time regex execution + microsecond bitwise boolean set filtering. |
| **Temporal Index** | **Off-Heap Checkpoint Table** | Sampled timestamp table enabling $O(\log N)$ binary wall-clock seeking in $<1.5\text{ ms}$. |
| **Agent Protocol** | **Model Context Protocol (MCP)** | JSON-RPC 2.0 interface over stdio, SSE, and local IPC for external AI agents (Copilot, Codex, Claude Code, Cursor). |
| **AI Orchestration** | **LangChain4j + Ollama / Enterprise Cloud**| Local SLMs (air-gapped) or Enterprise Zero-Retention Cloud (OpenAI, Claude, Google Vertex). |
| **Build System** | **Gradle (Kotlin DSL)** | Multi-module build bridging Scala 3 (`:umbrix-core`) and Kotlin desktop compilation (`:umbrix-ui`). |

---

## 7. Development Prerequisites & Build Commands

- **Target JDK:** **OpenJDK 25 LTS** or **Azul Zulu 25 LTS** (`--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`).
- **Build Tool:** Gradle 8.12+ with Kotlin DSL (`gradlew.bat` / `./gradlew`).
- **IDE:** IntelliJ IDEA 2024.2+ (equipped with Scala and Kotlin Multiplatform plugins).

### Build & Run Commands

```powershell
# Compile all Scala 3 and Kotlin Compose modules
.\gradlew.bat compileScala compileKotlin

# Run all unit tests across all 7 subprojects
.\gradlew.bat test

# Package cross-platform executable Fat JAR (bundles Windows, macOS ARM64/x64, and Linux Skiko binaries)
.\gradlew.bat :umbrix-app:shadowJar

# Run interactive workstation on Windows
.\gradlew.bat :umbrix-app:run --args="C:\path\to\your\file.log"

# Run interactive workstation on macOS / Linux
./gradlew :umbrix-app:run --args="/path/to/your/file.log"

# Run Fat JAR directly with JDK 25:
java --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar .\umbrix-app\build\libs\umbrix-0.1.0-SNAPSHOT-all.jar "C:\path\to\your\file.log"
```

---

## 8. Native Distribution & Packaging

Umbrix ships with a stripped, self-contained **Azul OpenJDK 25 LTS** runtime created via `jlink`, eliminating any requirement for end users to install a JRE. The custom runtime is stripped to ~85 MB uncompressed while retaining full Java 25 FFM (`java.lang.foreign.*`) and SIMD Vector API (`jdk.incubator.vector.*`) capabilities.

### Packaging Commands

```powershell
# 1. Create stripped minimal JDK 25 runtime (~85MB)
.\gradlew.bat :umbrix-app:createMinRuntime

# 2. Build standalone native application image (Umbrix.exe / Umbrix.app + bundled runtime)
.\gradlew.bat :umbrix-app:createAppImage

# 3. Create zero-admin portable ZIP bundle (build/dist/Umbrix-0.1.0-windows-x64-portable.zip)
.\gradlew.bat :umbrix-app:packagePortableZip

# 4. Create macOS DMG disk image (on macOS)
./gradlew :umbrix-app:packageDmg

# 5. Create Windows MSI installer (requires WiX toolset)
.\gradlew.bat :umbrix-app:packageMsi
```

### Windows "Open with Umbrix" Explorer Context Menu (Zero Admin Rights)

The portable bundle includes user-space registration scripts that write directly to `HKCU\Software\Classes\*\shell\OpenWithUmbrix` without requiring UAC administrator elevation:

- **Enable Context Menu:** Run `register-open-with.bat` inside the extracted folder. Adds `"Open with Umbrix"` to the top-level Windows Explorer right-click context menu for all files and registers associations for `.log`, `.txt`, `.out`, `.err`, `.trace`, `.jsonl`, and `.gz`.
- **Remove Context Menu:** Run `unregister-open-with.bat` to cleanly remove all registry entries.
- **Inno Setup Installer:** A complete Windows wizard installer script is provided at `distribution/windows/installer.iss` for automated desktop shortcuts and file associations.

### macOS Finder Integration & Homebrew Cask

- **Finder "Open With":** The macOS `.app` bundle declares native `CFBundleDocumentTypes` in `Info.plist` for `public.plain-text`, `public.log`, `.log`, `.txt`, `.out`, `.err`, `.trace`, and `.jsonl`.
- **Native Event Dispatch:** Opens files seamlessly from Finder via macOS `Desktop.getDesktop().setOpenFileHandler`.
- **Homebrew Cask (Non-Admin User Space):** A Cask formula is provided at `distribution/macos/umbrix.rb`:
  ```bash
  brew install --cask umbrix
  ```

### Single-Instance IPC Architecture

Umbrix implements a lightweight single-instance loopback router on `127.0.0.1:4243`:
- Selecting multiple files in Explorer/Finder or invoking the executable repeatedly forwards file paths to the running instance over TCP in sub-millisecond time.
- The secondary process terminates instantly, preventing multiple heavy JVM processes from running concurrently.
- The active window automatically un-minimizes, brings itself to the foreground (`toFront()`), and opens each requested file in a new workspace tab.

### Forensic Diagnostics & Debug Logging

- **CLI Flag:** Launch with `--debug`, `-v`, `--verbose`, or environment variable `UMBRIX_DEBUG=1` to elevate the async logging system to full `DEBUG` verbosity.
- **Log Files:** Rolling logs are stored off-heap in `~/.umbrix/logs/umbrix.log`.
- **One-Click Log Access:** Access logs directly within the app via **☰ Menu -> Open Logs Folder** or the **About Umbrix** dialog.

