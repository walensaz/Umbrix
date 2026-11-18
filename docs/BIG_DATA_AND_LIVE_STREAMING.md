# Big Data & Live Streaming Architecture

> [!NOTE]
> **Implementation Scope & Roadmap Distinction:**
> - **Active in v1.0 Core Engine:** High-performance local file ingestion with zero-copy static `mmap` (`MemorySegment.mapFile` / `Arena.ofShared()`), non-locking active file live tailing via Win32 Overlapped `ReadFile` (`FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`) and POSIX positional `pread` (preventing lock collisions and `SIGBUS` crashes), paired with SIMD AVX-512/AVX2 progressive two-tier indexing (<300ms physical line indexing for <2s initial render).
> - **Target Roadmap Extensions:** Remote object storage HTTP range streaming (S3/GCS/Azure Blob), container/cluster tailing (Kubernetes WebSockets / Docker pipes), remote SSH tailing, and direct network socket ingest (Syslog UDP/TCP, Kafka, OpenTelemetry OTLP).

---

## 1. Multi-Source Ingestion Architecture

In modern cloud-native environments, log analysis is not confined to static files on local disks. The workstation acts as a unified forensic hub ingesting multi-gigabit streams across disparate enterprise sources:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      MULTI-SOURCE INGESTION TOPOLOGY                        │
├─────────────────┬──────────────────┬──────────────────┬─────────────────────┤
│ 1. Local Files  │ 2. Remote SSH    │ 3. Cloud / K8s   │ 4. Direct Network   │
├─────────────────┼──────────────────┼──────────────────┼─────────────────────┤
│ • Win32 IOCP    │ • Async SFTP     │ • K8s WebSocket  │ • Syslog UDP/TCP    │
│ • Linux io_uring│ • SSH Tail Pipe  │ • Docker Pipe    │ • Kafka / OTel OTLP │
│ • S3 Range HTTP │ • Jump Host Auth │ • AWS CloudWatch │ • TCP Raw Socket    │
└─────────────────┴──────────────────┴──────────────────┴─────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UNIFIED OFF-HEAP SLAB MANAGER                           │
│  - Pre-allocated 4MB native slabs managed via Arena.ofShared()              │
│  - Backpressure applied via TCP / HTTP/2 window flow control                │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                  OFF-HEAP CIRCULAR RING BUFFER (Disruptor)                  │
│  - Lock-free atomic sequence cursors with 64-byte cache-line padding        │
│  - SIMD worker indexes newlines on the fly without intermediate Heap memory │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Ingestion Protocols & Connectors

### 2.1 Cloud Object Storage Direct Range Requests (S3 / GCS / Azure)
* **The Problem:** Downloading a 100 GB crash dump from an S3 bucket takes minutes and exhausts local SSD storage.
* **The Solution:** Umbrix implements a **Virtual HTTP Range-Seek Engine**:
  1. Issues an initial `HEAD` request to obtain the exact file size and `Accept-Ranges: bytes`.
  2. Pulls the first 1 MB and last 64 KB (for compression seek tables) into memory.
  3. When the user scrolls or searches, the engine dispatches parallel asynchronous HTTP `Range: bytes=start-end` requests into off-heap slabs.
  4. The user views remote 100 GB logs in seconds with zero local file download.

### 2.2 Kubernetes Multi-Pod Stream Interleaving
* Connects to the Kubernetes API server via chunked HTTP/2 or WebSockets (`/api/v1/namespaces/{ns}/pods/{pod}/log?follow=true`).
* Ingests multiple pod replicas simultaneously (e.g. `checkout-deployment-*`).
* Annotates each line off-heap with a 16-bit Pod ID and timestamp.
* Merges streams dynamically using an **Off-Heap $K$-Way Min-Heap** sorted by timestamp.

### 2.3 High-Throughput Syslog UDP/TCP Receiver
* Embedded native socket listener binding to port 514 (UDP) and 1514 (TCP).
* Uses Java 21+ Virtual Threads to receive datagram packets into off-heap slabs at over **500,000 events/second**.
* Zero GC impact on the workstation UI during massive log floods.

---

## 3. Off-Heap Lock-Free Circular Ring Buffer

To prevent GC stalls during 100,000 lines/second ingestion bursts, the workstation maintains an off-heap ring buffer following the LMAX Disruptor pattern:

