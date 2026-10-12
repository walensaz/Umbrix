# Umbrix

A fast, GPU-accelerated desktop log viewer built to open, search, and navigate large (20GB – 100GB+) log files with low memory usage (<150MB RAM) and zero garbage collection pauses.

---

## Downloads & Quick Start

> **Zero Java installation required.** Umbrix includes a bundled, minimal Azul OpenJDK 25 LTS runtime (~85 MB) with AVX-512 SIMD vector acceleration and the Java 25 Foreign Function & Memory (FFM) API. Download, extract, and run.

### Pre-Built Downloads (v1.0.0)

| Platform | Format | Description | Download |
| :--- | :--- | :--- | :--- |
| **Windows (x64)** | **Portable ZIP** | Zero-install archive. Extract and run `Umbrix.exe`. | [📦 **Download Windows Portable (.zip)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.0-windows-x64-portable.zip) |
| **Windows (x64)** | **MSI Installer** | Windows installer with desktop shortcut and file associations. | [💿 **Download Windows Installer (.msi)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.0.msi) |
| **macOS (Apple Silicon)** | **DMG Image** | Standard disk image. Drag to `/Applications`. | [🍏 **Download macOS DMG (.dmg)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.0.dmg) |
| **macOS (Apple Silicon)** | **Portable ZIP** | Zero-install standalone archive for macOS. | [📦 **Download macOS Portable (.zip)**](https://github.com/walensaz/Umbrix/releases/latest/download/Umbrix-1.0.0-macos-portable.zip) |

All release assets, tags, and checksums are on the [GitHub Releases Page](https://github.com/walensaz/Umbrix/releases/latest).

---

### Windows Setup
1. Download **`Umbrix-1.0.0-windows-x64-portable.zip`** and extract it anywhere.
2. *(Optional)* Run **`register-open-with.bat`** to add **"Open with Umbrix"** to your Windows right-click context menu (writes to `HKCU`, no admin rights required).
3. Double-click **`Umbrix.exe`** to launch.
4. Drag and drop any `.log`, `.txt`, `.jsonl`, `.out`, or `.err` file into the window.

### macOS Setup
1. Download **`Umbrix-1.0.0.dmg`**, open it, and drag **Umbrix.app** to your `/Applications` folder.
2. **First Launch (Gatekeeper):** Because this alpha is not signed with an Apple Developer certificate:
   - In Finder, right-click (or Control-click) `Umbrix.app`, select **Open**, then click **Open**.
   - Or run in Terminal: `xattr -cr /Applications/Umbrix.app`
3. Launch and open files via Finder or drag-and-drop.

---

## Interaction Guide

- **Open File:** `Ctrl+O` (`Cmd+O`) or drag and drop any file into the workspace.
- **Search:** `Ctrl+F` (`Cmd+F`) with regex, match-case, and whole-word toggles. Press `Enter` / `Shift+Enter` (or `F3` / `Shift+F3`) to step through hits.
- **Errors & Warnings:** Press `F4` / `Shift+F4` to jump between anomaly lines.
- **Go to Line / Timestamp:** `Ctrl+G` (`Cmd+G`) for line numbers or wall-clock timestamps (`HH:mm:ss`, ISO-8601).
- **Time Range Scrubber:** `Ctrl+T` toggles the dual-handle timeline scrubber and focus mode.
- **Split & Compare:** `Ctrl+\` for horizontal split or `Ctrl+Alt+\` for vertical split; toggle `⇋` for synchronized scrolling across panes.
- **Bookmarks:** `Ctrl+B` toggles a bookmark on the current line; `F2` / `Shift+F2` steps between bookmarks.
- **Scratchpad:** `Ctrl+N` opens an in-memory editor tab to take notes or paste log excerpts with source line provenance (`Ctrl+S` to save).
- **Command Palette:** `Ctrl+Shift+P` (`Cmd+Shift+P`) opens the multi-persona palette (VS Code, IntelliJ, or Vim `:` modes).
- **Settings & Themes:** `Ctrl+,` opens settings (10 built-in dark/light themes, font sizing, and AI integration).
- **Logs & Diagnostics:** Access workstation logs via **☰ Menu $\to$ Open Logs Folder** or check `~/.umbrix/logs/`.

---

## Keyboard Shortcuts

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| **`Ctrl+O`** / `Cmd+O` | **Open File** | Native file picker or drag-and-drop. |
| **`Ctrl+N`** / `Cmd+N` | **New Scratchpad** | In-app editor tab for notes and excerpts. |
| **`Ctrl+S`** / `Cmd+S` | **Save Buffer** | Save scratchpad contents to disk. |
| **`Ctrl+W`** / `Cmd+W` | **Close Tab** | Closes the active tab. |
| **`Ctrl+F`** / `Cmd+F` | **Find** | Search bar with regex/case/word toggles. |
| **`Enter`** / **`Shift+Enter`** | **Next / Prev Match** | Step through search hits (`F3` / `Shift+F3`). |
| **`F4`** / **`Shift+F4`** | **Next / Prev Anomaly**| Step through error and warning lines. |
| **`Ctrl+G`** / `Cmd+G` | **Go to Line / Time** | Seek by line index or wall-clock timestamp. |
| **`Ctrl+T`** | **Time Scrubber** | Dual-handle time range scrubber and focus mode. |
| **`Ctrl+B`** | **Toggle Bookmark** | Bookmark the current row. |
| **`F2`** / **`Shift+F2`** | **Next / Prev Bookmark**| Step between bookmarks across the document. |
| **`Ctrl+\`** / **`Ctrl+Alt+\`** | **Split View** | Horizontal or vertical split pane. |
| **`Ctrl+Shift+P`** / `Ctrl+P` | **Command Palette** | Workstation actions and shortcuts. |
| **`Ctrl+Shift+I`** | **AI Assistant** | Slide-out drawer for local (Ollama) or API models. |
| **`Ctrl++`** / **`Ctrl+-`** | **Zoom** | Adjust font size (`Ctrl+MouseWheel` supported). |
| **`F1`** | **Help** | Keyboard shortcuts cheatsheet. |
| **`Ctrl+,`** | **Settings** | Themes, typography, and AI configuration. |

---

## AI Agent Integration (MCP Server)

Umbrix runs an embedded **Model Context Protocol (MCP)** server on `http://127.0.0.1:4242/mcp` and via `stdio`, allowing external AI tools (Cursor, Claude Desktop, Copilot) to query multi-gigabyte log files:

### Cursor (`.cursor/mcp.json`)
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
        "/path/to/umbrix/umbrix-app/build/libs/umbrix-1.0.0-all.jar",
        "--headless",
        "mcp"
      ]
    }
  }
}
```

Tools exposed: `search_logs`, `get_time_slice`, `extract_causal_context`, `get_error_clusters`, `trim_and_export`.

---

## Technical Architecture & Design

### Core Mechanics
- **Zero Heap Ingestion:** Log bytes remain off-heap in OS page cache or direct memory segments (`java.lang.foreign.MemorySegment`). Only visible viewport lines (~50) are converted to UI text.
- **Two-Phase Progressive Indexing:** Chunk 0 (32MB) indexes in <10ms for instant initial render (<105ms time-to-first-render), while remaining chunks stream in background threads.
- **Two-Level Sparse Index:** Level 1 Super-Stride (16,384 lines, 64-bit offsets) and Level 2 Intra-Stride (64 lines, 32-bit deltas) resolve any line in $<1.5\ \mu\text{s}$ using ~13MB RAM for a 25GB file.
- **Hardware SIMD Acceleration:** Java Vector API (`jdk.incubator.vector`) uses AVX-512, AVX2, or ARM NEON instructions to scan delimiters and search candidate bytes at 15–25 GB/s.
- **Linear-Time Regex:** All regular expression queries run through RE2J automata in guaranteed $O(N \cdot M)$ time to eliminate ReDoS vulnerabilities.
- **Dual-Path I/O:** Static logs use continuous zero-copy memory mapping; active/rotating logs use non-locking positional reads (Win32 overlapped `ReadFile` with `FILE_SHARE_READ|WRITE|DELETE`, POSIX `pread`).

### Tech Stack
| Component | Technology | Rationale |
| :--- | :--- | :--- |
| **UI** | Compose Multiplatform (Desktop) + JetBrains Jewel | Native desktop rendering via Skia (DirectX 12 / Metal / Vulkan). |
| **Core Engine** | Scala 3.3 LTS | Functional domain types, opaque zero-boxing primitives, and off-heap data structures. |
| **Memory** | Java 25 LTS FFM API (`java.lang.foreign.*`) | Zero-copy memory mapping with deterministic lifecycles (`Arena.ofShared`). |
| **SIMD** | Java Vector API (`jdk.incubator.vector.*`) | Hardware vector registers for newline and substring scanning. |
| **Bitsets** | RoaringBitmap (`org.roaringbitmap`) | Compressed bitwise operations for bookmarks and search result filtering. |

---

## Building from Source

### Prerequisites
- **JDK:** OpenJDK 25 or Azul Zulu 25 (`--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`).
- **Build Tool:** Gradle 8.12+ (wrapper included: `./gradlew` / `.\gradlew.bat`).

### Commands

```bash
# Compile
./gradlew compileScala compileKotlin

# Run tests
./gradlew test

# Run workstation locally
./gradlew :umbrix-app:run --args="/path/to/logfile.log"

# Build standalone fat JAR
./gradlew :umbrix-app:shadowJar

# Build minimal runtime and native packages
./gradlew :umbrix-app:createMinRuntime
./gradlew :umbrix-app:packagePortableZip
```
