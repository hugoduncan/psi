2026-05-07

Task created to extract registry-style command ownership into a lower component.

Creation rationale:
- recent registry extractions clarified a useful split between lower registration/query ownership and higher orchestration
- commands still appear to have the same missing lower seam that tools recently had
- current command registration and command listing/query helpers live inside `agent-session.extensions`, which is broader than command ownership alone
- this task isolates that seam without broadening into command dispatch or a generic extension-registry redesign

Initial boundary hypothesis:
- new lower owner: `command-registry` for extension-owned command registration/query semantics
- higher owners retained: `agent-session`, RPC, and UI layers for orchestration, invocation, and presentation
- mutation/API seams retained above the boundary as thin adapters unless implementation reveals a clearly better bounded split

Open design point to resolve during implementation:
- confirm and record the exact duplicate/query semantics for commands, especially across multiple extensions registering the same command name
- do not assume the final contract; extract it from the live code/tests and preserve it intentionally

Relationship to umbrella work:
- this should become a concrete child under `105-agent-session-component-extraction-map`
- it is the command-side parallel to `111-tool-registration-component-extraction`
