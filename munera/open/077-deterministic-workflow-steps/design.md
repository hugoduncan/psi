# 077 — Deterministic workflow steps

## Intent

Define the authoring model, extension contract, data-flow semantics, and runtime boundaries for deterministic workflow steps.

This is an umbrella design task. Its primary goal is to settle the developer experience before implementation slices are started.

The motivating use case is workflows that perform deterministic GitHub operations such as searching issues or PRs by label. That work should be performed directly by code or an extension-owned operation, produce structured data, and make that data available to downstream workflow steps without forcing the step through LLM prompt execution.

## Problem statement

The current workflow model is centered on agent/session-oriented steps:

- create or shape a child session
- submit prompt text
- observe transcript / accepted-result text
- route to the next step

That model is appropriate for planning, implementation, review, and judgment steps, but it is a poor fit for operations that are already deterministic.

Examples:

- search GitHub issues by label
- search PRs by label
- fetch PR metadata
- list changed files
- inspect CI state
- parse a structured handoff
- gather environment or repository facts

For these cases:

- prompt execution adds no value
- child-session construction is often the wrong abstraction
- the natural result is structured data, not assistant text
- downstream steps may need to read selected fields from that data rather than consume a lossy text rendering

Without a first-class deterministic-step model, workflow authors are pushed toward awkward workarounds:

- wrapping deterministic work in an agent prompt
- forcing structured results through Markdown/text handoffs
- hiding data transfer inside extension-specific conventions
- blurring the boundary between agentic and deterministic execution
- making workflows harder to author, reason about, and debug

## Why this needs an umbrella task first

Several important parts are still intentionally undecided and need to be explored together rather than patched one-by-one:

1. the workflow-file syntax for deterministic steps
2. how deterministic calls receive arguments
3. the extension-facing implementation contract
4. the result shape and data handoff semantics
5. how deterministic results flow into both inline-session/delegated and deterministic downstream steps
6. how inline session steps should be described once conversation assembly is made explicit
7. how all of this should align with existing workflow/statechart authoring patterns

If these are implemented piecemeal, the project risks landing a surface that works for one GitHub use case but is awkward or inconsistent for general workflow authoring.

## Desired outcome

A workflow author can declare one of three execution forms:

- a deterministic step that invokes a named operation with explicit arguments
- an inline session step that constructs a full child session inline
- a delegated step that invokes an existing named workflow with an explicit prompt string and caller-derived context

The resulting workflow model should make this distinction obvious:

- **deterministic step** — executes by calling a named deterministic operation and producing structured result data
- **inline session step** — executes by building and running a child session from inline configuration and conversation contributions
- **delegated step** — executes by delegating to an existing named workflow with a new prompt string plus forwarded context

The authoring experience should be clear enough that a workflow author can read a workflow file and understand:

- which execution form each step uses
- what operation a deterministic step will invoke
- what session an inline session step will construct
- what target a delegated step will invoke
- what explicit prompt string and forwarded context a delegated step passes
- what data earlier steps return
- how later steps consume earlier outputs

## Scope

In scope:

- define the canonical 3-form execution model for workflow steps
- define the canonical workflow authoring model for deterministic steps
- define the canonical inline-session construction model
- define the canonical delegated boundary model
- define how deterministic steps receive arguments
- define what delegated steps accept as explicit prompt string and forwarded context
- define the extension/runtime operation contract for deterministic steps
- define the canonical result shape for deterministic step execution
- define how deterministic results are referenced by downstream steps
- define how session contributions and delegated context reference workflow state and prior-step outputs
- define the minimum projection semantics needed for downstream consumption
- define how the three execution forms fit into workflow progression, attempts, statuses, and debugging surfaces
- compare alternative shapes and choose one clear direction
- identify implementation child tasks needed after design convergence
- include the motivating GitHub label-search use case as an anchor example

Out of scope:

