# Design: Automatic Session Purpose Renaming — Gap Analysis

This section evaluates whether the current architecture appears to provide the
necessary pieces for the design.

## Summary

The system appears to have most of the major building blocks needed for this
extension, but not yet all of them as a clean extension-facing path.

Current fit:
- **strong** for session renaming
- **strong** for isolated helper execution primitives
- **moderate** for background workflow/job execution
- **moderate** for scheduling primitives
- **weak to moderate** for canonical trigger and transcript-sanitization surfaces
- **weak** for explicit metadata/policy around manual vs automatic names

## Capability fit by concern

### 1. Renaming the source session

**Present**: The platform already exposes the ability to set a session name.

**Fit**: Strong. This part appears ready.

**Gap**: The current visible model appears to support a name string, but the design also
benefits from name metadata such as:
- name source (`manual` vs `auto`)
- updated-at
- derivation marker

Without this metadata, conflict handling between user renames and automatic
renames will be brittle.

### 2. Running rename inference outside the source session

**Present**: The platform appears to support child/helper session creation and execution in
an isolated context.

**Fit**: Strong, conceptually.

**Gap**: The design needs a clean distinction between:
- user-facing sessions
- internal helper sessions used only for extension logic

If helper sessions are visible in normal session navigation, the feature will
introduce noise and confusion.

### 3. Background execution

**Present**: The runtime already has workflow-backed background jobs and job reconciliation.

**Fit**: Moderate to strong.

**Gap**: The extension needs a simple canonical way to express:
- one rename job per source session
- in-flight suppression/coalescing
- silent helper execution
- stale-result discard before apply

These are architectural policies on top of the current job substrate and should
be made explicit.

### 4. Scheduling/debounce

**Present**: A scheduler primitive exists in the runtime/dispatch layer.

**Fit**: Moderate.

**Gap**: It is not yet clear that extensions have a clean, direct, high-level scheduling
surface for "run this rename evaluation after the next eligible idle boundary"
or "debounce repeated triggers".

This may not block the feature if workflow/background execution can be triggered
from lifecycle events, but it does mean that "use the scheduler" is currently a
design preference rather than a clearly complete extension surface.

### 5. Triggering every N completed turns

**Present**: The prompt lifecycle is explicit and completed-turn semantics exist in the
architecture.

**Fit**: Moderate.

**Gap**: The design needs a canonical extension-observable hook for:
- completed prompt-lifecycle turn
- session returned to idle
- source session identity

If extensions do not currently receive a clean event at that semantic boundary,
then the runtime is missing the most natural trigger surface for this feature.

This is the single most important likely gap.

### 6. Building a sanitized transcript

**Present**: The system already distinguishes user-visible display content from some command
noise, and transcript/message projections exist.

**Fit**: Moderate.

**Gap**: The extension needs a canonical sanitized conversation view tailored to purpose
inference, not merely a raw journal dump.

Needed semantics include exclusion of:
- tool calls/results
- hidden reasoning/thinking
- slash commands
- background-job chatter
- extension/system noise

If each extension has to reconstruct this on its own, behavior will be
inconsistent and fragile. A shared projection or query surface would make this
feature much cleaner.

### 7. Preventing recursion

**Present**: Helper contexts and extension workflows exist, so recursion can likely be
avoided by convention.

**Fit**: Moderate.

**Gap**: The design needs an explicit way to mark helper contexts as internal and make
extension triggers ignore them. Without a standard marker, recursion prevention
will be ad hoc.

### 8. Stale-result protection

**Present**: The architecture already values explicit routing and traceable lifecycle stages.

**Fit**: Moderate.

**Gap**: The extension needs a stable source revision marker or equivalent read-model so
it can determine whether the source session advanced during the helper run.

Without a canonical notion of "source snapshot version", stale overwrites are
more likely.

### 9. Manual vs automatic naming policy

**Present**: Session names exist.

**Fit**: Weak.

**Gap**: There does not appear to be a first-class naming policy model. The proposed
feature needs one, even if minimal.

At minimum, the architecture should answer:
- was this name set manually or automatically?
- should auto-rename be allowed to overwrite it?

### 10. User-facing observability

**Present**: The UI and background-job systems can surface status if desired.

**Fit**: Moderate.

**Gap**: This is optional for v1, but operationally useful. If the extension silently
fails all renames, users may not know whether the feature is disabled, blocked,
or merely finding no better title.

A lightweight optional observability surface may be desirable later.

## Recommended runtime-facing additions

These are not code-level prescriptions; they are capability-level additions that
would make the extension clean and robust.

1. **Completed-turn extension event**
   - A canonical event emitted when a session turn fully completes and the
     session returns to idle.

2. **Sanitized conversation projection**
   - A shared read-model for "purpose inference conversation".

3. **Internal helper-session marker**
   - A standard way to create or mark extension-owned helper contexts as
     non-user-facing and non-recursive.

4. **Name metadata / policy model**
   - Support for distinguishing manual and automatic names.

5. **High-level extension scheduling/debounce surface**
   - A cleaner extension-facing capability for coalesced delayed work.

6. **Source revision marker**
   - A stable session-version concept suitable for stale-result detection.

## Recommendation

This extension is viable, but it should be treated as a feature that will also
clarify a few missing extension/runtime seams.

Recommended path:
1. establish the trigger surface at completed-turn/idle boundary
2. establish a canonical sanitized conversation projection
3. establish helper-session/internal-run semantics
4. define manual-vs-auto naming policy
5. build the extension on top of those clarified surfaces

With those pieces in place, the extension should be straightforward and
architecturally consistent.
