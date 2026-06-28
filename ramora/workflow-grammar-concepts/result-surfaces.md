# Result Surfaces

The grammar distinguishes between:

- step-local output surfaces
- step-local yielded value references
- the step's yielded value as a whole

## Output Surfaces

Output surfaces are addressed through `:output` selectors in source refs.

Examples include:

- `:data`
- `:summary`
- `:result`
- `:final-llm-reply`
- `:transcript`

Not every step form exposes every output surface.

For the first cut:

- invoke steps may expose `:data`, `:summary`, and optional `:result`
- session steps may expose `:final-llm-reply`, `:transcript`, and optional `:result`
- delegate steps do not re-export callee step-local outputs and do not add first-cut step-specific output surfaces beyond any future explicit delegate-local debug/result surface

A reference that selects an output not exposed by that step type is invalid.

## Per-Prompt Output Surfaces (`:prompt` Discriminator)

A multi-prompt session step (`:prompts`, see the grammar reference) exposes two
levels of output surface:

- **step-level** — the unqualified `{:step s :output k}` ref. `:final-llm-reply`
  is the **last** group's reply; `:transcript` is **accumulated** across every
  group's turn. This is back-compatible: a no-`:prompt` ref against a
  multi-prompt step resolves the step-level surface exactly as against a
  single-prompt step.
- **per-prompt** — `{:step s :prompt p :output k}` addresses named group `p`'s
  **turn-local** surface in step `s`: that group's own `:final-llm-reply` and
  `:transcript` for the turn it ran. `:prompt` is an optional discriminator on
  the canonical `{:step s :output k}` ref; it lives on the shared data-flow
  substrate and resolves uniformly wherever a source ref is admitted (invoke
  args, source contributions, template vars, delegated context).

A `:prompt` ref is invalid (reported fail-fast at workflow load / IR validation)
when any of the following hold:

- step `s` is **not** a `:session` step;
- step `s` is **single-prompt** (a `:contributions` step has no named-group
  namespace);
- `p` is **not** a declared group of `s`;
- `k` is **not** a per-prompt text surface (`:final-llm-reply` / `:transcript`)
  — structured/`:result` keys are not per-prompt-addressable;
- the ref targets the **same step being assembled** (a sibling-group ref,
  forward or back) — assembly-time refs cannot see a turn that has not run yet.

**Post-drain judge carve-out.** The one exception to the same-step rule is the
step's **own post-drain `:judge`**: it *may* address its prompt-groups via
`{:step s :prompt p :output k}`. The judge resolves **after the drain**, once
every group's turn is recorded, so the value is present and deterministic —
unlike an assembly-time contribution/template that would reference a sibling
turn that has not yet run.

`:prompt` is `:output`-only — a `:prompt` discriminator on a `:yield` ref
(`{:step s :prompt p :yield k}`) is **structurally** rejected (it matches neither
the `:output` ref shape nor the closed `:yield` ref shape), so the case is
unreachable rather than a semantic carve-out. The step's yielded value is
unchanged (text from the step-level `:final-llm-reply`).

## Yielded Value

The yielded value is the step's resulting value as a whole.

It is modeled through `:yields` as a tagged union.

Success forms:

- `{:type :data :data ...}`
- `{:type :text :text ...}`

Error form:

- `{:type :error :reason ... :message ... :details ...}`

This makes yielded values structurally exclusive rather than implicitly exclusive by map shape.

The default yielded-value composition by step form is:

- invoke step ⇒ yields data-oriented value sourced from its `:data` output surface
- session step ⇒ yields text-oriented value sourced from the `:final-llm-reply` output surface
- delegate step ⇒ yields the called workflow's yielded value unchanged

## Referencing Another Step's Yielded Value

A downstream step may reference a prior step's yielded value through `{:step ... :yield ...}`.

Examples:

- `{:from {:step "discover" :yield :data}}`
- `{:from {:step "report" :yield :text}}`
- `{:from {:step "review" :yield :reason}}`

This reference form addresses fields of the yielded tagged union, not step-local output surfaces.
