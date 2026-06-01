# Design follow-up steps — 199

## Ambiguity review (2026-06-01)

- [x] A1: Resolve the sharing mechanism. Current compiler wires shared `.md`
      `{{var}}` tokens only to the `.md`'s own frontmatter `vars:` (bound to
      `:workflow-input`/`:workflow-original`) + `standard-vars`; a referencing
      `.edn` step cannot inject a per-step constant profile. Pin down a concrete,
      grammar-supported mechanism in `design.md` (e.g. one `.md` per profile, a
      delegated follow-up sub-workflow, or profile carried via `:workflow-input`),
      and remove the infeasible "per-step constant `:vars`" recommendation.
- [x] A2: Reconcile parameterization with the "no workflow-engine/grammar
      changes" out-of-scope boundary. State explicitly in `design.md` whether the
      chosen mechanism stays within current grammar; if it needs compiler/grammar
      work, either move that in-scope or pick a mechanism that does not.
- [x] A3: Define the `steps` profile artifact scope unambiguously. The current
      `review-step` follow-up reads/updates `design.md` too; the design table
      omits it. State in `design.md` whether the unified `steps` profile includes
      `design.md` (and if dropped, flag it as an intentional behaviour change).
- [x] A4: Specify whether the unified follow-up preserves the plan follow-ups'
      "do not execute items that predate the preceding review pass" clause. If
      dropped for the plan/steps profile, flag it as an intentional behaviour
      change in `design.md`.

## Inconsistency review (2026-06-01)

- [x] I1: Reconcile the "aspect-agnostic / cosmetic" framing with the actual
      per-aspect follow-up prompts, which name the *specific* preceding review
      step ("preceding ambiguity-review pass" vs "preceding inconsistency-review
      pass"). A single shared profile follow-up `.md` is referenced by both the
      ambiguity-follow-up and inconsistency-follow-up host steps and cannot name
      one step. State in `design.md` that the shared follow-up uses generic
      "preceding review pass" wording (no named review step), that this is the
      deliberate generalization of the current named references, and correct the
      claim that the aspect mention is purely "cosmetic" (the named-step
      reference is functional: it identifies which review step's just-added
      items to execute).
