# Workflow IR — Step Forms

The IR has three step execution forms:

- invoke
- session
- delegate

Each step has exactly one execution `:type`.

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 ...}
```

### Common step fields

All step forms share these conceptual fields:

- `:name`
- `:type`
- optional `:outputs`
- optional `:yields`
- optional `:judge`
- optional `:on`
- optional `:max-iterations`

The IR should validate execution-specific fields according to step type.

Important alignment rule:

- the authored target grammar hoists execution-specific fields directly onto the step
- the IR groups those fields under one execution-specific key such as `:invoke`, `:session`, or `:delegate`

This keeps authored syntax compact while giving runtime one explicit normalized place for execution payload.

## Invoke step

An invoke step executes a deterministic operation.

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 :invoke {:operation "github/search-issues-by-label"
          :args {:repo {:from :workflow-input :path [:repo]}
                 :labels {:from :workflow-input :path [:labels]}
                 :state "open"}}
 :outputs {:data {:source :invoke/data}
           :summary {:source :invoke/summary}
           :result {:source :invoke/result}}
 :yields {:type :data
          :data :data}}
```

### Invoke semantics

- `:operation` is a canonical runtime operation id
- `:args` is a fully normalized named-argument map
- the operation runs without child-session construction
- canonical machine-readable output is `:data`
- optional human-readable output is `:summary`
- optional debug/result envelope output is `:result`

## Session step

A session step constructs and runs a child session inline.

Illustrative shape:

```clojure
{:name "report"
 :type :session
 :session {:model "gpt-5.4"
           :tools ["read" "bash"]
           :skills ["issue-feature-triage"]
           :contributions [{:type :source
                            :from :workflow-original}
                           {:type :template
                            :text "Review these issues:\n\n{{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}}}]}
 :outputs {:final-llm-reply {:source :session/final-llm-reply}
           :transcript {:source :session/transcript}
           :result {:source :session/result}}
 :yields {:type :text
          :text :final-llm-reply}}
```

### Session semantics

- `:session` contains the effective child-session construction data
- `:contributions` are ordered and preserved as authored
- the assembled result of contributions is the child-session conversation
- canonical first-cut session output is `:final-llm-reply`
- transcript output is optional but normalized when present
- structured machine-facing output is declared under step-local `:outputs` with `:source :session/structured-output`

#### Prompt source: `:contributions` vs `:prompts`

A session step's prompt source is normalized at IR time into one internal
prompt-queue representation, regardless of authoring form:

- a `:contributions` (or `:prompt-workflow`) session step normalizes to a
  **single unnamed** prompt-group — the degenerate one-turn queue shown above;
- a `:prompts` session step normalizes to an **ordered queue of named**
  prompt-groups, each run as its own model turn in the same shared child
  session, drained in author order by `drive-session-prompt-queue!`.

The two authoring forms are mutually exclusive on a single session step. See
[`doc/workflow-grammar.md`](../workflow-grammar.md) *Multi-prompt session steps
(`:prompts`)* for the author-facing `:prompts` form, precedence/validation
rules, per-prompt output surfaces, and drain/resume/abort semantics.

### Session structured outputs

A session step may declare at most one structured output entry under its
step-local `:outputs` map. The output key is the logical machine-facing value
that downstream references address. Authors who need multiple machine-facing
fields should model them as fields inside that one structured value.

Illustrative normalized shape:

```clojure
{:name "classify-reproduction"
 :type :session
 :session {:contributions [...]}
 :outputs {:classification
           {:source :session/structured-output
            :mode :structured
            :schema-id :psi.workflow/bug-reproduction-classification
            :schema-version 1
            :schema [:map
                     [:status [:enum :reproducible :not-reproducible :unclear]]
                     [:summary :string]
                     [:evidence [:vector :string]]
                     [:commands-run [:vector :string]]
                     [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]}}
 :yields {:type :data
          :data :classification}}
```

Session structured outputs are optional. If omitted, the session step remains a
text-producing step and should expose `:final-llm-reply` when downstream text
consumption is needed.

## Delegate step

A delegate step invokes another workflow through an explicit workflow boundary.

Illustrative shape:

```clojure
{:name "report-call"
 :type :delegate
 :delegate {:target "builder"
            :session {:session-profile :coding
                      :model "gpt-5.5"
                      :thinking-level :medium}
            :prompt-string {:type :template
                            :text "Review these issues:\n\n{{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "discover" :output :data}
                       :path [:issues]}]}
 :yields {:type :delegated}}
```

### Delegate semantics

- `:target` resolves to a named workflow definition
- `:prompt-string` must render to a final string before invocation
- `:context` is ordered forwarded material and is optional
- the delegated workflow's local `:workflow-input` becomes the rendered prompt string
- the delegated workflow's local `:workflow-original` is rebound for the delegated invocation
- by default, a successful step yields the delegated workflow's yielded value unchanged
- when the delegated workflow fails, the step exposes the canonical bounded and safely redacted failure message; arbitrary callee execution details, results, provider/session data, and transcripts remain runtime/debug-only
- delegated failure remains a failed step and produces no accepted result; successful delegated yield and handoff contracts are unchanged
- first cut does not re-export the callee workflow's step-local output surfaces through downstream `{:step ... :output ...}` refs against the delegate step itself
- optional `[:delegate :session]` config contains only `:session-profile`, `:model`, and `:thinking-level` in the current IR
- delegate session config shapes the concrete `:inherited-defaults` snapshot passed to the delegated run; it does not construct a delegate actor session for the delegate step itself
- profile-derived `:speed-mode` and `:effort-override` may flow into the delegated run through that inherited-defaults snapshot, but direct authored delegate session config does not accept `:speed-mode` or `:effort-override`
