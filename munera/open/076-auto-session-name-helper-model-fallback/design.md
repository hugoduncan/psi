Goal: make the auto-session-name extension fall back across ranked helper models when the top-ranked helper candidate is not runnable, so naming remains best-effort in environments where local model metadata and live local model availability diverge.

Context:
- the auto-session-name extension currently selects one helper model via `psi.ai.model-selection/resolve-selection`
- that selection is driven by catalog metadata and policy preferences such as locality, latency tier, and cost tier
- the selector does not currently know whether a local model is actually runnable right now
- as a result, the extension can choose a configured local model that is not running, even when another qualifying local model is running and would succeed
- helper execution failure is only discovered when the child helper session runs `psi.extension/run-agent-loop-in-session`
- the extension is best-effort and already tolerates no-op outcomes when title inference cannot be applied

Problem:
- helper model selection currently answers “best ranked candidate from the catalog” rather than “best runnable candidate right now”
- when the top-ranked helper model is not runnable, the extension stops after one failed attempt and does not try lower-ranked candidates
- this makes helper naming brittle for local providers whose configured model set is broader than the set of currently running models
- the immediate bug to solve is extension-local resilience, not a global redesign of model-selection or provider-health introspection

Required behavior:
- auto-session-name keeps its existing helper model selection policy and ranking intent
- instead of treating the top-ranked helper candidate as the only attempt, the extension must treat the ranked survivor list as an ordered fallback sequence
- helper execution attempts proceed in the exact order of the ranked candidate vector returned by model selection until one attempt produces a rename success or the candidate list is exhausted
- if the first candidate is not runnable but a later ranked candidate is runnable, the extension should still infer and apply a title
- if the ranked survivor list is empty, that counts as fallback exhaustion
- if all helper candidates fail or the candidate list is empty, the extension must preserve the current no-rename behavior and emit the existing single notification/log fallback outcome once after exhaustion
- stale-checkpoint suppression, helper-session recursion protection, manual-override protection, and title validation/application semantics must remain unchanged

Outcome model:
- distinguish these outcomes explicitly:
  - execution success: helper run completed with `:psi.agent-session/agent-run-ok? true`
  - title success: execution success plus a normalized title that passes the existing title validation rules
  - rename success: title success plus source-session guards still allow applying the inferred title
- fallback is driven by helper-attempt outcomes, while stale/manual source-session guards remain authoritative gates on applying a rename

Fallback semantics:
- fallback is owned by the auto-session-name extension, not by global model-selection and not by the generic session runtime in this task
- the retry boundary is helper execution attempt selection, not title post-processing
- the extension must compute one sanitized bounded conversation snapshot for the checkpoint attempt and reuse that same snapshot across all fallback candidates for that checkpoint
- each helper attempt uses the same naming prompt contract, the same system prompt, and the same user prompt text derived from that source-session conversation snapshot for that checkpoint attempt; only the helper model varies across fallback attempts
- each helper attempt uses a fresh helper child session; reusing a previous failed helper child session is out of scope for this task
- the ordered candidate list comes from the existing model-selection ranking result for the extension’s current helper selection request
- prefer consuming the existing `resolve-selection` ranking result, ideally by reading the existing ranked candidate vector directly, without changing global model-selection semantics or result shape; add a small shared accessor only if needed for code-shaping clarity

Failure policy:
- this task uses pragmatic best-effort fallback semantics
- fallback continues to the next ranked candidate when a helper attempt:
  - throws during helper execution
  - returns `:psi.agent-session/agent-run-ok? false`
  - returns `:psi.agent-session/agent-run-ok? true` but does not yield a valid usable title after normalization and existing validation
- this task does not require provider-specific parsing to distinguish “model unavailable” from other helper execution failures
- the extension must re-check stale/manual stop conditions before starting each further candidate attempt and again before applying a rename
- fallback must terminate immediately, without trying further candidates, when:
  - the checkpoint becomes stale before, during, or after an attempt
  - manual-override protection detects that the current source-session name has diverged from the last auto-applied name before rename application
- stale/manual guard termination is not a helper-model failure case; it is an authoritative source-session stop condition

Selection semantics to preserve:
- the extension’s helper model request remains responsible for expressing the desired policy:
  - require text support
  - require low latency tier
  - require zero or low cost tier
  - strongly prefer local models
  - strongly prefer lower input and output cost
  - weakly prefer the same provider as the source session
- this task must not change the ranking criteria or preference ordering; only code-shaping changes needed to consume the ranked survivor list for fallback are allowed
- the top-ranked candidate should remain the first attempted helper model

Notification and logging semantics:
- intermediate helper-attempt failures are not user-visible events in this task
- this task does not require any new per-attempt logging; if implementation-local debug logging is added using existing seams, tests and behavior must not depend on it
- the existing no-op notification/log fallback should occur once, after fallback exhaustion, rather than once per failed candidate attempt
- this task does not require new user-visible error reporting for per-candidate helper failures

Scope:
- in scope:
  - extension-local fallback across ranked helper models for auto-session-name
  - consumption of the ranked selection result needed to drive ordered fallback
  - focused tests proving fallback behavior, attempt ordering, and guard preservation
- out of scope:
  - global provider-health or live-model discovery infrastructure
  - changing general interactive session model selection
  - redesigning `psi.ai.model-selection` into a runtime-availability-aware selector
  - inventing new provider-specific availability probes unless separately requested
  - changing generic session runtime fallback semantics

Acceptance:
- auto-session-name can consume the ordered ranked helper candidates implied by its existing selection request
- fallback attempts follow the exact ranked candidate order returned by model selection
- when the first ranked helper model fails by throw, unsuccessful run result, or invalid title result, the extension attempts the next ranked candidate in order
- when a later ranked candidate succeeds and yields a valid title, the source session is renamed exactly once using the existing title validation and overwrite guards
- when the ranked candidate list is empty, or all candidates fail, the source session is not renamed and the extension emits the existing single no-op notification/log outcome once after exhaustion
- stale checkpoint suppression still prevents rename after source-session advancement and terminates further fallback attempts for that checkpoint
- manual-name overwrite protection still prevents replacing a diverged current name and terminates further fallback attempts for that checkpoint
- helper-session recursion protection still prevents nested scheduling from helper sessions
- focused tests prove at least:
  - fallback from a failed first helper candidate to a succeeding later candidate
  - fallback from a first candidate that returns an invalid title to a later candidate that returns a valid title
  - exhaustion of all candidates without rename
  - empty ranked candidate list behaves as exhaustion without rename
  - preservation of stale-checkpoint behavior during fallback, including immediate fallback termination when the checkpoint becomes stale
  - preservation of manual-override behavior during fallback, including immediate fallback termination when manual divergence is detected
  - top-ranked-first attempt ordering, matching the exact ranked candidate order returned by model selection
  - single notification/log fallback emission after exhaustion rather than one emission per failed attempt

Non-goals:
- proving that the selected candidate is runnable before execution
- adding a runtime “running model” attribute to the shared model catalog
- centralizing fallback in the generic session runtime
- broadening the task into a universal helper-model availability framework

Notes:
- this task intentionally chooses a small extension-local resilience improvement over a larger architectural availability project
- if a later project introduces authoritative provider/model runtime availability facts, auto-session-name may be simplified to rely more on selection-time filtering and less on execution-time fallback
