⊘ Dev/agent-facing surfaces that accept caller-supplied data must validate input shape and FAIL LOUD; silent degradation hides mistakes. The failure modes to hunt: literal rendering (a `:hiccup` string rendered as a text node instead of a tree), empty output (a non-map to a table renderer → empty table), and broken/garbage output (wrong-typed spec → broken chart). Each "looks like it worked" and wastes debugging time.

Rule: at the boundary, check the expected type/shape and return a clear error naming (a) the surface, (b) the expected shape, and (c) the ACTUAL type + a bounded value preview. A `400 :hiccup data must be a hiccup tree (vector/array), got string: "[:div …]"` is immediately diagnosable; literal text is not.

This is the project's "silent failure where a loud error would serve better" principle (`λone_way`, robustness). Prefer one shared `type-error-response`-style helper over per-site ad-hoc messages so the error shape is consistent.

Found via real use of dev-http renderers (229) — behaviour tests passed because they only fed well-shaped data; the gap was only the unhappy path. When building such surfaces, test the wrong-shape path explicitly.
