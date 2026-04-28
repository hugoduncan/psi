# 063 — Implementation notes

This task is Phase 4 extracted from umbrella task 059.

Key constraints:
- use one canonical transcript/message source of truth
- keep projection constrained and deterministic
- avoid branching into a general conversation transformation language

Implemented:
- `:session :preload` now compiles alongside `:input` / `:reference` using the same source-then-projection layering model
- preload supports value-style entries (`:workflow-input`, `:workflow-original`, `{:step ... :kind :accepted-result}`) and transcript entries (`{:step ... :kind :session-transcript}`)
- transcript preload projections reuse `workflow-judge/project-messages` so judge projection and preload projection share one deterministic implementation
- canonical transcript/message source of truth for transcript preload is the child/session journal via `persistence/messages-from-entries-in`
- statechart step entry now materializes compiled preload into `:preloaded-messages` for child-session creation

Focused proof:
- compiler tests cover preload compilation and malformed transcript projection rejection
- authoring-session tests cover preload validation surfaces
- step-prep tests cover materialization from canonical workflow bindings + canonical persisted transcript journal
- statechart runtime test covers forwarding materialized preload into child-session creation

Review note:
- implementation is now well-shaped: simpler, more consistent, and safer after constraining value preload to text-only projection
- good reuse of canonical journal + `workflow-judge/project-messages`
- follow-on feedback was executed: preload compilation helpers now live in a narrower namespace and preload role-synthesis policy is now explicit in that authoring surface
