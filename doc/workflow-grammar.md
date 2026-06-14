# Workflow Grammar

This document describes the supported workflow authoring grammar for the
deterministic-workflow-step design.

Higher-order workflow references are supported in a narrow, explicit form:
- workflows remain canonical named definitions
- dynamic delegation happens only through `:type :delegate`
- dynamic `:target` reuses canonical `source-spec` shape
- resolved dynamic targets must be explicit workflow references of the form
  `{:type :workflow-ref :name "builder"}`
- plain strings remain the static authored delegate-target form only

It documents the author-facing `:type :invoke | :session | :delegate` model.
For the conceptual explanation of this design, see
`doc/workflow-grammar-concepts.md`.

```clojure
workflow ::= workflow-map

workflow-map ::= {:steps [step+]
                  terminal-contract?}

terminal-contract ::= :terminal-contract {:handoff {:type :markdown-handoff-data}}

step ::= invoke-step | session-step | delegate-step

invoke-step ::= {:name step-name
                 :type :invoke
                 :operation operation-id
                 :args arg-map
                 yields?
                 control-flow*}

session-step ::= {:name step-name
                  :type :session
                  session-config-entry*
                  (:contributions [contribution+] | :prompts [prompt-group+])
                  outputs?
                  yields?
                  control-flow*}

prompt-group ::= {:name prompt-name
                  (:prompt-workflow relative-md-path | :contributions [contribution+])}

delegate-step ::= {:name step-name
                   :type :delegate
                   :target (workflow-name | source-spec)
                   delegate-session-config-entry*
                   :prompt-string (string | template-contribution)
                   :context? [source-item*]
                   outputs?
                   yields?
                   control-flow*}

control-flow ::= :judge judge-spec
               | :on outcome-map
               | :max-iterations pos-int

judge-spec ::= llm-judge | invoke-judge

llm-judge ::= {:type :llm
               judge-session-config-entry*
               :contributions [contribution+]
               outputs?}

invoke-judge ::= {:type :invoke
                  :operation operation-id
                  :args arg-map}

outcome-map ::= {outcome transition-map}+

transition-map ::= {:goto goto-target
                    max-iterations-clause?}

max-iterations-clause ::= :max-iterations pos-int

goto-target ::= :next | :previous | :done | step-name

contribution ::= source-contribution | template-contribution

source-contribution ::= {:type :source
                         :from source-ref
                         source-projection?}

template-contribution ::= {:type :template
                           :text string
                           :vars {var-name source-spec}*}

source-item ::= {:type :source
                 :from source-ref
                 source-projection?}

source-spec ::= {:from source-ref
                 source-projection?}

For delegate targets:
- `:target "builder"` is the static form
- `:target {:from ... :path [...]}` is the dynamic higher-order form
- dynamic target resolution must produce a `workflow-ref`
- free-form text and plain strings are not valid dynamic workflow-reference values

source-projection ::= :path path
                    | :projection projection

source-ref ::= :workflow-input
             | :workflow-original
             | {:step step-name :output output-key}
             | {:step step-name :yield yield-field}

outputs ::= {output-key output-spec}+

output-spec ::= delegate-handoff-output
              | session-text-output
              | session-structured-output
              | judge-structured-output

delegate-handoff-output ::= {:source :delegate/handoff}

session-text-output ::= {:source :session/final-llm-reply}

session-structured-output ::= {:source :session/structured-output
                               :mode :structured
                               schema-contract
                               invalid-policy?}

judge-structured-output ::= {:source :judge/structured-output
                             :mode :structured
                             schema-contract
                             invalid-policy?}

schema-contract ::= :schema-id keyword
                  :schema-version pos-int
                  :schema malli-schema
                  :json-schema json-schema-map?
                  :strategy-preference (:provider-native | :prompted-json)?
                  :fallback (:prompted-json | :none)?
                  :require-provider-native? boolean?

invalid-policy ::= :on-invalid {:action :fail-fast}
                 | :on-invalid {:action :retry
                                :max-attempts pos-int}

output-key ::= keyword

yield-field ::= keyword

arg-map ::= {keyword (literal | source-spec)}*

session-config-entry ::= :session-profile keyword
                       | :model model-selection-spec
                       | :tools [tool-id*]
                       | :skills [skill-id*]
                       | :thinking-level (:off | :minimal | :low | :medium | :high | :xhigh)
                       | :temperature double   ;; optional, range [0.0, 2.0]; absent = provider default
                       | session-config-extension

delegate-session-config-entry ::= :session-profile keyword
                                | :model model-selection-spec
                                | :thinking-level (:off | :minimal | :low | :medium | :high | :xhigh)

;; Delegate session config shapes the delegated run's concrete inherited
;; defaults; it does not construct a delegate actor session. Direct authored
;; :speed-mode/:effort-override keys are intentionally not part of this grammar;
;; they can flow from resolved session profiles.

judge-session-config-entry ::= :model model-selection-spec
                             | :tools [tool-id*]
                             | :skills [skill-id*]
                             | :temperature double   ;; optional, range [0.0, 2.0]; absent = provider default
                             | judge-session-config-extension

model-selection-spec ::= external-nonterminal-defined-in-doc-model-selection-grammar

yields ::= {:type :data :data output-keyword}
         | {:type :text :text output-keyword}
         | {:type :error :reason keyword :message string :details? map}
         | {:type :delegated}

output-keyword ::= keyword

step-name ::= string
workflow-ref ::= {:type :workflow-ref
                 :name workflow-name}

workflow-name ::= string
operation-id ::= string
tool-id ::= string
skill-id ::= string
var-name ::= string
outcome ::= string | keyword
path ::= vector
projection ::= map
literal ::= string | keyword | number | boolean | nil | vector | map
malli-schema ::= vector | map | keyword
pos-int ::= integer
map ::= clojure-map
vector ::= clojure-vector
string ::= clojure-string
keyword ::= clojure-keyword
number ::= clojure-number
boolean ::= true | false
nil ::= nil
```

