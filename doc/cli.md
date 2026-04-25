# CLI Reference

psi now has a launcher-owned canonical CLI surface.

## Canonical usage

```bash
psi [launcher-flags] [psi-runtime-flags]
```

The launcher constructs startup basis data before `psi.main` starts.
That means user/project extension manifests participate in classpath and
extension availability before the JVM launches.

Launcher realization policy controls how psi and psi-owned extensions are
resolved at startup. Three policies exist:

| Policy | When used | How psi is resolved |
|--------|-----------|---------------------|
| `jar` | Default for released versions | Single `io.github.hugoduncan/psi` Maven coordinate from Clojars — fast, cached |
| `installed` | Default for unreleased/git installs | Local paths relative to the launcher root (git checkout) |
| `development` | Contributor/repo-local flows | Local paths relative to the launcher root (source tree) |

Auto-detection: when `psi/version.edn` contains a release semver (not
`"unreleased"`), the launcher defaults to `:jar` policy. An unreleased build
defaults to `:installed`.

Override with `PSI_LAUNCHER_POLICY`:

```bash
PSI_LAUNCHER_POLICY=jar psi          # force Maven resolution (released builds)
PSI_LAUNCHER_POLICY=installed psi    # force git/local paths (installed builds)
PSI_LAUNCHER_POLICY=development psi  # force source-tree paths (contributors)
```

## Requirements

- **Java 22+** — required at runtime. The TUI (`--tui`) depends on
  `jline-terminal-ffm` which uses the Java Foreign Function & Memory API
  (finalized in Java 21, class-file version 66 = Java 22 minimum).
  Java 25 is the version used in CI and recommended for production use.
- **Babashka** — required for the launcher (`bb`).

## Install

Canonical install path:

```bash
bbin install io.github.hugoduncan/psi --as psi
```

Repo-local / development install alternative:

```bash
bbin install . --as psi
```

Repo-local development path without installation:

```bash
bb bb/psi.clj -- --tui
```

Legacy alias-based startup such as `clojure -M:psi ...` is now non-canonical.
It may still be useful in development or transition periods, but it is no
longer the primary documented startup contract.

## Launcher-only flags

These are consumed by the launcher and are **not** forwarded to `psi.main`.

- `--cwd <path>`
  - Override the working directory used for project manifest lookup and the launched psi process.
- `--launcher-debug`
  - Print a pre-launch summary of cwd, manifest presence, merged manifest libs,
    psi-owned defaults, inferred `:psi/init` usage, and basis summary.

## Forwarded psi runtime flags

All other flags are forwarded unchanged to `psi.main`.

## Mode and session flags

- `--tui`
  - Start interactive terminal UI mode.
- `--rpc-edn`
  - Start EDN-lines RPC mode on stdin/stdout.
- `--rpc-trace-file <path>`
  - When used with `--rpc-edn`, append inbound/outbound RPC frames to `<path>` as newline-delimited EDN.
  - Captures both directions (`:dir :in` and `:dir :out`) with timestamp and raw wire line.
  - Runtime tracing can also be toggled dynamically via EQL mutation `psi.extension/set-rpc-trace`.
- `--nrepl [port]`
  - Start nREPL alongside session.
  - If `port` is omitted, a random port is chosen.

## Model and logging

- `--model <key>`
  - Select model key (same keys as `psi.ai.models/all-models`).
  - Falls back to `PSI_MODEL` when not provided.
- `--log-level <LEVEL>`
  - Set Timbre minimum level.
  - Valid: `TRACE | DEBUG | INFO | WARN | ERROR | FATAL | REPORT` (case-insensitive).

## Memory runtime flags

- `--memory-store <in-memory>`
- `--memory-store-fallback <on|off|true|false>`
- `--memory-history-limit <n>`
- `--memory-retention-snapshots <n>`
- `--memory-retention-deltas <n>`

`<n>` must be a positive integer.

## Session/runtime tuning

- `--llm-idle-timeout-ms <n>`
  - LLM idle timeout in milliseconds (`<n>` positive integer).
  - Default: `600000` (10 minutes).

## Environment variables

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `PSI_MODEL`
- `PSI_DEVELOPER_PROMPT`

Memory-related:
- `PSI_MEMORY_STORE`
- `PSI_MEMORY_STORE_AUTO_FALLBACK`
- `PSI_MEMORY_HISTORY_COMMIT_LIMIT`
- `PSI_MEMORY_RETENTION_SNAPSHOTS`
- `PSI_MEMORY_RETENTION_DELTAS`

Session/runtime tuning:
- `PSI_LLM_IDLE_TIMEOUT_MS`

## Precedence notes

- `--model` flag overrides `PSI_MODEL`.
- `--llm-idle-timeout-ms` overrides `PSI_LLM_IDLE_TIMEOUT_MS`.
- For optional runtime settings generally: CLI flag takes precedence over env var.

## Examples

```bash
# Console
psi

# TUI
psi --tui

# RPC mode
psi --rpc-edn

# RPC mode with transport trace file
psi --rpc-edn --rpc-trace-file /tmp/psi-rpc.ndedn

# nREPL on random port
psi --nrepl

# nREPL on fixed port
psi --nrepl 7888

# TUI + nREPL
psi --tui --nrepl

# Pick model key
psi --model sonnet-4.6

# Memory retention
psi --memory-store in-memory \
  --memory-retention-snapshots 500 \
  --memory-retention-deltas 2000

# Disable auto fallback to in-memory
psi --memory-store-fallback off

# Launcher debug
psi --launcher-debug --tui

# Override cwd for manifest lookup and launched process
psi --cwd /path/to/project --rpc-edn
```

## Migration note

Old alias-based examples map directly:

```bash
clojure -M:psi            -> psi
clojure -M:psi --tui      -> psi --tui
clojure -M:psi --rpc-edn  -> psi --rpc-edn
```
