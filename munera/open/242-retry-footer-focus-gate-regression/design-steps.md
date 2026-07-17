# Design steps

- [ ] Ambiguity: reconcile acceptance criterion 1's unconditional
      "failing-then-passing end-to-end test" for the **focused** session with the
      Approach/Context branch that the focused case may be "working as intended"
      (only background/delegated sessions affected). If the focused case already
      works, such a test would pass immediately and never fail first. Clarify
      whether AC1's failing-then-passing requirement is mandatory in all outcomes,
      or contingent on the focused-session branch being the actual regression —
      and what proof AC1 demands in the "working as intended" outcome.
