# Contributing to Umbrix

Thanks for checking out Umbrix! Bug reports, reproducers, documentation improvements, and pull requests are all welcome.

---

## Reporting Bugs & Feature Requests

- **Bug Reports:** Please open a [GitHub Issue](https://github.com/walensaz/Umbrix/issues/new/choose) and include:
  - OS & architecture (Windows x64, macOS Apple Silicon, Linux x64)
  - Umbrix version (e.g. `v1.0.1` or commit hash)
  - File size / format (e.g. `12 GB .log`, live tailing, `.jsonl`, etc.)
  - Relevant logs or crash reports from `~/.umbrix/reports/` (redact any sensitive log snippets first)
- **Feature Requests & Ideas:** Open an issue describing the workflow or outage-triage problem you're trying to solve.

---

## Submitting Pull Requests

1. Fork the repository and create a branch from `develop` (or `master`).
2. Keep changes focused and verify everything compiles and passes tests:
   ```bash
   ./gradlew compileScala compileKotlin
   ./gradlew test --console=plain
   ```
3. If your change affects architecture, CLI commands, or user-facing behavior, update the relevant doc in `docs/` or `README.md`.
4. Open a pull request against `develop` describing **what** changed, **why**, and how you tested it.

### Core Engineering Invariants (For Code PRs)

Umbrix relies on a few strict constraints to keep multi-gigabyte files fast and memory-bounded:
- **Zero heap ingestion:** Keep raw log bytes off-heap (`java.lang.foreign.MemorySegment`). Only materialize `String`s for visible viewport rows (~50 lines).
- **Deterministic lifecycles:** Bind native memory to explicit `Arena.ofShared()` / `Arena.ofConfined()` scopes—never rely on GC cleaners.
- **No `java.util.regex` on log search paths:** Use `com.google.re2j` to guarantee linear $O(N \cdot M)$ time (ReDoS immunity).
- **Zero boxing on hot paths:** Use Scala 3 `opaque type`, primitive arrays, `fastutil`, and `Roaring64NavigableMap`.
- **Scala 3 style:** Classical `{ ... }` braces everywhere (no significant-indentation syntax), expression-oriented style without `return`.
- **4,096-char viewport clamp:** Never send unbounded multi-MB lines directly to Skia/HarfBuzz text shaping.

---

## Contributor License Note

- Contributions to the **Plugin SDK** (`umbrix-plugin-api` and `umbrix-plugin-ui-api`) are licensed under **Apache 2.0**.
- By submitting a pull request to the core repository, you agree that your contribution may be included and distributed under the project's [LICENSE](LICENSE) (and Apache 2.0 for Plugin SDK modules).