## Multi-prompt session steps (`:prompts`)

A session step authors **either** a single-prompt `:contributions` body **or**
an ordered `:prompts` queue of named prompt-groups — never both. The two forms
share one internal prompt-queue mechanism; single-prompt `:contributions` is the
N=1 degenerate (one unnamed group). The distinction is per-prompt **addressing
capability**, not behaviour: author `:prompts` when you want multiple turns in
one shared session or named per-prompt addressing; otherwise use
`:contributions`.

```clojure
{:name "design-review"
 :type :session
 :tools ["read"]                                ; session config is per-step, shared by all groups
 :prompts
 [{:name "architecture" :prompt-workflow "review-architecture.md"}
  {:name "ambiguity"    :contributions [{:type :template :text "..." :vars {}}]}]}
```

Each prompt-group materializes to one submitted prompt that runs one model turn
against the **same** child session, in author order; the next group's turn is
submitted only after the prior turn completes. Session config (`:model`,
`:tools`, `:skills`, …) is declared once at the step level and shared by every
group — there is no per-prompt model/tools/skills, and there is no step-level
shared `:contributions` preamble (the first group loads shared sources on turn
1; later groups see them via the live session's conversation memory).

Precedence and validation rules:

- **Step-level precedence** — `:contributions`/`:prompt-workflow` **xor**
  `:prompts`. Declaring both on one session step is an error; a session step
  must declare exactly one prompt source.
- **Group-internal precedence** — within a prompt-group the body is
  `:prompt-workflow` **xor** `:contributions` (mirroring the step-level rule):
  declaring both, or neither, is an error.
- **Non-empty** — an empty `:prompts` vector is rejected; a one-element
  `:prompts` is valid (and runs the multi-prompt path with per-prompt
  addressing, distinct from the `:contributions` single-prompt form).
- **Named, unique within a step** — every `:prompts` group is named, and group
  names are unique within a step (`(step-name, prompt-name)` is the addressing
  handle). Names may repeat across different steps.

All of these rules are reported fail-fast at workflow load / IR validation.

### Later-group single-submission limitation

Each prompt-group submits **one** message per turn. The **first** group's
materialized conversation is split into preloaded messages plus a final prompt,
and the preloaded messages are injected when the shared child session is spawned
— so a multi-message first group is honoured in full. **Later** groups, however,
submit **only** their final message: a later group's body materializes against
the already-live session and is split the same way, but its preloaded (non-final)
messages are **not** re-injected mid-session. Later groups instead rely on the
live session's conversation memory for shared context (the first group's loaded
sources persist across turns).

Consequently, a later prompt-group whose `:contributions` materialize to **more
than one message** silently drops every non-final message. Author multi-message
bodies as the first group, or keep later groups to a single submission — the
common `:prompt-workflow` (single user message) form always satisfies this.

### Drain and routing

The prompt-queue **drains** before the step routes: every group's turn runs (in
author order) and is recorded, and only then does the step's single post-drain
result — and any step `:judge`/`:on` routing — run. A multi-prompt step is still
**one** workflow step with **one** routing decision; the N turns are internal to
that step, not separate steps. Concretely:

- The step's `:final-llm-reply` is the **last** group's reply; its `:transcript`
  is **accumulated** across every group's turn.
- A declared step-level structured `:output` is requested on the **final** turn
  only.
- Each **named** group additionally records a per-prompt turn record (its own
  turn-local `:final-llm-reply`/`:transcript`), so completed turns are
  introspectable; the unnamed single-prompt (`:contributions`) degenerate
  records only the step-level rollup. Other steps (and the step's own post-drain
  `:judge`) address a named group's turn-local surface via the `:prompt`
  source-ref discriminator — see *Per-prompt output surfaces* in
  [workflow-grammar-concepts.md](workflow-grammar-concepts.md).

### Resume and idempotency