- implementing the full runtime in this umbrella task
- arbitrary embedded scripting or general-purpose code execution inside workflow files
- redesigning all workflow authoring from scratch
- inventing a broad typed schema system unless a very small schema/story is clearly required
- solving every possible deterministic-integration use case before first-cut design convergence

## Design principles

- **Explicit over implicit** — deterministic execution and session conversation assembly should be visible in the workflow file.
- **Structured results first** — deterministic steps should not be forced through text as their canonical result.
- **Projection over transformation** — support selecting data, not arbitrary scripting.
- **Control flow separate from execution flow** — routing should remain distinct from execution configuration.
- **Execution flow separate from data flow** — what a step runs should remain distinct from how it references prior outputs.
- **Single clear execution boundary** — runtime owns invocation and session execution; workflow files describe them.
- **Minimal first cut** — prove the model with the GH label-search use case before broadening.
- **Coexistence, not replacement** — deterministic, inline-session, and delegated steps must all be first-class workflow building blocks.
- **Conversation assembly as reality** — inline session steps should be modelled in terms of the child-session conversation they assemble, not hidden prompt-input shortcuts.

## Vocabulary separation and naming review

This umbrella should keep five concerns visibly separate in the workflow vocabulary:

1. **control flow** — how steps transition
2. **deterministic execution** — what operation a deterministic step invokes
3. **inline session construction** — how an inline session step constructs a child session
4. **delegated boundaries** — what a delegated step invokes and passes across the boundary
5. **data flow** — how steps reference workflow state and prior-step outputs

### Control-flow vocabulary

Recommended control-flow terms:

- `:steps` — ordered authored step list
- `:name` — author-facing step identity
- `:judge` — route-deciding judge for a step when present
- `:on` — outcome-to-transition map
- `:goto` — target transition destination
- `:max-iterations` — explicit loop bound

These terms should continue to mean routing and progression, not payload or execution configuration.

### Deterministic-step vocabulary

Recommended deterministic-step terms:

- `:invoke` — explicit execution boundary for a deterministic call
- `:operation` — author-facing deterministic operation id
- `:args` — invocation argument map
- `:result` — runtime result envelope concept
- `:data` / `:summary` — logical result outputs exposed from that result

Important naming recommendation: avoid using `:deterministic` as the field that names the operation itself. `:deterministic` is useful as a category adjective, but `:operation` is the clearer noun for the thing being called.

### Inline-session vocabulary

Recommended inline-session terms:

- `:type :session` — inline session execution form
- `:model`, `:tools`, `:skills` — representative hoisted inline-session construction fields
- `:contributions` — ordered authored inputs used to assemble the child-session conversation

This replaces the older split among `:prompt`, `:input`, `:reference`, and `:preload` as canonical session authoring concepts.

### Delegated-step vocabulary

Recommended delegated-step terms:

- `:delegate` — explicit delegated execution boundary
- `:target` — existing named workflow to invoke
- `:prompt-string` — explicit new prompt string passed to the delegated workflow
- `:context` — caller-derived material forwarded to the delegated workflow

### Conversation-contribution vocabulary

Recommended contribution terms:

- `:type` — contribution kind tag
- `:source` — contribution type for sourced material
- `:template` — contribution type for authored text with interpolation
- `:text` — template text
- `:vars` — variable-name to ref map for a template contribution

### Data-reference vocabulary

Recommended shared data-reference terms:

- `:from` — source selector relation
- `:path` — projection within the selected source
- `:projection` — richer projection form where needed
- `:step` — named prior-step source
- `:output` — logical output channel from a prior step

Important naming recommendation: avoid using `:kind` for deterministic outputs. `:kind` risks collision with step kind / execution kind vocabulary. `:output` is clearer because it says exactly what is being selected from a prior step.

## Canonical workflow model

The leading end-state model now has three distinct execution forms, represented by an explicit step `:type`.

A step should have exactly one `:type`, and that `:type` determines which hoisted fields are valid on the step.

Current first-cut step types are:

