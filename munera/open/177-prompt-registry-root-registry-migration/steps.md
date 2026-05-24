# Steps

Reserved for executable implementation work after design/plan are clear.

- [x] Implement the prompt-registry root-registry adapter and migrate prompt contribution write/read seams according to the refined design.
- [x] Add focused prompt-registry contract tests plus at least one higher-surface coherence test proving legacy session-local prompt-contribution vectors are no longer authoritative.
- [x] Run focused verification and then full-suite verification before closing the task.
- [x] Migrate nullable extension-test helper prompt storage/query seams to the same root-registry-backed authority used by runtime prompt-registry paths, with focused proof against stale local prompt vectors.
- [x] Reshape session lifecycle tests (`session_state/init_test`) to prove authoritative prompt behavior from `:prompt-contribution-ids` + root-registry-backed reads rather than merely preserving stale copied `:prompt-contributions` compatibility vectors across new/resume/fork initialization.
