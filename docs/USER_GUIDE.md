# Umbrix User Guide

A practical guide to investigating multi-gigabyte logs, configuring your workstation, and running forensic workflows in **Umbrix**.

---

## 1. Opening & Viewing Logs

### Opening Files & Sessions
- **Open File Picker:** Press `Ctrl+O` (`Cmd+O` on macOS) or open the **Hamburger Menu (`☰`) → Open File...**.
- **Drag-and-Drop:** Drag any log file directly onto the Umbrix window to open it in a new tab.
- **Open Recent:** **Hamburger Menu (`☰`) → Open Recent** lists up to **8** recently opened files (`WorkstationSettings.MAX_RECENT_FILES`).
- **Session Restore:** By default (`restoreSessionOnStartup = true`), Umbrix automatically re-opens your previously open log files and active scratchpad buffer on launch.
- **Instant First Render & Sidecar Cache:** Umbrix indexes the first chunk immediately so you can scroll and search right away while the rest of the file finishes indexing in the background. Coordinate indexes are cached in `~/.umbrix/cache/indexes/` for near-instant re-opening and pruned automatically after 7 days of disuse.

### Tri-Mode Viewport (`ViewportLayoutMode`)
Switch modes via **Hamburger Menu (`☰`) → View**, **Settings (`Ctrl+,`) → View & Viewport**, or the Vim ex-command `:set mode=<compact|expand|wrap>`:

| Mode | Behavior | Best For |
| :--- | :--- | :--- |
| **Expandable Mode** *(Default)* | 20px base rows with dropdown chevrons (`▶`) to expand/collapse multi-line stack traces inline. | General outage triage and Java/Python/Go/Rust exception logs. |
| **Compact Mode** | Strict fixed 20px rows with horizontal scrolling. | Maximum 60–120 FPS throughput when scanning massive files. |
| **Full Wrap Mode** | Soft-wraps long lines within the viewport width. | Reading long single-line JSON or structured payloads without horizontal scrolling. |

### Safety Clamp, Live Tailing & Plugin Views
- **4,096-Character Safety Clamp:** Lines longer than `4096` characters (configurable to `2048`, `4096`, or `8192` in **Settings → Performance & SIMD**) are clamped before text shaping with `[... +XX KB payload]` to protect the Skia renderer from multi-megabyte minified lines.
- **Safe Live Tailing:** Static files are memory-mapped zero-copy (`MemorySegment.mapFile`). Active/live streams use non-locking positional reads (`ReadFile` with delete-sharing on Windows; `pread` on macOS/Linux) so log rotation never triggers file-lock errors or `SIGBUS` crashes.
- **Raw vs. Custom Plugin Views:** When a plugin provides a custom dock view for an open file, you can switch back to the underlying raw log lines at any time via the Command Palette (`Show Raw Records`).

---

## 2. Searching & Query Syntax

Press `Ctrl+F` (`Cmd+F`) to open the Search Bar, or `Escape` to close it and clear the active search.

### Search Bar Toggles & Stepping
| Control / Shortcut | Action |
| :--- | :--- |
| `Alt+C` (`Aa`) | Toggle **Match Case** |
| `Alt+W` (`\|W\|`) | Toggle **Match Whole Word** |
| `Alt+R` (`.*`) | Toggle **Regular Expression** (evaluated via Google RE2J in guaranteed linear $O(N \cdot M)$ time) |
| `Enter` / `F3` | Jump to **Next Match** |
| `Shift+Enter` / `Shift+F3` | Jump to **Previous Match** |
| `F4` / `Shift+F4` | Jump to **Next / Previous Forensic Anomaly** (`ERROR` / `WARN` lines) |
| **Filter Matching Lines** | Isolate the viewport to show only lines matching the current query |
| `Ctrl+G` (`Cmd+G`) | **Go to Line / Timestamp** (`HH:mm:ss` or ISO-8601) |

### Structured Query AST (`QueryParser`)
Umbrix compiles structured queries into an AST with ReDoS-immune linear-time regex evaluation:

