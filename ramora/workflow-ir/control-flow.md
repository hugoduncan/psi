# Workflow IR — Control Flow

Control flow is orthogonal to execution form.

The IR uses:

- `:judge`
- `:on`
- `:max-iterations`

Transition directives inside `:on` additionally use:

- `:max-iterations` (transition-local loop bound)
- `:on-max-iterations` (optional exhaustion target; only valid alongside
  transition-local `:max-iterations`)

Illustrative shape:

```clojure
{:judge {...}
 :on {"APPROVED" {:goto :done}
      "REVISE" {:goto "build" :max-iterations 3 :on-max-iterations "summary"}}
 :max-iterations 5}
```

## Routing rules

- a judge produces one logical outcome value
- `:on` maps that outcome to a transition directive
- normalized IR requires `:on` when `:judge` is present, and requires `:judge` when `:on` is present
- if a selected transition goes to `:done`, the parent step's yielded value becomes the workflow result
- a judge routes; it does not replace the parent step's yielded value
- a transition directive may carry `:on-max-iterations` (a goto-target) only
  when it also carries transition-local `:max-iterations`; a directive with
  `:on-max-iterations` and no `:max-iterations` is rejected
- when a judged loop exhausts its transition-local `:max-iterations`: if the
  directive carries `:on-max-iterations`, the run routes to that target and
  continues (`:status :running`); if absent, the run hard-fails with
  `:reason :iteration-exhausted`

## Judge forms

The IR should normalize judge execution mode explicitly.

Current executed runtime support:

- `:type :llm`
- `:type :invoke`

### Runtime support note

`components/agent-session/src/psi/agent_session/workflow_judge.clj` executes both
prompt/session-based LLM judges and deterministic invoke judges. LLM judges create
a judge child session and route from the judge response. Invoke judges resolve
`:invoke :args` with the shared workflow source-resolution path, invoke the named
deterministic operation through the deterministic operation registry, and route
from the operation's returned data.

Therefore:

- `:judge {:type :llm ...}` is part of the current executed IR contract
- `:judge {:type :invoke :invoke {:operation ... :args ...}}` is part of the
  current executed IR contract and is used by built-in review workflows for
  deterministic `PASS_STATUS` and constant follow-up routing
- invoke-judge operation failures surface as judge/routing failures rather than
  falling back to LLM text matching

### LLM judge

Illustrative shape:

```clojure
{:type :llm
 :session {:model "gpt-5.4"
           :contributions [...]}
 :projection {:type :tail
              :turns 4
              :tool-output false}}
```

An LLM judge may declare judge-local structured outputs. The `:outputs` map is
local to the judge result, not to the parent step's ordinary text outputs.

Illustrative normalized structured judge shape:

```clojure
{:type :llm
 :session {:model "gpt-5.4"
           :contributions [...]}
 :outputs {:review
           {:source :judge/structured-output
            :mode :structured
            :schema-id :psi.workflow/judge-review-result
            :schema-version 1
            :schema [:map
                     [:decision [:enum :clear :needs-work :unclear]]
                     [:issues
                      [:vector
                       [:map
                        [:severity [:enum :blocking :minor]]
                        [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
                        [:description :string]
                        [:evidence :string]
                        [:suggested-change :string]]]]
                     [:confidence [:double {:min 0.0 :max 1.0}]]]}}
 :projection {:type :tail
              :turns 4
              :tool-output false}}
```

Judge structured outputs are intended for judge routing, retry decisions, and
review-loop control data. They do not replace the parent step's yielded value; a
judge routes while the parent step still yields according to its own `:yields`
form. Judge-local output keys are not implicitly promoted into the parent step's
step-local `:outputs` map.

### Invoke judge

Illustrative shape:

```clojure
{:type :invoke
 :invoke {:operation "workflow/classify-result"
          :args {:result {:from {:step "build" :output :data}}}}}
```

## Judge outcome contract

All judge forms normalize to one logical outcome value.

That outcome:

- may be a string or keyword
- is matched exactly against the keys in `:on`
- is case-sensitive for strings
- does not auto-coerce between strings and keywords
