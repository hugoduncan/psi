# ψ Psi — A Clojure AI Agent

A self-evolving AI coding agent built in Clojure. Statechart-driven,
EQL-queryable, extensible. Inspired by
[pi-mono](https://github.com/badlogic/pi-mono).

---

## Quick Start

```bash
# With Anthropic (env var)
ANTHROPIC_API_KEY=sk-... clojure -M:run

# Choose a model
clojure -M:run --model claude-3-5-sonnet

# Terminal UI
clojure -M:run --tui

# EDN-lines RPC mode (protocol frames on stdout)
clojure -M:run --rpc-edn

# OpenAI
clojure -M:run --model gpt-4o --tui

# With live nREPL introspection
clojure -M:run --tui --nrepl 8888
```

### OAuth Login (no env var needed)

```
/login    # browser-based OAuth flow
/logout
```

### In-session commands

`/status` `/history` `/new` `/help` `/quit` `/skills` `/prompts` `/feed-forward [reason]`
`/skill:<name>` plus any extension commands

`/feed-forward` triggers a manual recursion cycle from the runtime command surface.
It is bound to the internal spec prompt name `feed-forward-manual-trigger`.

---

## Architecture

```
Engine (statecharts) → substrate
Query  (EQL/Pathom3) → capability surface
AI     (providers)   → streaming LLM layer
Agent  (statechart)  → per-turn lifecycle
TUI    (charm.clj)   → Elm Architecture terminal UI
```

### Components

| Component       | Role                                              |
|-----------------|---------------------------------------------------|
| `engine`        | Statechart infrastructure, system state           |
| `query`         | Pathom3 EQL registry, `query-in`                  |
| `ai`            | Provider streaming, model registry (Anthropic, OpenAI) |
| `agent-core`    | LLM agent lifecycle statechart + EQL resolvers    |
| `agent-session` | Full coding-agent session: tools, extensions, OAuth, TUI |
| `history`       | Git log resolvers                                 |
| `introspection` | Engine queries itself — self-describing graph     |
| `tui`           | JLine3 + charm.clj terminal UI, extension UI points |

### Built-in Tools

`read` `bash` `edit` `write` `eql_query`

### EQL Introspection Tips

- Query only attributes that exist in the graph; unknown attrs can cause the whole `eql_query` request to fail.
- For the active system prompt, use:
  - `[:psi.agent-session/system-prompt]`
- For prompt sizing (chars + estimated tokens), use:
  - `[{:psi.agent-session/request-shape [:psi.request-shape/system-prompt-chars :psi.request-shape/estimated-tokens :psi.request-shape/total-chars]}]`
- Avoid non-existent attrs like `:psi.agent-session/prompt`, `:psi.agent-session/instructions`, `:psi.agent-session/messages` unless resolvers are added for them.

---

## Extension System

Extensions are Clojure namespaces loaded at runtime. Each extension
receives an API map with:

- Tool registration (`register-tool!`)
- EQL query access (`query`)
- UI hooks (`dialogs`, `widgets`, `status`, `notifications`, `renderers`)

---

## Roadmap

- ✓ Engine + Query substrate
- ✓ AI provider layer (Anthropic, OpenAI)
- ✓ Agent core loop
- ✓ Coding-agent session
- ✓ TUI (charm.clj / JLine3)
- ✓ Extension system + Extension UI
- ✓ OAuth (PKCE, Anthropic, OpenAI)
- ✓ Git history resolvers
- ✓ Session persistence
- ◇ HTTP API (openapi + martian)

---

## Emacs MVP frontend (rpc-edn)

The repository includes an Emacs MVP frontend at `clients/emacs/` that runs
psi in a dedicated process buffer over rpc-edn.

### Start

1. Ensure this repository is available locally.
2. In Emacs, add `clients/emacs` to `load-path` and load `psi.el`.
3. Run `M-x psi-emacs-start`.

This opens `*psi*` (configurable via `psi-emacs-buffer-name`) and starts one
owned subprocess per dedicated buffer using:

- `clojure -M:run --rpc-edn`

### Compose and keybindings

In `psi-emacs-mode`:

- `RET` inserts newline (never sends)
- `C-c RET` send prompt
  - while streaming: steer (`prompt_while_streaming` with `behavior=steer`)
- `C-u C-c RET` queue override while streaming
- `C-c C-q` queue while streaming; fallback to normal send when idle
- `C-c C-k` abort active streaming (`abort`)
- `C-c C-r` reconnect (prompts before clearing edited buffer)

Compose source rules:

- Active region sends region text (for both `C-c RET` and `C-c C-q`).
- Without a region, psi sends the tail draft block from the draft anchor marker to end-of-buffer.
- Normal editing keeps the anchor at the start of the current draft tail, so transcript text above the anchor is not resent unless you explicitly select it as a region.
- Reconnect clear (`C-c C-r` after confirmation) resets the buffer and repositions the draft anchor at the new buffer end; after reconnect, sends come only from text typed after that reset point.

### MVP rendering and status

- Assistant streaming uses a single in-progress block updated by
  `assistant/delta` and finalized by `assistant/message`.
- Tool lifecycle rows render inline for
  `tool/start|delta|executing|update|result`.
- ANSI tool output is rendered with faces (no raw escape noise).
- Header line shows minimal transport + process state.
- RPC errors are surfaced in minibuffer only.

### Reconnect semantics (MVP)

- Reconnect is manual only (`C-c C-r`), no auto-restart.
- Confirmed reconnect clears buffer and starts a fresh session.
- No automatic resume/rehydrate occurs in MVP.

### Explicitly deferred (parity phase)

MVP intentionally does **not** include:

- `ui/*` extension UI topic subscription/rendering
- `footer/updated` parity rendering
- model/session controls UI (set/cycle model, thinking)
- multi-session resume/fork/tree controls
- reconnect-time resume picker / auto-resume

## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
