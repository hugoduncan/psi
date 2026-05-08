Approach:
- treat this as a narrow registry extraction for extension-owned command semantics, not a command-dispatch redesign
- mirror the successful tool-registry pattern where it fits while staying specific to commands
- preserve current registration/query behavior exactly, making the implicit command contract explicit through focused tests before or during migration

Planned sequence:
1. verify the documented command registration and command query semantics against live code/tests and preserve them intentionally
2. define the smallest useful `command-registry` API for registration, lookup, names, and listing helpers
3. create `components/command-registry/` and add focused registry tests first
4. implement the first-cut command validation/normalization contract needed to preserve current behavior: exact-name identity, no slash-prefix normalization, `:name` as the only registry-required field, and explicit invalid-registration behavior at the registry boundary
5. delegate command registration/query ownership from `agent-session.extensions` into the extracted component
6. keep mutation/API seams (`register-command`) as thin higher-level adapters
7. update direct higher-level consumers to require `psi.command-registry.*` where appropriate
8. run focused verification for the new component and affected higher-level command paths, including duplicate semantics, missing-lookup behavior, and invalid-registration behavior
9. record final duplicate/query semantics, invalid-registration behavior, and any non-obvious tradeoffs in `implementation.md`

Design constraints:
- do not absorb command execution or slash-command parsing/routing into the new component
- do not widen into a generic extension-registry extraction for flags, shortcuts, tools, prompts, and operations
- preserve the current extension-registry state shape unless a tiny local helper is clearly beneficial
- prefer one obvious lower owner over anticipatory abstraction
- keep UI/RPC command presentation and invocation semantics outside this task

Verification intent:
- new component tests should prove command registration, lookup, names, and listing semantics directly
- at least one higher-level mutation/API or command-consumer path should remain tested above the boundary
- implementation must explicitly verify and record cross-extension duplicate semantics rather than leaving them implicit
