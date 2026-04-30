Goal: refactor the `mementum` and `munera` extensions so their injected protocol prompt bodies are loaded from extension-local classpath resource files instead of embedded inline as large string literals in code.

Intent:
- move content artifacts out of code
- preserve runtime behavior exactly
- keep the change narrow and local to these two extensions

Current state:
- `extensions/mementum/src/extensions/mementum.clj` contains a large inline string var holding the full Mementum protocol body.
- `extensions/munera/src/extensions/munera.clj` contains a large inline string var holding the full Munera protocol body.
- In both extensions:
  - `engage-prefix` is a small code-owned behavioral prefix
  - prompt mode is read from `:psi.agent-session/prompt-mode`
  - lambda mode uses only the protocol body
  - non-lambda mode uses `engage-prefix` followed immediately by the protocol body
  - `init` registers one prompt contribution with a fixed id/section/priority/enabled flag

Problem:
- The large protocol bodies are documentation/content artifacts, not logic.
- Keeping them inline obscures the small amount of actual extension behavior.
- The current code mixes two responsibilities in one namespace:
  - prompt-registration logic
  - long-form protocol content storage

Decision:
- Move only the protocol-body text into resource files.
- Keep `engage-prefix` in code.
- Keep prompt-mode branching in code.
- Keep prompt contribution metadata in code.
- Do not introduce a general prompt-resource framework.
- Use explicit fail-fast resource loading with a clear `ex-info` if a required resource cannot be found or read.

Required result:
1. `extensions.mementum` reads its protocol body from one resource file owned by the `mementum` extension.
2. `extensions.munera` reads its protocol body from one resource file owned by the `munera` extension.
3. The runtime-visible prompt content is identical to the current behavior:
   - lambda mode => exactly the protocol body text from the resource
   - non-lambda mode => exactly `(str engage-prefix protocol-body)`
4. Prompt contribution metadata is unchanged:
   - Mementum id remains `"mementum-protocol"`
   - Munera id remains `"munera-protocol"`
   - section names remain unchanged
   - priorities remain unchanged
   - `:enabled true` remains unchanged
5. Registration timing is unchanged: the contribution is still registered from `init`.

Unambiguous constraints:
- This is a refactor only. No protocol wording changes are allowed.
- This task must not change prompt assembly semantics.
- This task must not change lambda-mode detection.
- This task must not change extension public API shape.
- This task must not convert any other extension prompts to resources.
- Resource files must live inside the corresponding extension so ownership is local and obvious.
- The implementation must use UTF-8 when reading resource text.
- Missing-resource behavior must be one-way and explicit: throw `ex-info` naming the extension and missing resource path. Silent fallback is not allowed.

Content-preservation rule:
- The protocol text moved into each resource file must match the current inline protocol body exactly, character-for-character, excluding only the removal of the surrounding Clojure string literal syntax.
- Do not rewrite wording, spacing, indentation, or line content while moving the text.
- Store the resource with LF newlines.
- The loaded string used at runtime must equal the current runtime string content produced by the existing inline body.

Implementation shape:
- Add one UTF-8 text resource file for the Mementum protocol body.
- Add one UTF-8 text resource file for the Munera protocol body.
- Replace the two large inline protocol-body vars with resource-backed loading.
- Use a small local helper in each extension namespace. Do not build a broader abstraction.
- Keep `engage-prefix` as a code constant in each namespace.

Testing requirements:
- Add focused tests for `extensions.mementum`.
- Add focused tests for `extensions.munera`.
- Each extension test must prove both prompt modes:
  - lambda mode registers exactly the protocol body
  - non-lambda mode registers exactly `(str engage-prefix protocol-body)`
- Each test must assert the existing prompt contribution metadata for that extension.
- At least one focused failure-path test must prove missing-resource loading throws an explicit `ex-info` with the missing resource path in the message or ex-data.

Acceptance criteria:
- No large inline protocol-body string remains in `extensions/mementum/src/extensions/mementum.clj`.
- No large inline protocol-body string remains in `extensions/munera/src/extensions/munera.clj`.
- Both extensions load their protocol bodies from checked-in extension-local resources.
- Runtime prompt content remains behaviorally identical to the current implementation in both prompt modes.
- Missing-resource failure is explicit and fail-fast.
- Focused tests for both extensions pass.

Out of scope:
- changing protocol wording
- changing prompt contribution ids, sections, priorities, or enabled state
- changing `engage-prefix`
- introducing a generic prompt/resource subsystem
- refactoring unrelated extensions

Rationale for future readers:
- After this task, the code should make the behavioral logic obvious at a glance, and the protocol text should live where content artifacts belong: in resources.
