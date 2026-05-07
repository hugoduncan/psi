2026-05-07

Task created to capture the coherent component extraction map latent inside `agent-session`.

Creation rationale:
- recent turn-runtime/turn-preparation discussion showed that narrow extraction tasks can become structurally awkward when pursued before the broader subsystem map is explicit
- the right frame is the component map just above `session-state`, not a sequence of isolated namespace moves
- this umbrella is intended to guide which existing tasks remain valid children and which should be closed or re-scoped

Initial map captured in `design.md`:
- probable `agent-session` core remains session lifecycle / context / dispatch / statechart / orchestration
- strongest extraction candidates identified as:
  - prompt composition / prompt assets
  - OAuth / provider auth
  - tool runtime
  - turn
  - workflow
  - project nREPL
  - extensions runtime
  - scheduler
  - persistence / journal
  - background jobs

Child-task results now incorporated:
- `106-provider-auth-component-extraction` is landed
  - confirms OAuth / provider auth was correctly identified as a low-ambiguity early extraction
  - new authoritative component is `components/provider-auth/`
  - authoritative namespaces now live under `psi.provider-auth.*`
  - downstream `app-runtime`, `rpc`, and `agent-session` now depend downward on the extracted auth component
- `107-project-nrepl-component-extraction` is landed
  - confirms project nREPL was correctly identified as a low-ambiguity early extraction
  - new authoritative component is `components/project-nrepl/`
  - authoritative namespaces now live under `psi.project-nrepl.*`
  - downstream `agent-session` command/context/psi-tool/resolver code now depends downward on the extracted project-nREPL component

Supersession decision recorded:
- `102-turn-preparation-component-extraction` is superseded by this umbrella
- reason: its narrow extraction target proved structurally premature without the broader component map, especially around prompt composition and turn ownership

Follow-on architectural notes after child tasks `106` and `107`:
- `106` did not expose a comparable ownership surprise; it behaved like the clean bounded extraction the umbrella predicted
- `107` did expose a cross-cutting config ownership question
- `psi.project-nrepl.config` now carries copied project-config reading logic to avoid an upward dependency on `agent-session`
- that may be acceptable temporarily, but the umbrella should remember this as a signal that config resolution/loading may deserve its own lower shared component rather than repeated local copies in extracted subsystems
- `107` also accepted one non-blocking behavior drift at the higher-level tool boundary: `project-repl/start` missing-config handling now returns a structured component result, so the `psi-tool` path may want a later follow-on if stricter tool-facing error semantics matter
