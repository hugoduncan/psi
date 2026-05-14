# Steps

- [ ] Re-read the refined design and current tool registration / UI projection code before implementation.
- [ ] Add canonical optional tool-definition display hooks and wire registration to project them into interactive UI tool renderers.
- [ ] Migrate the in-scope built-in tools (`bash`, `read`, `edit`, `write`) to the shared registration path.
- [ ] Preserve generic fallback rendering for tools without custom display hooks.
- [ ] Update focused proofs for TUI, Emacs, UI projection, and extension registration behavior.
- [ ] Update extension-facing documentation to teach the shared tool-definition display path.
- [ ] Verify focused tests and lint as appropriate.
