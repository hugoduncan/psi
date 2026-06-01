# Design follow-up steps — architecture-fit review

- [x] Resolve the effect-vs-state kind mismatch: prompt templates are
      canonical session state, not an external runtime handle, so the
      `model-registry/reload` effect analogy does not transfer. Specify the
      reload as a pure handler returning `:root-state-update` that sets
      `:prompt-templates` to the freshly discovered vector (handler performs
      the discovery IO), OR explicitly justify and document an effect that
      writes canonical state via a second apply (the `mark-flushed`
      precedent) as a deliberate exception. Remove the "mirror
      model-registry effect shape" framing as the default.
- [x] Correct the replay-fidelity rationale: replay suppresses effects, so
      effect-modeled discovery does NOT replay the template set. Drop the
      "effect for replay fidelity" justification and instead state plainly
      that file-IO-derived template state is non-replay-deterministic for
      whichever shape is chosen, and that replay preserves the last applied
      `:prompt-templates` value (state application is preserved on replay).

## Ambiguity-review follow-ups (2026-06-01)

- [x] A1: Pin the full `discover-templates` opts map reload passes, including
      whether `:global-prompts-dir` is explicit or defaulted, and explicitly
      state that worktree-derived `:project-prompts-dir` intentionally diverges
      from startup's process-relative default (relevant for worktree sessions
      where cwd ≠ worktree).
- [x] A2: Correct the `--prompt-template` extra-paths premise — the flag is not
      implemented and startup passes no `:extra-paths`, so there is nothing to
      persist on reload. Reframe the "Why" source list and OQ#2 accordingly
      (reload omits extra-paths; note flag is unimplemented).
- [x] A3: Resolve the diagnostics either/or: either declare prompt discovery has
      no diagnostics channel and drop `:diagnostics` from the return shape and
      the AC4 command summary, or specify minimal error capture. Do not leave
      both options open.
- [x] A4: Resolve the replace-handler shape — specify that
      `:session/reload-prompts` itself computes the freshly discovered vector
      and returns a `:root-state-update` replacing `:prompt-templates` (mirroring
      `set-skills`/`set-active-tools`), rather than leaving a dedicated
      `set-prompt-templates`-vs-loop "preference" open.
- [x] A5: Fix the exact `reload-prompts` mutation `::pco/output` set (e.g.
      `:psi.prompt-template/reloaded?` + `:psi.prompt-template/count`, mirroring
      `add-prompt-template`) in the Return shape / AC5 and remove the
      non-committal "e.g.".
- [x] A6: State explicitly whether `:session/reload-prompts` emits
      `:runtime/refresh-system-prompt`. Templates are `/name`-invoked and not
      enumerated in the system prompt, so the default answer is "no refresh
      needed" — confirm and document, or justify a refresh if a template-listing
      surface requires it.
