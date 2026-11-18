# Ecosystem & Libraries Strategy: Build vs. Adopt

---

## 1. Architectural Philosophy: Build vs. Adopt

To achieve **zero JVM heap ingestion**, **sub-second 20GB+ load times**, and **120 FPS GPU rendering**, we strictly avoid typical heavyweight JVM dependencies (e.g. Jackson-databind, Spark, Akka, or standard Java NIO channels). 

Every dependency is evaluated against three non-negotiable criteria:
1. **Zero Garbage Allocation:** Does it allocate heap objects per line or per search match?
2. **Deterministic Off-Heap Memory:** Can native memory be freed instantly without waiting for GC finalizers?
3. **Hardware Acceleration:** Can it execute in vector registers (AVX-512/NEON) or direct GPU shaders (Skia)?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          LIBRARY TAXONOMY & DIVISION                        │
├──────────────────────────────────────┬──────────────────────────────────────┤
│ CUSTOM IN-HOUSE (Core Engine)        │ THIRD-PARTY ADOPTED (Standard Libs)  │
├──────────────────────────────────────┼──────────────────────────────────────┤
│ • Two-Level Hierarchical Sparse Index│ • JetBrains Jewel (IntelliJ Theme)   │
│ • Custom 64-bit Virtual Viewport     │ • Compose Multiplatform (Desktop)    │
│ • Off-Heap LMAX Disruptor RingBuffer │ • Skiko / Skia (GPU Blitting)        │
│ • AVX-512 Newline Scanner & 2-Byte   │ • Java 25 LTS FFM API (Foreign Memory│
│   Confirmation Substring Filter      │ • Java Vector API (SIMD Incubator)   │
│ • Off-Heap SimHash Trace Clustering  │ • RoaringBitmap & fastutil (Bitwise) │
│ • Streaming ANSI SGR State Machine   │ • RE2J / Rust Regex Engine           │
│ • Client-Side High-Entropy DLP       │ • LangChain4j (LLM Orchestration)    │
│ • Win32 / POSIX Dual-Path I/O Bridge │ • Ollama / llama.cpp (Local SLMs)    │
└──────────────────────────────────────┴──────────────────────────────────────┘
```

---

## 2. Detailed Library Matrix & Evaluation

### 2.1 UI & GPU Presentation Layer

| Component | Choice | Reason for Selection | Alternative Rejected |
| :--- | :--- | :--- | :--- |
| **Desktop UI Toolkit** | **Compose Multiplatform (Desktop)** | Hardware-accelerated Skia rendering engine (DirectX 12 on Windows, Metal on macOS). Native event dispatching and declarative state. | **JavaFX** (Outdated text shaping, slow virtual lists) / **Electron** (Immense memory hog, 2GB+ RAM). |
| **Component Kit** | **JetBrains Jewel (`org.jetbrains.jewel`)** | Official IntelliJ "New UI" standalone components. Provides authentic dark carbon IDE ergonomics (dense tabs, toolbars, splitters). | **Material 3 Desktop** (Too touch-oriented, excessive padding, poor information density). |
| **Viewport Virtualization** | **Custom `Virtual64Viewport` (Tri-Mode)** | Standard Compose `LazyColumn` caps at `Int.MAX_VALUE` ($107\text{M}$ lines before layout crash) and suffers from 32-bit Float scroll jitter. Tri-mode sliding window supports $2^{63}-1$ lines. | **Compose `LazyColumn`** (Fatal 32-bit integer and float overflow bugs on >100M lines). |
| **GPU Canvas & Minimap** | **Skiko / Skia (`org.jetbrains.skia`)** | Renders 1,000-bin density minimap directly to GPU back-buffer in microseconds. Zero Compose recompositions during scrubbing. | Standard Compose `Canvas` drawing with state recomposition. |

---

### 2.2 Memory, I/O & Platform Subsystem

| Component | Choice | Reason for Selection | Alternative Rejected |
| :--- | :--- | :--- | :--- |
| **Memory Mapping & Off-Heap** | **Java 25 LTS Foreign Function & Memory (FFM) API** (`java.lang.foreign.*`) | Deterministic unmapping via `Arena.close()`, zero-copy pointer arithmetic via `MemorySegment.asSlice`, standardized non-preview SE API. | **`sun.misc.Unsafe`** (Deprecated, no bounds checking) / **`MappedByteBuffer`** (2GB limit, permanent locks). |
| **Cross-Platform Dual-Path I/O** | **Custom Win32 / POSIX FFM Downcalls** | Directly invokes `CreateFileW` (`FILE_SHARE_DELETE`) on Windows and positional `pread` / `io_uring` on Unix. Completely prevents `ERROR_SHARING_VIOLATION` and `SIGBUS` on truncation. | Standard Java `FileChannel.open` (Cannot specify non-locking flags on Windows; mmap causes SIGBUS on truncate). |
| **Primitive Collections** | **fastutil (`it.unimi.dsi.fastutil`)** | Zero-boxing primitive maps (`Long2IntOpenHashMap`) and array lists (`LongArrayList`) for expanded row tracking and sparse lookups. | Standard Java/Scala collections (`List[Long]`, `Map[Long, Any]`) which allocate boxed wrapper objects. |
| **Streaming Buffer** | **Custom `OffHeapRingBuffer` (In-House)** | LMAX Disruptor architecture built directly in off-heap memory with 64-byte cache-line padding. Ingests multi-gigabit socket streams with zero GC pauses. | `java.nio.ByteBuffer` ring buffers (Excessive synchronization, GC object wrapping). |
| **Compressed Seeking** | **libzstd & libdeflate (FFM Downcall)** | Native SIMD decompression of `.zst` seek tables and `.bgzf` blocks directly into off-heap memory segments. | Pure Java `GZIPInputStream` (CPU-bound, allocations on every read). |

---

### 2.3 Throughput, SIMD & Search Engine

| Component | Choice | Reason for Selection | Alternative Rejected |
| :--- | :--- | :--- | :--- |
| **SIMD Newline & Substring** | **Java Vector API (`jdk.incubator.vector.*`)** | Inlines directly into HotSpot C2 JIT with 0 ns boundary transition. 64 bytes/cycle on AVX-512; 32 bytes/cycle on AVX2. | Native C/Rust via JNI (Boundary call overhead ruins tight loops). |
| **Linear-Time Regex Engine** | **RE2J / Rust `regex` Crate (via Panama)** | Automaton-based linear time $O(N \cdot M)$ execution. Mathematically immune to catastrophic backtracking (ReDoS). | **`java.util.regex`** (Dangerous NFA backtracking; freezes CPU cores on complex patterns). |
| **Multi-Pattern Search** | **Vectorscan (Hyperscan Fork) via FFM** | Compiles hundreds of search terms into a single streaming DFA/NFA. Scans 25+ GB/s across multi-core systems. | Looping multiple substring passes over memory. |
| **Bitwise Filtering** | **RoaringBitmap (`org.roaringbitmap`)** | Compressed 32/64-bit integer sets for discrete fields (`level:ERROR`). Bitwise `AND`/`OR` evaluates in microseconds. | Flat boolean arrays or hash sets (High memory consumption). |

---

### 2.4 AI Forensics & Machine Learning

| Component | Choice | Reason for Selection | Alternative Rejected |
| :--- | :--- | :--- | :--- |
| **AI Orchestration Framework** | **LangChain4j 1.x (`dev.langchain4j`)** | Industry-standard, modular JVM integration for local and cloud models. Native streaming, structured function calling, and token tracking. | Custom bespoke HTTP clients for each provider. |
| **Local Offline SLM** | **Ollama HTTP API & embedded llama.cpp** | Enables zero-egress, 100% private local inference (Mistral, Llama 3, Phi-3) for air-gapped enterprise environments. | Forcing cloud-only inference. |
| **Stack Trace Clustering** | **Custom SimHash / MinHash Engine (In-House)** | Normalizes frames and calculates 64-bit hash signatures off-heap. Evaluates Hamming distance to collapse 50k traces to 3 centroids in $<250\text{ ms}$. | Python scikit-learn / external ML microservices (High latency, deployment burden). |
| **Log Template Mining** | **Drain Algorithm (In-House Scala 3)** | Fast online parse tree clustering logs into templates, reducing LLM token volume by $\ge 95\%$. | Sending raw unclustered logs to LLM. |
| **Fast Token Counting** | **jtokkit (`com.knuddels:jtokkit`)** | Fast direct BPE tokenizer in Java for accurate prompt token calculation without API roundtrips. | Approximating tokens by word count. |
| **Zero-Allocation JSON Parsing** | **jsoniter-scala (`com.github.plokhotnyuk.jsoniter-scala`)** | Compile-time macro generated codecs with 0 bytes intermediate AST allocation. Ideal for high-throughput JSON-RPC 2.0 MCP server transport. | Jackson / Circe (Significant heap allocations, reflection overhead). |

---

### 2.5 Concurrency & Reactive Pipeline

| Component | Choice | Reason for Selection | Alternative Rejected |
| :--- | :--- | :--- | :--- |
| **Core SIMD Compute** | **Pinned Platform Threads (`Thread.ofPlatform()`)** | 1 dedicated carrier thread per physical CPU core. Guarantees 100% vector unit utilization without fiber preemption. | **Java Virtual Threads** (Anti-pattern for CPU-bound SIMD; starves carrier threads). |
| **Socket & Network Ingestion** | **Java 21+ Virtual Threads (`StructuredTaskScope`)** | Lightweight M:N fibers for handling 1,000+ simultaneous remote tailing sockets (SSH, K8s, Syslog) with minimal footprint. | Heavy thread pools (Memory exhaustion with thousands of streams). |
| **Reactive UI State Flow** | **Cats Effect 3 / FS2 & Kotlin Coroutines** | Functional, backpressured stream pipelines for search progress and viewport debouncing. | Raw callbacks / Java `CompletableFuture` (Difficult cancellation coordination). |

---

### 2.6 Build Tooling, Compilers & JDK Environment Matrix

| Component | Choice & Coordinate | Version | Rationale & JVM Flags |
| :--- | :--- | :--- | :--- |
| **Target Runtime JDK** | **Azul OpenJDK 25 LTS** | 25.0.4+ | Finalized FFM API (JEP 454) & Java Vector API. Mandatory flags: `--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`. |
| **Gradle Daemon JDK** | **Azul OpenJDK 21 LTS** | 21.0.5+ | Optional daemon runtime if needed by local Gradle 8.12 scripts while compiling via Java Toolchain 25. |
| **Build Tool** | **Gradle Wrapper (Kotlin DSL)** | 8.12 (`gradlew.bat` / `./gradlew`) | Multi-project setup bridging Scala 3 Zinc compiler, Kotlin Compose Desktop, and Jewel. |
| **Core Language** | **Scala 3 (Zinc Compiler)** | 3.3.8 LTS (`scala3-library_3`) | Zero-boxing opaque types and Panama FFM interop on JDK 25. |
| **UI Language** | **Kotlin JVM & Compose Desktop** | Kotlin 2.1.10 / Compose 1.7.3 | JetBrains Compose Desktop multiplatform runtime and Skiko hardware acceleration. |
| **Component Kit** | **JetBrains Jewel** | 0.27.0 (`jewel-int-ui-standalone-242`) | Matches IntelliJ IDEA "New UI" dark styling. |
| **Fat JAR Packaging**| **GradleUp Shadow Plugin** | 8.3.6 (`com.gradleup.shadow`) | Packages standalone all-in-one executable JAR (`umbrix-1.0.1-all.jar`). |
| **Runtime Stripping** | **JDK `jlink` Tool** | Azul JDK 25 | Strips standard 380MB JRE to 85.3 MB modular runtime while retaining Vector API and FFM. |
| **Native Packaging** | **JDK `jpackage` Tool** | Azul JDK 25 | Builds native OS bundles (`app-image`, DMG, MSI) with custom dock icons and file associations. |
| **Windows Installer** | **Inno Setup / WiX Toolset** | Inno Setup 6 / WiX 5.0 | Generates zero-admin portable ZIPs and wizard installers (`installer.iss`) with context menu scripts. |
| **macOS Distribution**| **Homebrew Cask** | Cask 2.0+ (`umbrix.rb`) | Non-admin user-space installation via `brew install --cask umbrix`. |


