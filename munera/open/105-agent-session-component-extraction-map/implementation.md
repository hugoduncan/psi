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

Supersession decision recorded:
- `102-turn-preparation-component-extraction` is superseded by this umbrella
- reason: its narrow extraction target proved structurally premature without the broader component map, especially around prompt composition and turn ownership

Follow-on architectural note after child task `107-project-nrepl-component-extraction`:
- the project-nrepl extraction landed successfully as a component move, but it exposed a cross-cutting config ownership question
- `psi.project-nrepl.config` now carries copied project-config reading logic to avoid an upward dependency on `agent-session`
- that may be acceptable temporarily, but the umbrella should remember this as a signal that config resolution/loading may deserve its own lower shared component rather than repeated local copies in extracted subsystems
