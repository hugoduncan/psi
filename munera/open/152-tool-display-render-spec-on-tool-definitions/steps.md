# Steps

- [x] Re-read the refined design and current tool registration / UI projection code before implementation.
- [x] Add canonical optional tool-definition display hooks and update the normalization/registration path (including `normalize-tool-def` and any registration/backfill seam) so runtime tool definitions preserve and project them into interactive UI tool renderers.
- [x] Migrate the in-scope built-in tools (`bash`, `read`, `edit`, `write`) to the shared registration path.
- [x] Preserve generic fallback rendering for tools without custom display hooks.
- [x] Update focused proofs for TUI, Emacs, UI projection, and extension registration behavior, including concrete Emacs shared-path parity coverage.
- [x] Update extension-facing documentation to replace primary `:register-tool-renderer` guidance with the shared tool-definition display path, demoting imperative renderer registration to compatibility/advanced usage if still supported.
- [x] Verify focused tests and lint as appropriate.
