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
