# 063 — Steps

- [x] Define constrained `:session :preload` authoring using the same source+projection layering model as tasks `060` and `061`
- [x] Settle canonical source of truth for projected step-session messages
- [x] Implement at least one transcript/message projection form
- [x] Feed projected context into child-session preloading without changing `$INPUT`/`$ORIGINAL` binding ownership
- [x] Reject malformed preload specs, unsupported operators, and forward/unknown step references
- [x] Add focused execution tests
- [x] Run focused workflow tests

Progress notes
- investigating current canonical transcript surfaces: `workflow_judge/project-messages` already provides deterministic transcript projection and `persistence/messages-from-entries-in` is the canonical session journal message source

Follow-on shaping
- [x] decide whether value preload should be constrained to text-like projections in the first cut
- [x] if constrained, reject or normalize `:projection :full` for value preload sources so non-text values are not silently stringified
- [x] document the remaining synthetic-message semantics for value preload explicitly
- [x] consider extracting preload compilation helpers from `workflow_file_authoring_session.clj` if additional preload surface area lands
- [x] decide whether preload role synthesis (`user` for workflow/original, `assistant` for accepted-result) should remain implicit policy or become a more explicit author-facing contract
