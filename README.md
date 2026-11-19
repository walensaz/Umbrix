# Umbrix

A desktop log and forensic analysis tool for investigating outages and multi-gigabyte log files (20GB–100GB+) with low memory overhead and zero raw log bytes on the JVM heap.

Umbrix keeps log bytes off-heap in the OS page cache (`java.lang.foreign.MemorySegment`) and uses the Java Vector API (`jdk.incubator.vector`) to scan, index, and search multi-gigabyte files without freezing the UI or blowing out RAM.

> **Status & Pricing Note:** Umbrix is currently in **early public release / beta** and is **completely free to use** right now. Payment checkout and code-signing certificates aren't set up yet while v1.x stabilizes—see [Licensing](#licensing) and [Unsigned Binary Instructions](#opening-unsigned-binaries-windows--macos) below.

---

## Benchmarks & Comparison

Measured on Azul OpenJDK 25 LTS against public datasets from **[logpai/loghub](https://github.com/logpai/loghub)** (reproducible locally via `./gradlew :umbrix-core:benchmarkLargeFiles`).

### Measured Performance

| Dataset | File Size & Lines | First Render | Full Index (Cold / Warm) | JVM Heap & Index RAM | Search Throughput |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Synthetic Log** | ~2.00 GB (~15M lines) | < 20 ms | < 1.0 s | < 15 MB (~400 KB idx) | 4.5+ GB/s |
| **Hadoop HDFS** | 5.05 GB (16.89M lines) | 40 – 68 ms | 2.06 s / 0.72 s | 11 MB heap (1.01 MB idx) | 2.97 – 5.43 GB/s |
| **Thunderbird** | 29.61 GB (211.21M lines) | 16 – 59 ms | 22.64 s / 0.83 s | 25 MB heap (12.69 MB idx) | 1.93 – 2.82 GB/s |

### Tool Comparison & Honest Trade-Offs

| Capability | **Umbrix** | **klogg** (C++/Qt) | **lnav** (Terminal) | **EmEditor** | **ripgrep + less** (CLI) | **VS Code / Sublime** |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **First Screen** | **16 – 68 ms** | 2 – 60 s (scales with size) | Instant top-screen | 1 – 15 s | Instant (<10 ms) | 15 s (2GB) / Crash (30GB) |
| **Full Index (5GB / 30GB)** | **2.06 s / 22.64 s** (<1s warm) | 5–8 s / 45–60 s | ~60 s / 4–8 min (SQLite) | N/A (disk-paged) | **0 s** (no index) | Multi-min / **Crash (OOM)** |
| **RAM (5GB / 30GB)** | **11 MB / 25 MB heap** (1–12.7MB idx) | ~400 MB / 1.8–2.5 GB | ~650 MB / 2–4 GB | ~150 MB / ~250 MB (+ swap) | **~10 MB** | ~6+ GB / **Crash (OOM)** |
| **Index RAM Overhead** | **~0.04% of file** | ~6 – 8% | ~10 – 15% | 0% (disk swap) | **0%** | N/A |
| **Stack Trace Folding** | **Yes** (off-heap bitmap) | No | Yes (predefined schemas) | No | No | Yes |
| **AI / MCP Integration** | **Embedded MCP Server** | None | None | None | None | Editor plugins (fail on 30GB) |

- **Where Umbrix fits best:** Interactive desktop triage of 5GB–30GB+ logs with minimal heap usage, instant scrolling, timeline scrubbing, stack-trace folding, and local AI/MCP slicing.
- **Where other tools fit better:** `ripgrep` needs zero indexing and streams cold files from any CLI; `lnav` runs over headless SSH and supports SQL queries; `EmEditor` / `Sublime Text` / `VS Code` are full text/code editors; and CLI/C++ tools are <15MB binaries whereas Umbrix bundles an ~85MB stripped JDK 25 runtime.

---

## Key Features

- **Instant Viewport (<70ms):** Indexes the first 32MB chunk immediately so you can scroll and search right away while the rest of the file finishes indexing in the background.
- **Low RAM Overhead:** Uses a two-level sparse index (~13MB for a 30GB / 211M-line file) and keeps raw log bytes out of JVM GC heap.
- **Forensic Triage Tools:** Dual-handle time-range scrubber, inter-record latency-gap detection, collapsible multi-line stack traces, synchronized split-pane views, bookmarks, scratchpads, and an incident timeline.
- **Safe Live Tailing:** Memory-maps static files and uses non-locking positional reads (`ReadFile` with delete-sharing on Windows; `pread` on macOS/Linux) for active logs to avoid file locks and `SIGBUS` crashes on rotation.
- **Embedded MCP Server:** Exposes local JSON-RPC/SSE tools (`http://127.0.0.1:4242/mcp`) so AI assistants (Cursor, Claude Desktop, Copilot) can search and slice massive logs without flooding their context windows, with client-side secret redaction.
- **Apache 2.0 Plugin SDK:** Extensible via `:umbrix-plugin-api` and `:umbrix-plugin-ui-api` for custom file formats, dock views, query functions (`fn:<name>`), and MCP tools.
  - *Coming Later:* An official **JVM Diagnostic & Profiling Toolkit (`umbrix-plugin-jvm`)** for off-heap `.hprof` heap dumps, `.jfr` recordings, and GC logs is being held back for a separate future release.

---

## Downloads & Quick Start

> **Zero Java setup needed for packaged builds.** Release bundles include a stripped Azul OpenJDK 25 LTS runtime (~85MB). Download, extract/install, and launch—or [build from source](#building-from-source).

### Pre-Built Downloads (`v1.0.1`)

| Platform | Format | Download |
| :--- | :--- | :--- |
| **Windows (x64)** | Portable ZIP | [`Umbrix-1.0.1-windows-x64-portable.zip`](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.1-windows-x64-portable.zip) |
| **Windows (x64)** | MSI Installer | [`Umbrix-1.0.1.msi`](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.1.msi) |
| **macOS (Apple Silicon)** | DMG Image | [`Umbrix-1.0.1.dmg`](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.1.dmg) |
| **macOS (Apple Silicon)** | Portable ZIP | [`Umbrix-1.0.1-macos-portable.zip`](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.1-macos-portable.zip) |

All releases and checksums are on the [GitHub Releases Page](https://github.com/walensaz/Umbrix/releases).

### Opening Unsigned Binaries (Windows & macOS)

Release binaries are **not code-signed yet** (Authenticode / Apple Developer ID signing is not configured for early builds), so your OS will show an unrecognized-app warning on first launch:

#### Windows (SmartScreen)
1. Download `Umbrix-1.0.1-windows-x64-portable.zip` (or `.msi`) and extract it.
2. *(Optional)* Right-click the downloaded `.zip`/`.msi` $\to$ **Properties** $\to$ check **Unblock** $\to$ **Apply** before extracting (or run `Unblock-File .\Umbrix-1.0.1-windows-x64-portable.zip` in PowerShell).
3. If Windows Defender SmartScreen says *"Windows protected your PC"*, click **More info** $\to$ **Run anyway**.
4. *(Optional)* Run `register-open-with.bat` to add **"Open with Umbrix"** to the Explorer right-click menu (writes to `HKCU`, no admin rights needed).

#### macOS (Gatekeeper)
1. Open `Umbrix-1.0.1.dmg` and drag `Umbrix.app` to `/Applications` (or extract `Umbrix-1.0.1-macos-portable.zip`).
2. On first launch, macOS blocks unsigned `.app` bundles opened from Finder. Use any of these options:
   - **Run directly from Terminal** (skips Finder's Gatekeeper check, and lets you pass a file directly):
     ```bash
     /Applications/Umbrix.app/Contents/MacOS/Umbrix /path/to/logfile.log
     ```
     *(If using the portable ZIP, run `./Umbrix.app/Contents/MacOS/Umbrix` from the extracted folder.)*
   - **Clear quarantine attribute** (so Finder / Spotlight / Dock launch works normally):
     ```bash
     xattr -cr /Applications/Umbrix.app
     ```
   - **System Settings (macOS Sequoia / Sonoma):** Try opening `Umbrix.app`, click **Done** on the warning, then open **System Settings $\to$ Privacy & Security**, scroll down to Security, and click **Open Anyway**. *(On older macOS versions, Control-click `Umbrix.app` $\to$ **Open** also works.)*

*(If you prefer not to run unsigned binaries, you can inspect the code and run directly via `./gradlew :umbrix-app:run`.)*

---

## Keyboard Shortcuts

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| `Ctrl+O` / `Cmd+O` | **Open File** | Open log files via picker or drag-and-drop |
| `Ctrl+F` / `Cmd+F` | **Find** | Search with regex (`Alt+R`), case (`Alt+C`), and whole-word (`Alt+W`) toggles |
| `Enter` / `Shift+Enter` | **Next / Prev Match** | Step through matches (`F3` / `Shift+F3`) |
| `F4` / `Shift+F4` | **Next / Prev Anomaly** | Step between `ERROR` and `WARN` lines |
| `Ctrl+G` / `Cmd+G` | **Go to Line / Time** | Jump to a physical line or timestamp (`HH:mm:ss`, ISO-8601) |
| `Ctrl+T` | **Time Scrubber** | Toggle dual-handle time range filter |
| `Ctrl+B` / `F2` | **Bookmarks** | Toggle bookmark (`Ctrl+B`) and jump between bookmarks (`F2` / `Shift+F2`) |
| `Ctrl+\` / `Ctrl+Alt+\` | **Split View** | Split horizontally/vertically; click `⇋` on divider to sync scroll |
| `Ctrl+N` / `Ctrl+S` | **Scratchpad** | Open an in-app note/excerpt tab and save to disk |
| `Ctrl+Shift+P` / `Ctrl+P` | **Command Palette** | Run actions, switch tabs, or use Vim `:` commands |
| `Ctrl+Shift+I` | **AI Assistant** | Open slide-out drawer for local (Ollama) or API-backed queries |
| `Ctrl+,` | **Settings** | Themes, fonts, AI endpoints, and plugin manager |
| `F1` | **Help** | Keyboard shortcuts reference |

---

## AI Agent Integration (MCP Server)

When Umbrix is running (and **Settings $\to$ AI & MCP Server** is enabled), it runs a local **Model Context Protocol (MCP)** HTTP/SSE server on `http://127.0.0.1:4242/mcp`.

**Cursor / Antigravity / Claude Desktop (`mcp.json`):**
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

Built-in MCP tools: `search_logs`, `get_time_slice`, `extract_causal_context`, `get_error_clusters`, and `trim_and_export`.

---

## Architecture & Tech Stack

| Layer | Technology | Role |
| :--- | :--- | :--- |
| **UI & Rendering** | Kotlin 2.1 + Compose Desktop + JetBrains Jewel | Skia GPU-accelerated UI (DirectX 12 / Metal / Vulkan) with 64-bit virtual viewport |
| **Core Engine** | Scala 3.3 LTS | Query AST, indexing coordinators, unboxed `opaque type` primitives |
| **Off-Heap Memory** | Java 25 LTS FFM API (`java.lang.foreign.*`) | Deterministic `Arena`-scoped mmap and native OS I/O downcalls |
| **Vector Search** | Java Vector API (`jdk.incubator.vector.*`) | AVX-512, AVX2, and ARM NEON newline/substring scanning |
| **Bitsets & Regex** | RoaringBitmap, fastutil, RE2J | 64-bit bitsets, primitive collections, linear-time $O(N \cdot M)$ regex |

Further reading:
- [System Architecture](docs/ARCHITECTURE.md) | [System Design](docs/SYSTEM_DESIGN.md) | [Memory Analysis](docs/MEMORY_AND_PERFORMANCE_ANALYSIS.md)
- [UI & 64-Bit Viewport](docs/UI_ARCHITECTURE_AND_VIEWPORT.md) | [MCP & AI Governance](docs/MCP_SERVER_AND_AGENT_ECOSYSTEM.md)
- [Plugin Development Guide](docs/PLUGIN_DEVELOPMENT_GUIDE.md) | [Testing Strategy](docs/TESTING_STRATEGY.md) | [Benchmarks](docs/BENCHMARKS.md)

---

## Building from Source

**Prerequisites:** OpenJDK 25 or Azul Zulu 25 LTS (`--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`). Gradle 8.12 wrapper is included.

```bash
# Compile Scala and Kotlin modules
./gradlew compileScala compileKotlin

# Run unit tests
./gradlew test

# Launch the desktop app locally
./gradlew :umbrix-app:run --args="/path/to/logfile.log"

# Build standalone fat JAR
./gradlew :umbrix-app:shadowJar

# Build stripped JRE (jlink) and portable native bundle
./gradlew :umbrix-app:createMinRuntime
./gradlew :umbrix-app:packagePortableZip
```

---

## Contributing, Bug Reports & Pull Requests

Bug reports, feature requests, and pull requests are welcome:
- **Found a bug or crash?** Open a [GitHub Issue](https://github.com/walensaz/Umbrix/issues) with your OS, approximate file size, and any error log/stack trace from `~/.umbrix/reports/` (if generated).
- **Want to submit a PR?** See [CONTRIBUTING.md](CONTRIBUTING.md) for local build/test steps, core off-heap invariants, and contributor licensing notes before opening a pull request.

---

## Licensing

Umbrix is **free to download, build, and use during beta / early public release**—there is no paywall, license check, or checkout set up yet while v1.x stabilizes.

- **Core Application:** Copyright (c) 2026 Zachary Walensa. Source-available under [LICENSE](LICENSE) (redistribution/reselling as a commercial product or hosted service, as well as AI/ML training or TDM dataset harvesting, are not permitted without written permission; post-GA honor-system commercial tiers are outlined in [COMMERCIAL_AND_LICENSING.md](COMMERCIAL_AND_LICENSING.md)).
- **Plugin SDK (`:umbrix-plugin-api`, `:umbrix-plugin-ui-api`):** Open source under **Apache 2.0**.
