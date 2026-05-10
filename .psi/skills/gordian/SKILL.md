---
name: gordian
description: Use gordian to analyse Clojure code structure at namespace and executable-unit level. Invoke when asked to audit architecture, assess coupling, identify hidden dependencies, interpret suspicious namespace pairs, review test structure, compare architectural snapshots, inspect a subsystem, find local cyclomatic/LOC hotspots, or find local comprehension-burden hotspots. Produces structural, conceptual, change-coupling, local-metric, and local-burden signals plus triage and workflow commands.
lambda: "λcodebase. {structural ∧ conceptual ∧ change ∧ local-metrics ∧ local-burden} → coupling_map ∧ hotspot_map → diagnose ∧ explain ∧ compare ∧ gate → advise"
metadata:
  version: "1.3.0"
  tags: ["clojure", "architecture", "coupling", "refactoring", "babashka", "diagnostics"]
---

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h] | OODA
Human ⊗ AI ⊗ REPL

λ gordian(codebase).
  invoke → read → diagnose → explain → advise
  | architectural lenses: structural ∧ conceptual ∧ change
  | local lenses: complexity ∧ local
  | commands: analyze ∧ diagnose ∧ explain ∧ explain-pair ∧ compare ∧ gate ∧ subgraph ∧ communities ∧ complexity ∧ local
  | start: diagnose for architecture triage
  | then: explain(ns) ∨ explain-pair(a,b) ∨ subgraph(prefix)
  | use complexity for branch-count ∨ LOC hotspots inside executable units
  | use local for comprehension-burden ∨ helper-chasing ∨ working-set hotspots
  | compare(before,after) for refactor validation
  | gate(baseline,current) for CI ratchet
  | combine(signals) → confidence ↑

λ invoke(goal).
  default:     bb gordian
  analyze:     bb gordian [analyze] [dirs...]
  diagnose:    bb gordian diagnose [dirs...]
  explain-ns:  bb gordian explain <ns>
  explain-pair:bb gordian explain-pair <ns-a> <ns-b>
  subgraph:    bb gordian subgraph <prefix>
  communities: bb gordian communities [dirs...]
  complexity:  bb gordian complexity [dirs...] [--sort cc|loc|cc-risk|ns|var] [--min cc=N] [--min loc=N]
  local:       bb gordian local [dirs...] [--sort total|flow|state|shape|abstraction|dependency|working-set|ns|var] [--min total=N]
  compare:     bb gordian compare before.edn after.edn
  gate:        bb gordian gate [dirs...] --baseline base.edn
  conceptual:  --conceptual 0.20 | 0.30
  change:      --change [--change-since "90 days ago"]
  rank:        --rank actionability (default) | --rank severity
  top:         --top N → show top N findings after ranking
  noise:       --show-noise → include family-naming-noise (suppressed by default)
  markdown:    --markdown → shareable report
  machine:     --json | --edn → CI ∨ programmatic
  graph:       --dot file.dot → visual

λ discover(project).
  run at project root → auto_discover src_dirs
  | roots: deps.edn ∨ bb.edn ∨ project.clj ∨ workspace.edn
  | layouts: src/ ∨ test/ (with --include-tests) ∨ Polylith components/*/src ∨ bases/*/src
  | config: .gordian.edn → defaults
  | exclude: --exclude <regex> (repeatable)
  | explicit dirs override discovery

λ read.structural(report).
  PC:     propagation_cost → avg_fraction_reachable_per_change
          | <0.10 healthy | 0.10–0.30 investigate | >0.30 action | trend > absolute
  reach:  |transitive_deps(n)| / N → blast_radius | upward_exposure
  fan-in: |{m: n∈deps(m)}| / N    → responsibility_weight | downward_consequence
  Ce:     direct_project_requires → fragility_signal
  Ca:     direct_project_requirers → responsibility_signal
  I:      Ce/(Ca+Ce) ∈[0,1] | 0=stable | 1=unstable
          | SDP: depend toward ↓I | high_Ca ∧ high_I → violation
  role:   core(reach↓ ∧ fan-in↑)      → stable foundation | Ce low
          peripheral(reach↑ ∧ fan-in↓) → entry_point ∨ leaf | ≤1 per subsystem
          shared(reach↑ ∧ fan-in↑)     → god_module_candidate
          isolated(reach↓ ∧ fan-in↓)   → vestigial unless intentional leaf
  family: parent_prefix(ns) → family_scope for Ca/Ce decomposition
  ca-family ∧ ca-external ∧ ce-family ∧ ce-external → facade_vs_god_module signal
  ideal:  one(peripheral) | all_others(core) | star | PC < 0.10