```
Buffer Layout: 64-byte Cache-Line Padded Cursor
┌──────────────┬──────────────────┬──────────────┬────────────────────────────┐
│ Padding (64B)│ WriteCursor (8B) │ Padding (64B)│ ReadCursor (8B)            │
└──────────────┴──────────────────┴──────────────┴────────────────────────────┘
        │                                                ▲
        │ atomic setRelease                              │ atomic getAcquire
        ▼                                                │
┌─────────────────────────────────────────────────────────────────────────────┐
│ Off-Heap Memory Segment (Capacity: 2^N bytes, e.g. 1GB contiguous)          │
│ [ Slot 0 | Slot 1 | Slot 2 | ... | Slot 2^N - 1 ]                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Wrap-Around Boundary Math
```scala
val bufferOffset = currentWrite & mask

if (bufferOffset + length <= capacityBytes) {
  // Contiguous single copy
  MemorySegment.copy(source, 0L, buffer, bufferOffset, length)
} else {
  // Split wrap-around copy across boundary
  val firstChunk = capacityBytes - bufferOffset
  val secondChunk = length - firstChunk
  MemorySegment.copy(source, 0L, buffer, bufferOffset, firstChunk)
  MemorySegment.copy(source, firstChunk, buffer, 0L, secondChunk)
}
```

---

## 4. Dynamic Extent Paging Table (Handling File Growth)

* **Anti-Pattern:** Re-mapping the entire file or calling `realloc()` when the file grows, which invalidates pointers and causes UI stutters.
* **Umbrix Solution: Extent Paging Table:**
  1. Mapped memory is modeled as an array of independent **64 MB contiguous extents**.
  2. When the OS signals file growth ($S_{\text{old}} \to S_{\text{new}}$):
     - Maps only the delta slice $[S_{\text{old}}, S_{\text{new}})$ into the next available extent slot.
     - Spawns an AVX-512 worker to scan strictly the newly appended bytes.
     - Appends indices directly into Level 1 and Level 2 sparse index tables.
     - Atomically updates total line count:
       ```scala
       TOTAL_LINES_HANDLE.setRelease(this, newTotalLines)
       ```
     - Virtual scrollbar adjusts thumb ratio in 60 FPS loop with zero lag.

---

## 5. Live Viewing & Kinetic Deceleration UX

Real-time tailing at $50,000+\text{ lines/sec}$ requires careful interaction physics:

```
                    ┌─────────────────────────┐
                    │      PINNED_TAIL        │
                    │ (Viewport locked to     │
                    │  newest incoming lines) │
                    └────────────┬────────────┘
                                 │ User touches scroll wheel / trackpad
                                 ▼
                    ┌─────────────────────────┐
                    │  KINETIC_DECELERATING   │
                    │ (Inertia decay curve    │
                    │  v(t) = v0 * e^(-λt))   │
                    └────────────┬────────────┘
                                 │ Velocity drops below threshold
                                 ▼
                    ┌─────────────────────────┐
       User clicks  │      DETACHED_FREE      │
      "Resume Tail" │ (Display floating pill: │
      ─────────────►│  "14,290 new logs ↓")   │
                    └─────────────────────────┘
```

### Jewel Floating Pill Component
When the user detaches to inspect earlier events, incoming lines spool off-heap silently. A floating pill informs the user of incoming activity:
- Displays unread count and ingestion speed (e.g. `42.1k lines/s`).
- Visual sparkline indicates activity spikes.
- Single click smoothly returns to `PINNED_TAIL` mode.

---

## 6. Time-Synchronized Split-Screen Forensics

When debugging distributed failures, engineers frequently compare **Staging vs. Production** or **Service A vs. Service B**:

```
PRIMARY PANE: production.log                 SPLIT PANE: staging.log
[Timestamp Lock: Synchronized Δ = 0ms]       [Timestamp Lock: Synchronized Δ = 0ms]
┌──────────────────────────────────────────┐ ┌──────────────────────────────────────────┐
│ 14:02:11.002 FATAL Hikari pool exhausted │ │ 14:02:11.006 WARN  Slow DB query: 1800ms │
│ 14:02:12.100 FATAL Auth service timeout  │ │ 14:02:12.104 INFO  Retrying token auth... │
└──────────────────────────────────────────┘ └──────────────────────────────────────────┘
```

- **Wall-Clock Lock:** Scrolling either viewport triggers an asynchronous binary search in the counterpart log's timestamp index table.
- Both viewports stay locked to the exact same millisecond, highlighting causal anomalies instantly across disparate environments.