| Syntax | Meaning & Example |
| :--- | :--- |
| `term1 term2` / `AND` / `&&` | Logical AND (space-separated terms are implicitly ANDed): `timeout AND database` |
| `OR` / `\|\|` | Logical OR: `timeout OR "connection refused"` |
| `NOT` / `!` | Logical negation: `ERROR NOT heartbeat` |
| `( ... )` | Grouping: `(timeout OR deadlock) AND level:error` |
| `"..."` / `'...'` | Quoted literal phrase (supports `\` escaping): `"NullPointerException: null"` |
| `level:<lvl>` | Filter by severity: `FATAL`/`CRITICAL`, `ERROR`/`ERR`, `WARN`/`WARNING`, `INFO`, `DEBUG`, `TRACE` |
| `since:<ts>` / `until:<ts>` | Time bounds using ISO-8601 (`2026-11-18T14:30:00Z`), local datetime, or time-of-day (`14:30:00.000`, `24:...` rollover) |
| `case:sensitive` / `case:true` | Force case-sensitive matching on the following term: `case:sensitive UserId` |
| `/pattern/` / `regex:<pat>` | Inline RE2J regex term: `/status=(5\d\d)/` |
| `fn:<name>` / `fn:<name>(a, b)` | Invoke a plugin-registered custom query function |

---

## 3. Forensic Triage Workflows

### Time-Range Scrubbing & Latency Gaps (`Ctrl+T`)
Press `Ctrl+T` (or **Hamburger Menu (`☰`) → Temporal Range...**) to open the **Time Range Scrubber**:
- Drag the dual handles to isolate any wall-clock time window in $O(\log N)$ time.
- **Latency-Gap Highlighting:** Amber and red ticks on the scrubber track and viewport gutter highlight inter-record pauses exceeding your configured thresholds (defaults: **1,000 ms** amber warning, **5,000 ms** red severe outage).
- Click **Trim View (Focus)** to restrict the viewport to the selected time range, or clear focus to restore the full file.

### Bookmarks (`Ctrl+B`, `F2`, `Ctrl+Alt+B`)
- **Toggle Bookmark:** Press `Ctrl+B` (or click the line gutter) to bookmark/unbookmark the current line.
- **Step Through Bookmarks:** `F2` (Next) / `Shift+F2` (Previous).
- **Bookmark Manager:** Open from **Hamburger Menu (`☰`) → Manage Bookmarks...** to search bookmarks by snippet, note, or line number, filter by tag, edit analyst notes, or send entries to the Incident Timeline.
- **Bookmark Filter:** Press `Ctrl+Alt+B` to toggle filtering the viewport down to bookmarked lines only.

### Incident Timeline Drawer (`Ctrl+Shift+T`)
Press `Ctrl+Shift+T` to open the dockable **Incident Timeline** tab:
- Collect key log lines from multiple open files or bookmarks into a single chronological sequence with automatic $\Delta t$ deltas between events.
- **Export Reports:** Export the timeline as a **Markdown post-mortem report**, a **consolidated `.log` file** with provenance headers, or a **structured JSON archive** (`IncidentReportExporter`).

### Split Views & Multi-Pane Docking
- **Split Right (Horizontal):** `Ctrl+\`
- **Split Down (Vertical):** `Ctrl+Alt+\`
- **Focus Next Pane:** `F6`
- **Synchronized Scrolling:** Click the `⇋` sync toggle on any split divider (`DockSplitDivider`) to scroll two panes together by relative line delta.
- **Drag-and-Drop Docking:** Drag tabs between panes or onto empty panes to reorganize your workspace, or detach tabs into separate windows.

### Scratchpads & Sub-File Export
- **New Scratchpad (`Ctrl+N`):** Open an editable scratchpad tab for investigation notes, pasted stack traces, or hypotheses. Scratchpads are automatically persisted under `~/.umbrix/scratchpads/`.
- **Save Scratchpad (`Ctrl+S`):** Save the active scratchpad to disk (prompts for a destination path on first save).
- **Manage Scratchpads (`Ctrl+Alt+S`):** View saved/unsaved scratchpads, rename, or reopen them.
- **Export Sub-File (`Ctrl+Shift+S`):** Slice a line range (`Start Line` to `End Line`) directly from the off-heap memory-mapped buffer into **Raw Monospace Log (`.log`)**, **Gzip Compressed (`.log.gz`)**, or **JSON Lines (`.jsonl`)**.

### Command Palette & Vim Ex-Commands
Open the Command Palette with `Ctrl+Shift+P` (prefixed with `>`), `Ctrl+P`, or **Double-Shift** (within 400 ms, when enabled):
- **Line Jump:** Type `#<line>` or `:<line>` (e.g., `#1500` or `:1500`) to jump directly to a 1-indexed line.
- **Category Filter Pills:** In `Unified Omni` and `IntelliJ` styles, press `Tab` to cycle `[All]`, `[Files]`, `[Actions]`, `[Navigation]`, `[View]`, `[Settings]`, and `[Themes]`.
- **Vim Ex-Mode (`:`):** Pressing `:` while viewing logs (when enabled) opens the palette in Ex-command mode:
  - `:1234` — Jump to line 1234
  - `:w` / `:write` — Open Export Sub-File dialog
  - `:q` / `:quit` / `:q!` — Close current tab (`:qa` / `:qall` closes all tabs)
  - `:tabn` / `:gt` and `:tabp` / `:tabprev` — Next / previous tab (`:tabc` closes tab)
  - `:theme <name>` (or `:colorscheme` / `:colo`) — Switch theme
  - `:set <option>` — Toggle `minimap` / `nominimap` / `minimap!`, `numbers` (`nu`) / `nonumbers` (`nonu`), `whitespace` / `nowhitespace`, or `mode=compact|expand|wrap`
  - `:find <query>` / `:search <query>`, `:settings`, `:help`

---

## 4. Settings Reference (`Ctrl+,`)

Open **Settings** with `Ctrl+,` (`Cmd+,`) or **Hamburger Menu (`☰`) → Settings...**. Settings are persisted to `~/.umbrix/settings.json`.

### General & Workspace (`GeneralSettingsTab`)
| Setting | Default | Description |
| :--- | :--- | :--- |
| **Restore Previous Session on Startup** | `true` | Re-opens previously open log files and active scratchpad buffer on launch. |
| **Virtual Scratchpads** | `~/.umbrix/scratchpads/` | Informational path where investigation drafts are auto-persisted. |
| **Persistent Sidecar Index Cache** | `~/.umbrix/cache/indexes/` | Cached off-heap coordinate indexes (auto-pruned after 7 days; includes **Clear Index Caches Now** button). |
| **Recent Files History** | Max `8` entries | Button to **Clear Recent Files History**. |

### Appearance & Minimap (`AppearanceSettingsTab`)
| Setting | Default | Description |
| :--- | :--- | :--- |
| **Color Theme Preset** | `Dark Carbon` | 10 built-in presets: **Dark:** `Dark Carbon`, `Midnight Blue`, `Sublime Monokai`, `Dracula`, `Nord`, `Solarized Dark`, `High Contrast`; **Light:** `Light Ivory`, `Solarized Light`, `GitHub Light`. |
| **Enable GPU Seismograph Minimap** | `true` | Toggles the density radar minimap (`Ctrl+M` shortcut also toggles). |
| **Minimap Strip Width** | `48px` | Choose `32px`, `48px`, `64px`, or `80px`. |
| **Enable Search Match Highlighting** | `true` | Highlights search matches as inline needle pills. |

### Fonts & Typography (`FontsSettingsTab`)
Includes a live preview and a **Reset to Defaults** button.

| Setting | Default | Range / Options |
| :--- | :--- | :--- |
| **Editor Font Family** | `JetBrains Mono` | System monospace fonts |
| **Editor Font Size** | `13.0 pt` | Configurable pt range (`Ctrl++` / `Ctrl+-` / `Ctrl+0` also zoom) |
| **Line Height Multiplier** | `1.40×` | Slider |
| **Editor Font Weight** | `Regular (400)` | `Light (300)`, `Regular (400)`, `Medium (500)`, `Bold (700)` |
| **Enable OpenType Ligatures** | `true` | Renders `!=`, `->`, `==`, `>=` ligatures |
| **UI Font Family & Size** | `Inter`, `13.0 pt` | System proportional fonts |

### View & Viewport (`ViewSettingsTab`)
| Setting | Default | Description |
| :--- | :--- | :--- |
| **Viewport Layout Mode** | `Expandable` | `Expandable`, `Compact Fixed Mode`, or `Full Wrap Mode`. |
| **Show 64-bit Line Numbers** | `true` | Renders 1-indexed line numbers in the gutter. |
| **Show Whitespace Glyphs** | `false` | Renders subtle space (`·`) and tab (`→`) indicators. |
| **Status Bar Telemetry** | Position, Stats, Mode = `true`; Indexing, Memory = `false` | Individually toggle **Line & Cursor Position**, **Document Total Lines & File Size**, **Viewport Layout Mode**, **SIMD Vector Width & Indexing Telemetry**, and **JVM Heap Memory & GC Invariant Telemetry**. |
| **Restore Previous Session on Startup** | `true` | Also accessible here for convenience. |
| **Command Palette Style** | `Unified Omni (Default)` | `Unified Omni`, `VS Code Style`, `IntelliJ Search Everywhere`, or `Vim / CLI Ex-Commands`. |
| **Enable Double-Shift Trigger** | `true` | Pressing `Shift` twice within 400 ms opens the Command Palette. |
| **Enable Vim `:` Trigger in Viewport** | `true` | Pressing `:` while viewing logs opens Ex-command mode. |
| **Show Keyboard Shortcuts in Command Results** | `true` | Displays keybinding hints on Command Palette rows. |

### Log Languages & Profiles (`LanguagesSettingsTab`)
| Setting | Default | Description |
| :--- | :--- | :--- |
| **Active Language Profile** | `Auto-Detect Format` | `Auto-Detect`, `Java / JVM`, `Python`, `Go`, `Rust`, or `Syslog & Generic Plaintext`. |
| **Custom Error Keywords** | `""` (empty) | Comma-separated keywords treated as error severity. |

### Performance & SIMD (`PerformanceSettingsTab`)
| Setting | Default | Options |
| :--- | :--- | :--- |
| **Enable Java Vector API SIMD** | `true` | Hardware AVX-512 / AVX2 / NEON acceleration for scanning. |
| **Max Characters Per Line (Safety Clamp)** | `4096 chars` | `2048`, `4096`, or `8192` chars before Skia text shaping. |
| **Highlight Latency Gaps in Viewport Gutter** | `true` | Renders amber/red dots in the gutter for inter-record pauses. |
| **Warning Gap Threshold (Amber)** | `1000 ms` | `500ms`, `1000ms`, `2000ms`, `5000ms`. |
| **Severe Gap Threshold (Red Outage)** | `5000 ms` | `3000ms`, `5000ms`, `10000ms`. |

### AI & MCP Connections (`AiMcpSettingsTab`)
| Setting | Default | Description |
| :--- | :--- | :--- |
| **Enable Embedded MCP HTTP Server** | `true` | Exposes `http://127.0.0.1:<port>/mcp` for external agents (Cursor, Claude Desktop, Antigravity, Copilot). |
| **Loopback Port** | `4242` | Valid range `1024–65535`. |
| **AI Engine Provider** | `Ollama (Local)` | `Ollama (Local)`, `OpenAI Compatible`, or `Custom MCP` for the in-app AI Forensic Assistant (`Ctrl+Shift+I`). |
| **Endpoint URL** | `http://localhost:11434` | Auto-fills `https://api.openai.com` when switching to OpenAI Compatible. |
| **Model Name** | `llama3.2` | Auto-fills `gpt-4o-mini` when switching to OpenAI Compatible. |
| **API Key** | `""` (empty) | Optional for local Ollama; required for cloud OpenAI-compatible endpoints. |

### Plugins & Analyzers (`PluginsSettingsTab`)
- **Install Plugin from Disk (`.jar`)...:** Copies a plugin JAR into `~/.umbrix/plugins/` and loads it.
- **Open Plugins Folder / Reload Plugins:** Open `~/.umbrix/plugins/` in your OS file manager or hot-reload plugins.
- **Enable / Disable & Configure:** Toggle individual plugins on or off (persisted via `disabledPluginIds`; some plugins may show `Restart Required`) and expand plugin-specific settings panels.

---

## 5. Diagnostics & Troubleshooting

- **Generate Diagnostic Report:** Run **Hamburger Menu (`☰`) → Diagnostic Report** (or via Command Palette) to capture system metrics, SIMD vector status, and active session context. Reports are written in both text and JSON formats to `~/.umbrix/reports/`.
- **Application Logs:** Open **Hamburger Menu (`☰`) → Open Logs Folder** to inspect Umbrix's own SLF4J logs in `~/.umbrix/logs/`.
- **Unsigned Binary Launch Notes (Windows & macOS):** Early `v1.x` releases are not code-signed yet:
  - **Windows (SmartScreen):** Click **More info → Run anyway** if SmartScreen prompts, or run `Unblock-File .\Umbrix-1.0.1-windows-x64-portable.zip` in PowerShell before extracting.
  - **macOS (Gatekeeper):** Run `xattr -cr /Applications/Umbrix.app` after dragging to `/Applications`, or launch via `/Applications/Umbrix.app/Contents/MacOS/Umbrix /path/to/logfile.log`, or allow in **System Settings → Privacy & Security → Open Anyway**.