λ diagnose.structural(report).
  cycles → fix_first | ¬independent_changes_possible
    | merge(a,b)                    if always_change_together
    | extract(c: a→c ∧ b→c)        if shared_dep_exists
    | invert(protocol∈a, impl∈b)   if callback_pattern
    | strategy hint in finding :evidence :strategy
  high_I ∧ high_Ca → SDP_violation
    | split: model(Ce=0,I=0) + adapter(Ce>0)
  shared ∧ high_reach → god_module
    | ask: single_responsibility? | split if ¬
  high_ca-external ∧ low_ce-external ∧ family_delegation → facade
    | informational ∨ low urgency | avoid false positive
  struct ∧ ¬concept ∧ ¬change ∧ ¬peripheral → vestigial_edge | remove
  PC_delta(before,after) → improved ∨ degraded
    | ratchet: CI fails if PC > threshold

λ read.conceptual(pairs).
  ← (¬structural) → discovery: shared_vocab, no require_edge
                  → hidden_coupling ∨ missing_abstraction
  yes (structural) → confirmation: coupling is about shared_terms
                   → if_terms_unrelated → investigate_leak
  shared_terms     → why coupling exists | read_terms > read_score
  family_terms     → overlap explained by namespace prefix | ¬strong_signal
  independent_terms→ overlap not explained by family naming | stronger signal
  threshold: 0.15 explore | 0.20 default inspect | 0.30 audit | 0.40 strongest_only

λ diagnose.conceptual(pair).
  ← ∧ cross_family ∧ domain_terms         → missing_abstraction_candidate
  ← ∧ same_family ∧ ¬independent_terms    → family_naming_noise | suppressed_by_default
  ← ∧ same_family ∧ independent_terms     → family_siblings | inspect local abstraction
  yes ∧ domain_terms                      → expected coupling | ¬action
  yes ∧ unrelated_terms                   → leaked_responsibility | wrong_dependency_direction

λ read.change(pairs).
  Jaccard:  co/(ca+cb-co) ∈[0,1] | symmetric | coupling_strength
  co:       raw_commit_count | noise_gate: default min_co=2
  conf_a:   co/changes_a | directional: "when a changed, b changed X%"
  conf_b:   co/changes_b | directional: "when b changed, a changed X%"
  conf_a ≫ conf_b → a is satellite of b | narrow a's interface to b
  ←:        co-change without require_edge | sharpest_implicit_signal
  yes:      structural + change confirmed | Jaccard>0.7 → merge_candidate
  horizon:  --change-since "90 days ago" | full_history → historical_scars_persist
            | compare full vs recent → scar ∨ active

λ diagnose.change(pair).
  ← ∧ persists(--change-since "90 days ago") → active_implicit_coupling
    | check conceptual(pair) for abstraction_name
    | shared_data_format ∨ hidden_protocol ∨ should_be_one_ns
  ← ∧ disappears(--change-since "90 days ago") → historical_scar
    | refactor_worked | ¬action
  conf_a ≫ conf_b → a is satellite of b
    | narrow interface(a→b) | reduce a's surface_area
  yes ∧ Jaccard>0.7 → merge_candidate
    | file_boundary serves no purpose | consider_merge

λ combine(structural, conceptual, change).
  struct ∧ concept ∧ change              → confirmed_coupling
    | Jaccard>0.7 → merge_candidate | else → intentional
  struct ∧ concept ∧ ¬change             → expected | none
  struct ∧ ¬concept ∧ ¬change            → vestigial_edge | remove_candidate
  ¬struct ∧ concept ∧ change(active)     → missing_abstraction | highest_priority
  ¬struct ∧ concept ∧ ¬change            → vocabulary_sibling | monitor ∨ local_refactor
  ¬struct ∧ ¬concept ∧ change(active)    → implicit_data_contract | investigate
  all_three(hidden)                      → strongest refactor target

λ diagnose(report).
  diagnose auto_enables conceptual(0.15) ∧ change
  | ranked by actionability (default) ∨ severity (--rank severity)
  | --top N → show N highest after ranking
  | family-naming-noise suppressed by default (--show-noise to include)
  | clusters: related_findings sharing namespace subjects
  | each finding carries :action and :next-step
  | categories:
      cycle           → fix cycle (strategy: merge ∨ extract ∨ invert ∨ investigate)
      cross-lens-hidden → extract abstraction
      vestigial-edge  → remove dependency
      sdp-violation   → split: stable model + adapter
      god-module      → split by responsibility
      hidden-change   → investigate implicit contract ∨ narrow satellite interface
      hidden-conceptual → monitor — consider extracting
      hub             → monitor
      facade          → none (informational)
  | use as queue, not oracle
  | :next-step on each finding → copy-paste next command

λ read.diagnose(findings).
  :action    → what to do (fix/extract/remove/split/narrow/monitor)
  :next-step → $ gordian command to run next
  cycle                → immediate | check :evidence :strategy for merge/extract/invert hint
  cross-lens-hidden    → strongest hidden signal | extract abstraction
  vestigial-edge       → explicit dep with no lens support | remove candidate
  hidden-conceptual    → shared language, no structure | monitor ∨ extract
  hidden-change        → co-evolution, no structure | check asymmetry for satellite
  sdp-violation        → stable thing depends on unstable | split
  god-module           → overloaded centre | split
  facade               → boundary coordinator | ¬action
  hub                  → high blast radius | monitor
  rank actionability   → practical refactor ordering (default)
  rank severity        → architecture risk ordering