- `:type :invoke`
- `:type :session`
- `:type :delegate`

These are mutually exclusive execution forms.

### First-cut field validity matrix

The first cut should validate step fields by `:type`.

| Step `:type` | Required fields | Optional fields | Forbidden execution fields |
| --- | --- | --- | --- |
| `:invoke` | `:name`, `:type`, `:operation`, `:args` | `:judge`, `:on`, `:max-iterations` | inline-session fields such as `:model`, `:tools`, `:skills`, `:contributions`; delegated fields `:target`, `:prompt-string`, `:context` |
| `:session` | `:name`, `:type`, `:contributions` | `:model`, `:tools`, `:skills`, other explicit inline-session construction fields, `:judge`, `:on`, `:max-iterations` | deterministic fields `:operation`, `:args`; delegated fields `:target`, `:prompt-string`, `:context` |
| `:delegate` | `:name`, `:type`, `:target`, `:prompt-string` | `:context`, `:judge`, `:on`, `:max-iterations` | deterministic fields `:operation`, `:args`; inline-session fields such as `:model`, `:tools`, `:skills`, `:contributions` |

Control-flow fields such as `:judge`, `:on`, and `:max-iterations` are shared across all step types when the workflow author needs routing on the step result.

### Deterministic step

A deterministic step invokes a named operation through the runtime using hoisted deterministic fields on the step.

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 :operation "github/search-issues-by-label"
 :args {:repo {:from :workflow-input :path [:repo]}
        :labels {:from :workflow-input :path [:labels]}
        :state "open"}}
```

### Inline session step

An inline session step constructs a full child session inline using hoisted session-construction fields, including the contributions used to assemble its conversation.

Illustrative shape:

```clojure
{:name "report"
 :type :session
 :model "gpt-5.4"
 :tools ["read" "bash"]
 :skills ["issue-feature-triage"]
 :contributions
 [{:type :source
   :from :workflow-original}
  {:type :template
   :text "Review these issues and produce a triage report:\n\n{{issues}}"
   :vars {:issues {:from {:step "discover" :output :data}
                   :path [:issues]}}}]}
```

### Delegated step

A delegated step invokes an existing named workflow and passes both explicit prompt string and caller-derived context using hoisted delegated-step fields.

Delegated first-cut boundary semantics:

- `:target` resolves against the existing named workflow definitions available to the runtime for the current project/worktree scope.
- `:prompt-string` becomes the delegated workflow invocation's local workflow input value.
- the delegated workflow should treat that local workflow input as the value available via `:workflow-input` within the callee.
- `:workflow-original` is per workflow invocation, not globally inherited from the root caller; for a delegated workflow, its local `:workflow-original` is the delegated invocation's own original request surface, derived from the delegated boundary rather than silently reaching back to the root workflow.
- `:context` is forwarded explicitly and remains separate from `:prompt-string`; it does not implicitly rewrite the delegated workflow's `:workflow-input`.
- first cut disallows delegated workflow session overrides.

Illustrative shape:

```clojure
{:name "report"
 :type :delegate
 :target "builder"
 :prompt-string "Review these issues and produce a triage report."
 :context [{:type :source
            :from :workflow-original}
           {:type :source
            :from {:step "discover" :output :data}
            :path [:issues]}]}
```

## Authoring syntax alternatives

### Option A — dedicated deterministic top-level block

Illustrative shape:

```clojure
{:name "discover"
 :deterministic {:operation "github/search-issues-by-label"
                 :args {:repo {:from :workflow-input :path [:repo]}
                        :labels {:from :workflow-input :path [:labels]}}}}
```

Advantages:

- very explicit that the step is deterministic
- easy to spot in a mixed workflow file
- easy to validate structurally

Disadvantages:

- creates a one-off shape that does not pair neatly with the inline-session shape
- weakens the clean distinction between execution boundary (`:invoke`) and execution target (`:operation`)
- makes broader authoring convergence harder

Assessment:

Readable, but less regular than the explicit `:type`-guided execution forms.

### Option B — general `:invoke` block

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 :operation "github/search-issues-by-label"
 :args {:repo {:from :workflow-input :path [:repo]}
        :labels {:from :workflow-input :path [:labels]}}}
```

