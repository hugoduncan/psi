# Design follow-up steps

- [x] Clarify whether the task must update the direct interactive model-picker setters (`psi-emacs-set-model` and TUI submit handling) to use the canonical scope-carrying helper/API surface, or explicitly limit the acceptance to slash-command and RPC text surfaces only.
- [x] Clarify whether workflow-owned judge child sessions are in scope for the authoritative transient-model rule, since they are workflow-created child sessions but the design only explicitly names execution child sessions and ranked fallback switching.
- [x] Clarify whether the required workflow regression proof must assert both initial child-session model setup and fallback switching against persistence side effects, because current cited workflow tests only exercise fallback switching and do not yet name the initial concrete-model path as a persistence regression target.
