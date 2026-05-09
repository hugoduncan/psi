Approach:
- treat higher-order workflows as a narrow workflow-model extension, not a new execution substrate
- preserve the current named-workflow/delegate architecture and extend it with first-class workflow-reference values
- prefer extending existing delegate target semantics over adding new step kinds
- keep executable authority in canonical discovered/registered workflows rather than runtime-generated workflow code
- make validation, capability enforcement, and failure behavior explicit at each layer
- land the work in small vertical slices that separately prove model shape, IR/validation, runtime resolution, and end-to-end delegation

Execution slices:

Slice 1 — Model and ownership alignment
1. inspect the current workflow grammar, IR, target compiler, source-resolution, and delegate runtime owners that currently assume static target names
2. identify the smallest canonical owner for workflow-reference value semantics
3. record the chosen external shape, IR shape, and validation boundaries in `implementation.md`
4. confirm which current static delegate behaviors must remain unchanged

Slice 2 — Grammar / IR / compiler extension
1. extend the workflow model so delegate targets can represent either a static workflow name or a dynamic workflow-reference source
2. add explicit workflow-reference value semantics rather than relying on incidental plain strings alone
3. update target-authored validation and IR compilation accordingly
4. ensure malformed higher-order target shapes fail clearly and locally
5. keep compiled semantics simple enough that runtime resolution remains explicit and deterministic

Slice 3 — Runtime target resolution
1. extend canonical workflow source/delegate resolution to accept the new workflow-reference path
2. resolve dynamic targets through the same canonical workflow-definition lookup/enforcement path already used for static delegation
3. fail explicitly with distinct semantics for authored-shape failure, runtime-type failure, lookup failure, and availability failure
4. preserve existing delegate result/handoff semantics once the target workflow is resolved
5. prefer structured data outputs as the path by which workflow references flow between steps; do not rely on free-form generated text as the canonical higher-order transport

Slice 4 — Proof
1. add focused grammar/compiler proof for static compatibility, dynamic target compilation, and malformed-shape failures
2. add focused runtime proof for successful dynamic resolution and explicit failure modes
3. add at least one end-to-end higher-order workflow proof where one step selects/yields a workflow reference and a later delegate step consumes it
4. verify downstream delegated yield/handoff behavior is unchanged after dynamic resolution

Slice 5 — Docs and coherence
1. update workflow docs to explain first-class workflow references and dynamic delegation
2. show static-vs-dynamic authoring guidance with a minimal worked example
3. explicitly document that higher-order support does not include runtime workflow generation
4. verify coherence across design, docs, tests, and code-facing ownership notes

Planned outcomes:
- workflows gain first-class workflow-reference values as a canonical higher-order composition mechanism
- delegate steps can target either static names or resolved workflow references
- higher-order composition remains deterministic, capability-safe, and replay-friendly
- authored workflows remain the only canonical executable workflow units
- static delegation stays compatible while dynamic delegation becomes explicit and documented

Scope boundaries:
- no runtime-authored executable workflow generation
- no anonymous workflow execution model
- no workflow-map/reduce/parallel higher-order operators in this slice
- no broad redesign of workflow discovery, loading, or registry semantics
- no new generic execution step kind unless extending `:delegate` proves clearly insufficient and the reason is recorded