Advantages:

- strongest match to statechart-style invoke/input/output thinking
- makes the execution boundary explicit: this step invokes something
- naturally accommodates explicit args as a sibling field
- leaves room for multiple invocation targets without inventing new top-level step forms each time
- separates invocation target from routing and dataflow clearly

Disadvantages:

- slightly more abstract than a dedicated `:deterministic` field

Assessment:

This remains the strongest conceptual fit.

### Option C — `:executor`, `:kind`, or `:mode`

Illustrative shape:

```clojure
{:name "discover"
 :kind :deterministic
 :operation "github/search-issues-by-label"
 :args {...}}
```

Advantages:

- makes step classification explicit
- can represent a broad taxonomy of step kinds

Disadvantages:

- describes classification more than invocation semantics
- risks internal taxonomy leaking into author-facing workflow syntax
- weaker fit for the now-clear `:invoke` / `:session` / `:delegate` split

Assessment:

Workable, but less crisp for authors.

## Current recommendation on authoring syntax

Current recommendation:

- all steps carry an explicit `:type`
- `:type :invoke` steps use hoisted `:operation` + `:args` fields
- `:type :session` steps use hoisted session-construction fields such as `:model`, `:tools`, `:skills`, and `:contributions`
- `:type :delegate` steps use hoisted `:target`, `:prompt-string`, and `:context` fields

This gives the authoring model one obvious 3-form execution split while making step exclusivity explicit in the authored data.

## Operation naming and registration alternatives

This umbrella should explicitly compare how deterministic operations are named and exposed by extensions/runtime code.

### Option 1 — single string operation id

Illustrative shape:

```clojure
{:type :invoke
 :operation "github/search-issues-by-label"
 :args {...}}
```

Advantages:

- compact and easy to read in workflow files
- gives one canonical author-facing handle for the operation
- keeps the workflow surface small

Disadvantages:

- packs ownership and operation identity into one token

Assessment:

Attractive for workflow ergonomics and likely sufficient if runtime owns a clear registry keyed by canonical operation id.

### Option 2 — explicit target map

Illustrative shape:

```clojure
{:type :invoke
 :target {:extension "github"
          :operation "search-issues-by-label"}
 :args {...}}
```

Advantages:

- makes ownership explicit
- easier to validate and explain

Disadvantages:

- more verbose in workflow files
- redundant if the runtime normalizes everything to a canonical id anyway

Assessment:

Clear but heavier.

### Option 3 — direct function/var-style reference

Illustrative shape:

```clojure
{:type :invoke
 :operation 'psi.github/search-issues-by-label
 :args {...}}
```

Advantages:

- looks close to an implementation target

Disadvantages:

- leaks code-layout details into workflow files
- couples workflow syntax to implementation structure
- weaker fit for extension registration and manifest-driven ownership

Assessment:

Too implementation-shaped for the intended author-facing surface.

## Current recommendation on operation identity

Current recommendation: prefer a stable author-facing canonical operation id such as `"github/search-issues-by-label"`.

Leading direction:

- workflows name operations by stable ids
- runtime owns a registry from operation id to extension-provided implementation
- extensions register operations declaratively against that registry
- workflow execution invokes registered operations through the runtime boundary rather than calling vars/functions directly from workflow files

## Argument-passing alternatives

This umbrella should compare how deterministic invocations receive inputs.

### Option 1 — single `:args` map

Illustrative shape:

```clojure
{:type :invoke
 :operation "github/search-issues-by-label"
 :args {:repo {:from :workflow-input :path [:repo]}
        :labels {:from :workflow-input :path [:labels]}
        :state "open"}}
```

Advantages:

