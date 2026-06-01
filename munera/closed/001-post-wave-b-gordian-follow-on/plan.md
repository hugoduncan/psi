Approach:
- Treat this as a continuation umbrella for the active Gordian follow-on thread.
- Prefer small vertical slices with explicit structural intent.
- Start from the currently identified near-term RPC edge thinning target.

Likely steps:
1. inspect remaining overlap between `psi.rpc.session.commands` and `psi.rpc.session.frontend-actions`
2. identify one small extraction or centralization slice
3. land the slice with focused proof
4. rerun structural checks if the slice changes boundary shape

Risks:
- over-extracting transport code into a premature abstraction
- broad router reshaping instead of local thinning
