# Steps

- [x] Re-read the refined design and current tool registration / UI projection code before implementation.
- [x] Add canonical optional tool-definition display hooks and update the normalization/registration path (including `normalize-tool-def` and any registration/backfill seam) so runtime tool definitions preserve and project them into interactive UI tool renderers.
- [x] Migrate the in-scope built-in tools (`bash`, `read`, `edit`, `write`) to the shared registration path.
- [x] Preserve generic fallback rendering for tools without custom display hooks.
- [x] Update focused proofs for TUI, Emacs, UI projection, and extension registration behavior, including concrete Emacs shared-path parity coverage.
- [x] Update extension-facing documentation to replace primary `:register-tool-renderer` guidance with the shared tool-definition display path, demoting imperative renderer registration to compatibility/advanced usage if still supported.
- [x] Verify focused tests and lint as appropriate.
- [x] Replace the current RPC→Emacs render-hook path with a transport-safe shared mechanism that preserves cross-frontend parity without attempting to serialize executable functions across EDN RPC events.
- [x] Update Emacs implementation and focused proofs so the parity path is exercised through real RPC-compatible event/snapshot shapes rather than direct test-only lambda injection.
- [x] Tighten active-tool renderer projection so `:session/set-active-tools` removes stale renderer entries for tools that are no longer active or no longer declare render hooks.
- [x] Add focused proof that active-tool renderer projection stays in sync for both hook addition and hook removal.
- [x] Add a focused Emacs proof that a transport-safe `tool/executing` event with `:call-summary` updates the visible tool row header before any `tool/result` arrives.
- [x] Re-run the focused Emacs/RPC proof set after adding the live `tool/executing` coverage.
- [x] Optionally inventory whether the RPC prompt proof can be narrowed toward a more injectable/nullable dependency seam without losing the current end-to-end signal; if not, record that debt explicitly and defer it.
