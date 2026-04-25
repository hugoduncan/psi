Implementation notes:
- Task created from live discrepancy where `/gh-issue-work-on 27` returned `[not a command]` despite `gh-issue-work-on` being loaded from `.psi/prompts/`.
- Expected architectural direction: shared backend ownership of slash-command vs prompt-template fallback semantics; RPC should remain transport-only.
- Completion visibility is part of the task: loaded prompt templates should appear in slash autocomplete/completion on supported interactive surfaces rather than being invokable-but-undiscoverable.
- Initial inspection note: TUI autocomplete already appears to source `:prompt-templates` into slash candidates; verify whether the missing completion gap is RPC/Emacs-specific, stale-state-related, or broader before changing adapter code.
- Design constraint: implementation should avoid separate RPC-local and app-runtime-local fallback semantics; one shared backend resolution path should own command vs template vs unknown behavior.
- Design constraint: behavior and tests should be driven by the loaded session prompt-template set, including templates introduced after startup by registration or reload.
- No implementation yet.
