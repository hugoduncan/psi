Approach:
- Execute this as a strict behavior-preserving refactor.
- Move only the two large protocol-body texts from code into extension-local UTF-8 classpath resources.
- Preserve all existing runtime semantics exactly:
  - same prompt contribution ids
  - same section names
  - same priorities
  - same `:enabled true`
  - same `init` registration timing
  - same prompt-mode branching
  - same `engage-prefix` behavior
- Keep the implementation local and explicit.
- Do not introduce a general prompt-resource subsystem.

Concrete plan:
1. inspect the current `extensions.mementum` and `extensions.munera` code and any existing tests to capture the exact current runtime strings and registration metadata
2. create one resource file owned by the `mementum` extension containing exactly the current Mementum protocol body text
3. create one resource file owned by the `munera` extension containing exactly the current Munera protocol body text
4. store both resources as UTF-8 text with LF newlines only
5. replace the inline protocol-body vars with resource-backed loading
6. keep `engage-prefix` as code-owned behavior and keep prompt-mode branching unchanged
7. implement one-way failure for missing/unreadable resources using explicit `ex-info` that names the extension and resource path
8. add focused tests for both extensions proving:
   - lambda mode registers exactly the resource body
   - non-lambda mode registers exactly `(str engage-prefix protocol-body)`
   - existing contribution metadata is unchanged
9. add at least one focused failure-path test proving missing-resource loading fails explicitly with the resource path named
10. run focused verification for the two extension test namespaces

Non-negotiable decisions:
- No prompt text editing during the move.
- No silent fallback to inline text or empty text.
- No central shared resource bucket.
- No unrelated extension refactors.
- No shared helper

Primary risk controls:
- compare resource content against current runtime strings to prevent text drift
- normalize on LF in the files so newline behavior is deterministic
- assert exact content in tests rather than partial substring checks

Definition of done:
- both large inline protocol-body literals are removed from code
- both protocol bodies are loaded from extension-local resources
- runtime-visible prompt content remains identical in both modes
- missing-resource failure is explicit and tested
- focused tests pass