- one obvious place for invocation input
- natural fit for deterministic operations that want named parameters
- supports mixed literal and projected values cleanly
- aligns with explicit invoke input maps

Disadvantages:

- does not distinguish a conceptual primary input from auxiliary parameters

Assessment:

This is the simplest and strongest default.

### Option 2 — split `:input` and `:args`

Illustrative shape:

```clojure
{:type :invoke
 :operation "github/search-issues-by-label"
 :input {:from :workflow-input :path [:labels]}
 :args {:repo {:from :workflow-input :path [:repo]}
        :state "open"}}
```

Advantages:

- preserves the idea that a step may have one primary upstream payload

Disadvantages:

- introduces two parallel payload channels for deterministic invocation
- encourages ambiguous API design
- reintroduces the very input-specialness the newer model is trying to avoid

Assessment:

Adds conceptual weight without enough benefit.

### Option 3 — positional/list-shaped args

Illustrative shape:

```clojure
{:type :invoke
 :operation "github/search-issues-by-label"
 :args [{:from :workflow-input :path [:repo]}
        {:from :workflow-input :path [:labels]}
        "open"]}
```

Advantages:

- compact in narrow cases

Disadvantages:

- poor readability
- weak self-documentation
- brittle as operation contracts evolve

Assessment:

Should not be the preferred workflow authoring model.

## Current recommendation on argument passing

Current recommendation: prefer a single explicit **map-shaped `:args`** surface.

Why:

- deterministic operations naturally want named parameters
- projected and literal values compose cleanly in one map
- it avoids introducing a second parallel input channel
- it aligns with the explicit-source/projection direction used elsewhere in the design

## Inline-session conversation assembly model

The canonical authoring model for inline session steps should be explicit conversation assembly.

An inline session step does not canonically have separate top-level `:prompt`, `:input`, `:reference`, and `:preload` fields. Instead it has ordered contributions that assemble the child-session conversation.

### Contribution type 1 — sourced contribution

A sourced contribution brings workflow-derived material into the child-session conversation.

Illustrative shapes:

```clojure
{:type :source
 :from :workflow-original}
```

```clojure
{:type :source
 :from {:step "discover" :output :data}
 :path [:issues]}
```

```clojure
{:type :source
 :from {:step "reproduce" :output :result}
 :projection {:type :tail :turns 4 :tool-output false}}
```

Requirements:

- sourced contributions reuse the existing workflow projection functionality
- they do not invent a separate projection mini-language
- sourced contributions preserve author order during materialization
- sourced contributions are materialized into the child-session conversation as sourced context material, not merged implicitly into template text unless a template contribution explicitly references the same source again

### Contribution type 2 — template contribution

A template contribution is authored text rendered with explicit interpolated refs.

Illustrative shape:

```clojure
{:type :template
 :text "Review these issues and produce a triage report:\n\n{{issues}}"
 :vars {:issues {:from {:step "discover" :output :data}
                 :path [:issues]}}}
```

Requirements:

- `:text` is rendered using the resolved values in `:vars`
- template contributions use `{{var}}`-style interpolation
- template variables reuse the same reference/projection language as deterministic args and source contributions
- duplicate use of the same template variable in one `:text` is allowed
- references used in `:vars` must resolve explicitly; unresolved variables are an error in the first cut rather than silently disappearing
- template contributions are materialized into the child-session conversation as authored instruction text

### No prompt-compatibility surface in the new model

This design prefers a clean break.

The canonical inline-session authoring surface is a `:type :session` step with hoisted session-construction fields including `:contributions`, not a compatibility layer over `:prompt` + `$INPUT`.

## What the assembled output is

The compiled/rendered output of inline-session conversation assembly is simply the child session conversation.

The design does not need a separate conceptual artifact beyond that. Contributions are the authoring surface; the resulting child-session conversation is what the runtime executes.

## Shared data-reference model

Deterministic args, sourced contributions, template vars, and delegated context should all reuse the same source/reference language.

