# 063 — Plan

1. Define the constrained `:session :preload` authoring surface using the same source+projection layering model established by tasks `060` and `061`.
2. Choose and document the canonical source of truth for projected step-session messages.
3. Implement at least one transcript/message projection form, with tail/tool-output options only if they fit cleanly.
4. Feed projected context into child-session preloading without changing `$INPUT`/`$ORIGINAL` binding ownership.
5. Validate malformed preload specs, unsupported operators, and forward/unknown step references.
6. Add focused execution tests.
