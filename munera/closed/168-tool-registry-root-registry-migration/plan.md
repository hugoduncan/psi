# Plan

1. Audit the current `tool-registry` storage shape, focused tests, and direct callers against the `167` migration pattern.
2. Pin the exact compatibility surface that must remain at the `tool-registry` boundary.
3. Refactor `tool-registry` internals to store built-in and extension-owned tool data through `root-registry`.
4. Keep tool-name validation, `:format-request` enforcement, and canonical normalization in `tool-registry` / `tool-registry.defs`.
5. Preserve merged read behavior:
   - built-ins included in `tool-names-in`, `all-tools-in`, and `get-tool-in`
   - built-ins shadow same-name extensions on merged reads
   - `all-tools-in` preserves built-ins-first then extension registration-order projection
6. Update focused `tool-registry` tests to prove preserved behavior and migrated storage ownership.
7. Preserve and explicitly test the pinned compatibility details from ambiguity review:
   - built-ins-first merged ordering, with built-in provenance registration order before extensions
   - caller-visible provenance/read-shape fields (`:source`, `:ext-path`, `:extension-path`)
   - same-owner replacement semantics for extension-owned and built-in tools
8. Verify focused tests, lint, and formatting.