For the first cut:

- delegated `:context` is an ordered vector of forwarded source-style items
- delegated `:context` preserves author order
- delegated `:context` reuses the same source/projection selectors as sourced contributions (`:from`, `:path`, optional richer `:projection`)
- delegated `:context` does not add template contributions in the first cut; keep delegated forwarding explicit and source-shaped until the boundary semantics are proven

Illustrative references:

```clojure
:workflow-input
:workflow-original
{:step "discover" :output :data}
{:step "discover" :output :summary}
{:step "discover" :output :result}
```

Illustrative projected reference:

```clojure
{:from {:step "discover" :output :data}
 :path [:issues]}
```

This keeps deterministic-step data flow and inline-session conversation assembly aligned.

## Downstream result-reference alternatives

This umbrella should compare how later steps refer to deterministic outputs.

### Option 1 — explicit output selectors

Illustrative shapes:

```clojure
{:from {:step "discover" :output :data}}
```

```clojure
{:from {:step "discover" :output :data}
 :path [:issues]}
```

```clojure
{:from {:step "discover" :output :summary}}
```

Advantages:

- keeps machine-readable payload distinct from human-readable summary text
- makes downstream intent explicit
- extends the shared reference style naturally

Assessment:

Strongest option.

### Option 2 — default output plus optional selectors

Illustrative shapes:

```clojure
{:from {:step "discover"}}
```

```clojure
{:from {:step "discover"}
 :path [:issues]}
```

Advantages:

- concise for the common case

Disadvantages:

- weaker explicitness about structured vs textual output semantics
- introduces ambiguity in mixed step kinds

Assessment:

Attractive ergonomically, but hides an important distinction.

### Option 3 — always reference the full result envelope

Illustrative shapes:

```clojure
{:from {:step "discover" :output :result}}
```

```clojure
{:from {:step "discover" :output :result}
 :path [:data :issues]}
```

Advantages:

- one uniform result envelope shape everywhere

Disadvantages:

- pushes envelope details into every consumer
- makes common authoring more verbose
- encourages dependency on runtime envelope mechanics

Assessment:

Too runtime-shaped for the normal author-facing surface.

## Current recommendation on downstream references

Current recommendation: use **explicit output selectors** with `:output :data` as the normal machine-readable output surface.

Leading direction:

- deterministic outputs expose at least:
  - `:output :data`
  - `:output :summary`
  - possibly `:output :result` for advanced/debug use, but not as the normal author-facing default
- downstream `:path` or richer `:projection` applies relative to the selected output
- deterministic steps, inline session steps, and delegated steps use the same reference/projection family when consuming deterministic outputs

## Step output surfaces

The design should make explicit what later steps can reference from each step form.

### Deterministic step outputs

Deterministic steps expose explicit logical outputs:

- `:output :data` — canonical machine-readable payload
- `:output :summary` — optional human-readable summary
- `:output :result` — optional full deterministic result envelope for advanced/debug use

### Inline session step outputs

First-cut inline session steps should expose at least:

- `:output :text` — the accepted/result text surface produced by the executed child session
- `:output :transcript` — the child-session transcript surface when transcript projection is requested
- `:output :result` may exist later if a normalized envelope proves necessary, but it is not required as a first-cut author-facing surface

### Delegated step outputs

Delegated steps should expose the outputs of the delegated workflow run through the delegated workflow's terminal result surface.

For the first cut, later steps should be able to reference at least:

- `:output :text` — the delegated workflow's accepted/result text surface
- `:output :result` may exist later if a normalized delegated envelope proves necessary, but it is not required as a first-cut author-facing surface

These output names must be validated by step type; not every output selector is valid for every step form.

## Canonical deterministic result model

The umbrella task should define one canonical deterministic-step result model.

Likely requirements:

- terminal status
- structured result data as the primary payload
- optional human-readable summary for logs/UI
- optional diagnostics/error data for failure cases

