# Licensing & Plans

## Why Umbrix Is Free Right Now

Umbrix is currently in **early public release / beta (`v1.0.x`)** and **there is no charge to use it right now**.

Specifically:
- **Still stabilizing:** We are actively polishing cross-platform builds, UI edge cases, and real-world log workflows based on early user feedback.
- **Not code-signed yet:** Windows and macOS binaries are currently unsigned while release pipelines and certificates are still being set up.
- **No checkout or license keys yet:** There are no payment links, license servers, trial timers, or DRM.

If you download or build Umbrix today, just use it—at home or at work—and feel free to file bug reports or pull requests if you run into rough edges.

---

## Planned Post-Beta Honor-System Model

Once Umbrix stabilizes and reaches General Availability (GA), the plan is to use a straightforward **honor-system perpetual license** (no subscriptions, no forced cloud accounts, no phone-home checks):

| Tier | Intended Scope | Planned Price |
| :--- | :--- | :--- |
| **Community & Evaluation** | Personal projects, open-source work, education, emergency outage triage, and unlimited evaluation. | **$0** |
| **Personal License** | Individual developers paying with personal funds (valid across personal and work machines). | **$39 one-time** |
| **Commercial Single Seat** | Companies or employer-reimbursed seats (up to 3 machines per user). | **$69 one-time** |
| **Team Packs** | 5-seat (**$299**), 10-seat (**$549**), and 25-seat (**$1,199**) one-time packs. | **$48–$60 / seat** |

---

## Plugin SDK & Add-Ons

- **Public Plugin SDK (Apache 2.0):** `:umbrix-plugin-api` and `:umbrix-plugin-ui-api` are licensed under **Apache 2.0**, so you can build and distribute open-source or proprietary plugins under any license you like.
- **Upcoming JVM Plugin (`umbrix-plugin-jvm`):** A separate off-heap JVM diagnostic plugin (for `.hprof` heap dumps, `.jfr` recordings, and GC logs) is in development and held back from this initial release. It will be published separately later.

---

## Where Umbrix Fits (Honest Trade-Offs)

- **Best fit:** Opening and investigating multi-gigabyte desktop logs (5GB–30GB+) interactively with low heap usage (<30MB JVM heap, ~13MB sparse index on 30GB), timeline scrubbing, stack-trace folding, and local MCP/AI triage.
- **Other tools fit better when:**
  - You want instant zero-index CLI streaming over SSH (`ripgrep`, `less`, `lnav`).
  - You need full in-place text editing of arbitrary files (`EmEditor`, `Sublime Text`, `VS Code` on smaller files).
  - You want a tiny <15MB native binary (Umbrix bundles an ~85MB stripped Azul OpenJDK 25 runtime).

See [LICENSE](LICENSE) for full legal terms and [docs/BENCHMARKS.md](docs/BENCHMARKS.md) for reproducible benchmark commands.