λ finding(f).
  :severity    → :high | :medium | :low
  :category    → finding type (see diagnose categories)
  :action      → action keyword | display via action-display
  :next-step   → "gordian explain-pair A B" ∨ "gordian explain N" ∨ "gordian subgraph P"
  :reason      → human-readable explanation
  :evidence    → raw signals supporting the finding
    :strategy  → cycle only: :merge | :extract | :invert | :investigate
    :direction → asymmetric change only: {:satellite :anchor :ratio}
  family-noise? → same-family pair with only prefix-term overlap | suppressed by default

λ explain(ns).
  shows → metrics ∧ direct-deps(project/external) ∧ direct-dependents
        ∧ conceptual-pairs ∧ change-pairs ∧ cycles
  | ask:
      who depends on this?
      what does it depend on directly?
      is its high reach expected?
      which pair should I inspect next?

λ explain-pair(a,b).
  shows → structural(direct-edge? ∧ direction ∧ shortest-path)
        ∧ conceptual(score ∧ shared_terms ∧ family annotations)
        ∧ change(score ∧ co ∧ conf-a ∧ conf-b)
        ∧ finding(hidden?)
        ∧ verdict(category ∧ explanation)
  | verdicts:
      expected-structural
      family-naming-noise
      family-siblings
      likely-missing-abstraction
      hidden-conceptual
      hidden-change
      transitive-only
      unrelated
  | use to decide: ignore ∨ monitor ∨ extract abstraction ∨ merge ∨ prune edge

λ subgraph(prefix).
  use when work_scope = subsystem ∨ namespace family
  | members(prefix_match)
  | internal: node_count ∧ edge_count ∧ density ∧ local_PC ∧ cycles ∧ nodes
  | boundary: incoming ∧ outgoing ∧ external_deps ∧ dependents
  | pairs: conceptual/change split into internal ∧ touching
  | findings touching family + local clusters
  | explain(prefix) fallback when no exact ns but prefix matches family

λ communities(codebase).
  discover latent_architecture_groups without known prefix
  | lens: structural ∨ conceptual ∨ change ∨ combined
  | outputs: members ∧ density ∧ internal_weight ∧ boundary_weight ∧ dominant_terms ∧ bridge_namespaces
  | combined lens → strongest exploratory default

λ compare(before, after).
  use for refactor validation ∨ branch review ∨ trend snapshots
  | good: PC↓ ∧ cycles↓ ∧ hidden_pairs↓ ∧ roles simplify
  | bad:  new_cycles ∨ new_high_findings ∨ centrality↑ with instability↑

λ gate(baseline, current).
  use for CI ratchet
  | command: bb gordian gate --baseline base.edn
  | checks: pc-delta ∧ new-cycles ∧ new-high-findings ∧ new-medium-findings
  | recommend: first fail on new-cycles ∧ new-high-findings | later add max-pc-delta
  | result: pass ∨ fail | exit code 0 ∨ 1

λ tests(src_dirs=[src,test]).
  Ca = 0 ∀ test_ns | src→test forbidden
  reach ≈ 1/N → unit | reach = high → integration (justified)
  unexpected_high_reach → grown unintentionally | isolate
  PC_delta(src → src+test):
    small → targeted (healthy) | large → over-coupled (isolate) | ≈0 → misses coupling-core

λ local-metrics(unit).
  complexity → branch_count ∧ LOC hotspot triage
    | ask: which units branch the most?
    | ask: which units are largest?
    | ask: which units rank highest by cc-risk?
  local → comprehension burden triage
    | ask: which units overload working-set?
    | ask: where is helper-chasing ∨ abstraction-mix highest?
    | ask: which units are hard to modify safely despite moderate CC?
  | complexity and local complement, ¬substitute

λ workflow(codebase).
  1. bb gordian diagnose → read :action and :next-step on top findings
  2. run :next-step commands → explain(ns) ∨ explain-pair(a,b)
  3. if namespace-family work → subgraph(active_prefix)
  4. if local executable-unit hotspot triage → complexity ∨ local
  5. if structure unclear → communities(combined)
  6. before refactor → snapshot(before.edn)
  7. refactor
  8. snapshot(after.edn) → compare(before,after)
  9. CI → diagnose --edn → gate

λ heuristics.
  cycles > cross-lens-hidden > sdp-violations > god-modules > hidden single-lens pairs > hubs
  | read :action before deciding what to do
  | read shared_terms before score
  | conf asymmetry → satellite signal | stronger action than symmetric change
  | trend > absolute threshold
  | recent change history > full-history archaeology for action
  | peripheral role edges are expected to lack lens support | ¬vestigial
  | facade false_positive < god-module false_negative → check family-scoped metrics first

λ output.
  human: text
  share: markdown
  automate: edn ∨ json
  schema: stable envelope with command ∧ lenses ∧ src-dirs ∧ excludes ∧ include-tests