Illustrative shape only:

```clojure
{:status :succeeded
 :data {:issues [...]
        :count 4}
 :summary "Found 4 open issues with the requested labels."}
```

Important design rule: for deterministic steps, `:data` should be the canonical machine-readable output. Any text summary is secondary.

## Runtime and observability expectations

Although this is a design-first umbrella, it must define what runtime surfaces the eventual implementation should preserve.

Deterministic steps should participate visibly in:

- workflow run status
- step progression
- attempts/history
- debugging/introspection surfaces
- terminal result recording

The likely default is:

- no child session
- no prompt/transcript
- one explicit invocation record with input args and result payload

Inline session steps should continue to participate visibly through the child session they construct and run.

Delegated steps should participate visibly through the named workflow they invoke plus the explicit boundary payload they pass (`:prompt-string` and `:context`).

Across all three execution forms, the runtime should make the step's effective boundary/input surface inspectable: deterministic `:args`, inline-session `:contributions`, and delegated `:prompt-string` + `:context`.

## GitHub label-search anchor use case

Representative author goal:

1. search open GitHub issues with a target label
2. receive structured issue data
3. pass that data into either:
   - another deterministic step, or
   - an inline-session or delegated review/classification/report step

Representative example shape:

```clojure
{:steps [{:name "discover"
          :type :invoke
          :operation "github/search-issues-by-label"
          :args {:repo {:from :workflow-input :path [:repo]}
                 :labels {:from :workflow-input :path [:labels]}
                 :state "open"}}
         {:name "report-inline"
          :type :session
          :model "gpt-5.4"
          :contributions
          [{:type :source
            :from :workflow-original}
           {:type :template
            :text "Review these candidate issues and produce a triage report:\n\n{{issues}}"
            :vars {:issues {:from {:step "discover" :output :data}
                            :path [:issues]}}}]}
         {:name "report-call"
          :type :delegate
          :target "builder"
          :prompt-string "Review these candidate issues and produce a triage report."
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :output :data}
                     :path [:issues]}]}]}
```

This example is intentionally small. The umbrella should use it to judge whether the syntax feels clear and whether the extension contract is too heavy or too magical.

## Proposed end-state summary

### 1. Execution split

Preferred direction:

- all steps carry an explicit `:type`
- `:type :invoke` steps use hoisted `:operation` + `:args` fields
- `:type :session` steps use hoisted session-construction fields such as `:model`, `:tools`, `:skills`, and `:contributions`
- `:type :delegate` steps use hoisted `:target`, `:prompt-string`, and `:context` fields

### 2. Operation identity and registration

Preferred direction:

- workflow files use a stable author-facing operation id such as `"github/search-issues-by-label"`
- runtime owns a deterministic-operation registry keyed by those ids
- extensions register implementations against that registry
- workflow files do not reference vars/functions directly

### 3. Deterministic arguments

Preferred direction:

- deterministic invocation uses one explicit map-shaped `:args` surface
- `:args` values may be literals or projected values
- no separate positional or special primary-input channel exists by default

### 4. Inline session construction

Preferred direction:

- inline session steps author the child-session conversation explicitly through ordered `:contributions`
- inline session steps choose their model through a single `:model` field
- `:model` may be either an explicit model id or a model-selection query specification that reuses the existing model-selection query approach already used elsewhere in the system (for example auto-session-name)
- canonical contribution types are currently:
  - `:type :source`
  - `:type :template`
- contributions assemble directly into the child-session conversation
- there is no canonical `:prompt` / `$INPUT` compatibility surface in the new model

### 5. Delegated boundary

Preferred direction:

- `:type :delegate` steps use hoisted `:target`, `:prompt-string`, and `:context` fields
- `:target` names an existing workflow only
- a delegated step passes both:
  - `:prompt-string` — the explicit new prompt string for the delegated workflow
  - `:context` — caller-derived material forwarded across the boundary