The queue's position is reconstructed **purely from the recorded per-prompt turn
records**, never from an in-memory counter. The realized guarantee is a
**structural progression guard**: on **every** iteration the queue-driving loop
re-reads which group indices already have a recorded turn and submits the
**lowest un-run** group next, so the next prompt is derived from recorded
progression alone. A prompt whose turn record already exists is **never**
re-submitted, so its non-deterministic model turn (`ai/generate`) never re-fires.

This makes a multi-prompt step idempotent: re-driving a partially recorded queue
runs only the remaining un-run prompts and reproduces the same ordered per-prompt
records, and re-driving a fully recorded queue runs **zero** turns and proceeds
straight to the single post-drain result/route. The post-drain route is reached
only once every group has a recorded turn. The idempotency property is validated
by re-driving against a **reconstructed** queue state — the same observable an
async restart or replay would produce.

> **Realized vs. target.** As built the drain is **synchronous**: the whole
> queue drains inside one step action with no mid-drain suspend, so an async
> turn-completion resume, a process restart, or an event-log replay re-entering
> mid-drain is a **not-yet-realized target**, not an occurring runtime path. What
> *is* realized today is the structural progression guard above — the per-iteration
> re-read of recorded per-prompt progression. The async suspend/resume contract
> (continue-from-progression across an actual process restart / replay) is the F1
> target the synchronous drain stands in for; the progression guard is exactly
> the mechanism a future async resume would reconstruct from.

### Abort, cancellation, and blocked outcomes

When a turn does not complete successfully the queue stops and the step routing
is **skipped** — the drain never produces a successful post-drain result. The
per-prompt turn records of groups that completed **before** the aborting turn are
retained and introspectable; the aborting group itself leaves **no** completed
turn record. There are three non-success terminal outcomes:

- **`:failed`** — a turn errors. The failure payload names the failing prompt
  (`:failed-prompt {:index … :name …}`). An error at **any** position — including
  the **last** prompt — follows the same `:failed` abort. The single-prompt
  (`:contributions`) degenerate fails the same way, with no prompt name (no named
  group), byte-equivalent to today's single-prompt failure.
- **`:cancelled`** — the run is cancelled. Whether the cancel lands between turns
  or while a turn is in flight, the outcome is the same terminal `:cancelled`; an
  interrupted in-flight prompt leaves no record, and only prompts completed
  before the cancel are retained.
- **`:blocked`** — structured-output viability fails. An invalid structured-output
  **request** is checked **upfront before turn 1** (fail-fast: zero turns run,
  zero records). An `:unsupported-structured-output` or `:invalid-structured-output`
  block can only arise on the **final** turn (structured output is requested on
  the final turn only), yielding a terminal `:blocked` after the prior turns ran
  and were recorded, with the blocking final prompt leaving no record.

## Structured outputs

Session steps may declare machine-facing structured outputs under the existing
step-local `:outputs` map. LLM judges may declare judge-local structured
outputs under their own `:outputs` map. Delegate `:outputs` remain supported
for handoff data; `outputs` is no longer delegate-only.

Structured outputs use `:source :session/structured-output` for ordinary
session step output and `:source :judge/structured-output` for LLM judge
output. Both forms require `:mode :structured` plus a Malli-compatible schema
contract (`:schema-id`, `:schema-version`, and `:schema`). Provider-native and
prompted-JSON request shaping also require an explicit `:json-schema`; the
runtime does not derive JSON Schema from Malli. Authors may set
`:strategy-preference :provider-native`, `:fallback :prompted-json` or `:none`,
and `:require-provider-native? true`. Omitted strategy defaults to native-first
with prompted-JSON fallback. A session step may have at most one session
structured-output entry, and an LLM judge may have at most one judge
structured-output entry. Authors who need multiple machine-facing values should
group them as fields inside one structured map schema and address fields with
`:path`.

Downstream references to session structured outputs use the normal source-spec shape:

```clojure
{:from {:step "classify-reproduction" :output :classification}
 :path [:next-action]}
```

The path is resolved against the validated structured `:value`, never by
parsing prose. If the source output is missing, non-structured, invalid, or the
path is absent, resolution fails clearly.

Judge structured outputs are judge-local in this slice. They are available to
the judge result and transition evaluation, but are not implicitly promoted into
the parent step's `{:step ... :output ...}` namespace. A later step that needs
the same data must consume an explicitly declared session structured output or a
future explicit promotion/export contract, not a hidden judge-output ref.

Prompted fallback means the AI adapter injects schema-guided JSON-only
instructions into the provider request for one JSON value matching the declared
JSON Schema for the one declared structured-output key. Workflow runtime then
parses the returned text and schema-guided coercion maps JSON object keys and
enum strings into the declared Malli-domain values when the value is an object,
and also validates scalar, array, boolean, number, string, and `null` values when
the schema allows them. Raw text is retained even when coercion and validation
succeed. Provider-native structured output likewise requests one
schema-constrained JSON value and records it behind the single declared
structured-output key. If native support is required or fallback is `:none`, an
unsupported resolved model/transport fails with `:unsupported-structured-output`
instead of retrying as prose. Authors who need multiple named fields or
`:path`-addressable subvalues should use a map/object schema for that one JSON
value.
