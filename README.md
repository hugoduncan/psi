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

`/status` `/history` `/new` `/help` `/quit` `/skills` `/prompts`
`/skill:<name>` plus any extension commands

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

## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