- first cut disallows delegated workflow session overrides
- delegated context reuses the same reference/projection language as source contributions

### 6. Shared reference/projection model

Preferred direction:

- deterministic args, source contributions, template vars, delegated context, and query-shaped `:model` values should all reuse the same source/reference/query language where applicable
- likely sources include:
  - `:workflow-input`
  - `:workflow-original`
  - prior step outputs via `{:step "..." :output ...}`
- optional `:path` or richer `:projection` selects the needed view

### 7. Result model and downstream references

Preferred direction:

- deterministic steps produce a structured result envelope
- `:data` is the canonical machine-readable output
- `:summary` is optional and secondary
- output selectors are validated by step type
- downstream references use explicit output selectors such as:
  - deterministic: `:output :data`, `:output :summary`, optional `:output :result`
  - inline-session: `:output :text`, `:output :transcript`
  - delegated: `:output :text`, optional later `:output :result`

### 8. Runtime expectations

Preferred direction:

- deterministic steps do not create child sessions by default
- deterministic execution is still recorded in workflow progression/attempt history
- inline session steps execute by building and running the child-session conversation assembled from contributions
- delegated steps execute by invoking the target workflow with the explicit delegated boundary payload

### 9. Current open questions that remain after this summary

The main unresolved details are now narrower:

- exact registry/extension API shape for registering deterministic operations
- exact runtime representation of deterministic invocation attempts/results
- exact materialization details for sourced/template contributions inside the child-session conversation
- exact first-cut query-shaped `:model` shape and whether it is a strict reuse of the auto-session-name query surface or a normalized subset
- whether `:output :result` should be first-cut or deferred
- whether any light schema/contract declaration is needed for operation docs/validation

## Acceptance criteria for this umbrella task

This umbrella task is complete when it produces an unambiguous design, not when full runtime implementation lands.

Acceptance:

- the task defines the preferred 3-form execution model (`:type :invoke`, `:type :session`, `:type :delegate`)
- the task defines the preferred workflow authoring model for deterministic steps
- the task defines the preferred inline-session conversation-assembly model
- the task defines the preferred delegated boundary model (`:target` names an existing workflow; `:prompt-string` is string-only in the first cut; delegated workflow session overrides are disallowed)
- the task defines the preferred argument-passing model for deterministic invocation
- the task defines the preferred extension/runtime contract for deterministic operations
- the task defines the canonical deterministic result shape
- the task defines how downstream steps reference deterministic results
- the task defines how inline-session contributions and delegated context reference workflow state and prior-step outputs
- the task defines the first-cut output surfaces exposed by each of the three step types
- the task includes at least one GH label-search anchor example that exercises deterministic invocation and both inline-session and delegated downstream consumption
- the task identifies the follow-on implementation slices needed to build the feature safely
- the design is specific enough that a later implementation task does not need to reinvent the API surface

## Follow-on child tasks to create after design convergence

Expected children are likely to include some subset of:

1. workflow authoring/compiler support for deterministic step syntax
2. deterministic operation registry / extension contract
3. runtime execution support for deterministic steps
4. deterministic result recording and introspection surfaces
5. inline-session contribution compilation into child-session conversations
6. delegated boundary model and workflow invocation plumbing
7. step-output surface normalization and validation across `:invoke`, `:session`, and `:delegate`
8. shared source/reference/projection support across deterministic args, inline-session contributions, and delegated context
9. example workflow migration and documentation

The exact slice boundaries should be chosen only after the umbrella design is accepted.

## Risks

- overfitting the design to GitHub-specific operations
- accidentally inventing a hidden scripting language inside workflow files
- creating an operation contract that bypasses canonical runtime ownership
- forcing deterministic results back into text too early
- making inline-session conversation assembly too magical rather than explicit
- making downstream data reference semantics inconsistent across deterministic, inline-session, and delegated execution forms

## Notes

This task should continue to prefer one obvious path rather than preserving several permanently-equal options.
