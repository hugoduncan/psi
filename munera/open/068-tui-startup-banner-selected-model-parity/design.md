Goal: make the TUI startup banner show the actual effective current session model instead of a stale/default model label.

Intent:
- restore trust in the TUI banner as a truthful summary of current operator state
- bring startup banner model display into parity with the real effective current session model used by the session/runtime
- prevent confusion at startup when the banner claims one model while prompts run with another

Problem:
When the TUI starts, the banner displays a model line. Today that line can show `Claude Sonnet 4.6` even when the effective current session model is different. This creates a misleading first impression of session state and makes the banner an unreliable operator-facing status surface.

The defect matters because the startup banner is the first visible summary a TUI user sees. If it reports the wrong model, users cannot tell whether model selection persisted correctly, whether runtime configuration took effect, or whether subsequent prompts will use the shown model.

Inspected root cause:
- The TUI banner is rendered from `state :model-name` in `components/tui/src/psi/tui/app/render.clj`.
- `:model-name` is seeded once in `components/tui/src/psi/tui/app/support.clj/build-init` from the `model-name` argument passed to `make-init`.
- `start-tui-runtime!` currently passes `(:name ai-model)` into the TUI initializer.
- But `create-runtime-session-context` resolves an `effective-model` from config/project preferences and installs that into session defaults before the session is created.
- Therefore the TUI can be initialized with the launch/default model name while the actual live session already uses a different effective current session model.
- The banner state is also effectively static: it is not derived from the canonical session/footer model each render, so even a later `/model` change would leave the startup banner using old local state.

This is a source-of-truth bug, not a text-layout bug.

Scope:
In scope:
- the TUI startup banner model display
- the source of truth used to determine the model shown there
- initialization/refresh behavior needed so the banner reflects the effective current session model at startup
- focused proof that the banner changes when the effective current session model changes
- preserving existing banner behavior for other startup metadata

Out of scope:
- redesigning the startup banner layout
- changing model selection semantics
- changing provider/model naming policy beyond what is required for truthful display
- converting prompt/skill/extension banner summaries from startup snapshots into live runtime projections
- broader footer/status model display work outside the startup banner, except where a shared canonical source is reused
- unrelated TUI wrapping or transcript rendering issues

Requirements:
- the startup banner must show the effective current session model, not a hard-coded or stale launch-time label
- if a non-default model is selected or otherwise resolved before TUI startup, the banner must reflect that effective current session model on first render
- if no explicit alternate model is selected, the banner may show the true effective default model
- the displayed model label must come from the same canonical runtime/app surface that already knows the current model for the active session
- the banner must not mix a model label from one source with provider/thinking metadata from another
- the banner model source should remain correct after model changes, not only at first launch
- this task changes only the banner model line; prompt, skill, and extension banner summaries remain startup snapshot metadata in this slice

Concrete design direction:
Use the canonical footer model projection as the banner model source instead of the TUI-local `:model-name` field.

Canonical banner model contract:
- the banner model line displays exactly the current value of `[:footer/model :text]`
- this line is a live canonical surface, not startup snapshot data
- TUI-local `:model-name` must not influence rendered banner output

Banner ownership distinction for this task:
- the **model** line is treated as live current-session state and must therefore be canonical/current
- the **prompts**, **skills**, and **extension summary** lines remain startup snapshot metadata in this task
- this slice does not attempt to make the entire banner live-authoritative; it removes incorrect current-state information from the model line specifically

Why this is the right source:
- the TUI already receives `:footer-model-fn` from app-runtime
- `footer-model-fn` calls `psi.app-runtime.footer/footer-model` for the active session
- `footer-model` derives model data from authoritative session state (`:psi.agent-session/model-provider`, `:psi.agent-session/model-id`, thinking-level/effective reasoning effort)
- this avoids duplicating model resolution logic in the TUI
- although the bug is observed at startup, this task intentionally makes the banner model line a live canonical surface rather than a startup snapshot
- this makes the banner truthful both at startup and after later `/model` or thinking-level changes

Rejected alternatives:
1. Pass a better initial `model-name` string into TUI init
- would fix the immediate startup symptom only if every startup path remembers to pass the effective current session model
- leaves the banner locally cached and stale after later model changes
- preserves two interpretations of model identity: banner local state vs footer/session state

2. Query model attributes directly in the TUI render path and rebuild the label locally
- duplicates existing footer/session summary logic
- increases drift risk between banner model display and other UI surfaces
- violates the project preference for one obvious authoritative path

3. Keep `:model-name` as a fallback
- preserves ambiguity and hidden drift
- makes future regressions easier because two sources can disagree silently

Chosen approach:
- make the banner model line consume canonical model data from `:footer-model-fn`
- remove the TUI-local banner dependence on `:model-name` as the authoritative banner source
- remove `:model-name` from TUI state if it is unused after the rendering change
- avoid fallback banner model sources; incorrect local information should be removed rather than retained

Implementation shape:
1. Rendering:
- in `components/tui/src/psi/tui/app/render.clj`, derive the banner model text from `(:footer-model-fn state)` rather than from `:model-name`
- use exactly `[:footer/model :text]` as the banner model line text
- do not preserve a second banner-model source in steady state

2. State/init cleanup:
- stop treating `:model-name` as the authoritative banner model source
- remove `:model-name` from TUI state if it becomes unused after the rendering change
- keeping or removing the external `make-init` / `start!` `model-name` parameter is an implementation choice, but it must no longer influence rendered banner model output
- update tests that currently assert or depend on `:model-name` state so they instead assert the visible/canonical behavior that matters

3. Runtime wiring:
- no new model-resolution logic should be added to TUI startup
- reuse the existing `:footer-model-fn` injected by app-runtime

Proof:
Add focused tests that distinguish launch/default model from the effective current session model.

Minimum proof set:
1. Banner uses canonical footer model data
- create a TUI state whose `:footer-model-fn` returns a footer model containing `[:footer/model :text]`
- assert the rendered banner displays exactly that `[:footer/model :text]` value
- if transitional code still exists during implementation, use a conflicting local model value in the test to prove it is ignored, then remove that local state entirely when cleanup lands

2. Default/effective model still renders when no alternate selection exists
- supply footer model data whose `[:footer/model :text]` represents the default/effective current session model
- assert normal banner rendering remains intact

3. Stronger proof: banner updates when model changes
- use a mutable `footer-model-fn` backing atom
- render once with model A, then again with model B
- assert the banner changes to model B without rebuilding the whole TUI init state
- this proves the fix removes the stale local-cache behavior rather than only patching startup

Acceptance:
- the TUI startup banner no longer always reports `Claude Sonnet 4.6`
- when the effective current session model differs from the launch/default model, the banner shows that effective current session model on first render by displaying exactly `[:footer/model :text]`
- the banner model line is derived from the canonical session/footer model surface, not a stale TUI-local copy
- stale local banner-model state is removed if it is unused after the rendering change
- focused tests prove the banner follows canonical current model data and updates when that canonical data changes
- a focused test proves expected behavior still holds for the default/effective model case
- prompt, skill, extension, and controls lines remain present with their current startup-snapshot semantics
- relevant TUI tests remain green

Notes:
- the inspected code suggests the bug is broader than a single default string: the banner model line is structurally stale because it is seeded once from launch input instead of read from active session state
- this task should leave behind one obvious source of truth for banner model display rather than parallel default-vs-selected interpretations
