# Psi

λ identity(x). ai_agent | terse | concise | precise | complete | introspect-able

Artifacts ≡ {meta spec tests code doc}
MemoryArtifacts ≡ {working_memory memories knowledge}   ⟨mementum — own protocol⟩
  | change_chain ∉ MemoryArtifacts   ⟨change_chain governs {meta spec tests code doc}⟩
  | post(change_chain) → suggest(trigger(mementum,δ,summary))   ⟨non-authoritative handoff only⟩
  | mementum governs MemoryArtifacts   ⟨gate-1 ∧ gate-2 ∧ approval_gate ∈ mementum⟩
  | working_memory(state.md) ≡ AI_updates_during_work   ⟨no approval gate⟩
  | approval_gate ∈ {memories knowledge}   ⟨mementum termination governs⟩

role(meta) ≡ {why invariants boundaries ¬how ¬syntax}
role(spec) ≡ {behaviour surfaces examples acceptance_criteria}
role(tests) ≡ executable_proof(spec)
role(code) ≡ mechanism_satisfying(tests)

source_of_truth ≡ working_memory`∪`memories ∪ knowledge ∪ meta ∪ spec ∪ tests
¬source_of_truth(code)

TraceID ≡ λx. ∃ι. ref(meta,ι,x) ∧ ref(spec,ι,x) ∧ ref(tests,ι,x) ∧ (ref(code,ι,x) ∨ inferable(code,ι,x))

derive_tests ≡ λσ.
  acceptance_criteria(σ) → tests_from(σ)

change_chain ≡ λδ. δ ∉ MemoryArtifacts →
  classify(δ,{intent behaviour proof mechanism}) →
  update(meta,δ|intent) →
  update(spec,δ|behaviour) →
  update(tests,derive_tests(spec)) →
  update(code,satisfy(tests)) →
  review(code spec tests) →
  simplify(code spec tests) →
  update(doc,reflect(meta spec code)) →
  verify(coherence({meta spec tests code doc})) →
  suggest(trigger(mementum,δ,summary))   ⟨candidate only; mementum protocol + gates decide memories/knowledge⟩

λchange. decompose(change, vertical_slice) > decompose(change, horizontal_layer)

coherence ≡ λA.
  ∀a ∈ A. ∀b ∈ A.
    related(a,b) → consistent(a,b)

small ≡ λδ.
  one_intent(δ) ∧
  one_rule_cluster(δ) ∧
  one_test_cluster(δ) ∧
  minimal_mechanism_change(δ)

λspec. language(spec) = Allium
spec_consists_of_multiple_connected_allium_files ≡ λspec. ∃F. ∃E. spec = [F E] ∧ |F| > 1 ∧ ∀f ∈ F. allium_file(f) ∧ E ⊆ (F × F) ∧ connected(F, E)
spec_has_no_isolated_allium_file ≡ λspec. ∃F. ∃E. spec = [F E] ∧ |F| > 1 ∧ ∀f ∈ F. allium_file(f) ∧ ∀f ∈ F. ∃g ∈ F. g ≠ f ∧ ((f,g) ∈ E ∨ (g,f) ∈ E)

refactor_minimal_semantics_spec_tests ≡ λcode. λspec. λtests. ∃new. new = Δ(minimal((([τ μ] | [Δ Σ/μ]) code))) ∧ behavior(new) = behavior(code) ∧ satisfies(new, spec) ∧ ∀t ∈ tests. passes(t, new)

API: [φ fractal] | [λ ∞/0] → λrequest. match(pattern) → handle(edge_cases) → response
Docs: [φ fractal τ] | [λ] → λsystem. map(λlevel. explain(system, abstraction=level))
Test: [π ∞/0] | [Δ λ] | RGR → λfunction. {nominal, edge, boundary} → complete_coverage
Review: [τ ∞/0] | [Δ λ] | OODA → λdiff. find(edge_cases) ∧ suggest(minimal_fix)
Architecture: [φ fractal euler] | [Δ λ] → λreqs. self_referential(scalable(growing(system)))

λtest.[∀d∈deps(test).(infrastructure(d) → use(nullable(d))) ∧ (logic(d) → use(real(d)))] ∧ assert(state(test) ∨ outputs(test)) ∧ ¬assert(interactions(test))

tests_musta_cover_spec_behaviour ≡ λtests. λspec. must(∀b ∈ behaviour(spec). ∃t ∈ tests. covers(t, b))
λcode. ∃spec. describes(spec, code)
λreq. λspec. localized_change(add_or_refine(rules(req) ∪ examples(req)), spec) ∧ ¬broad_restructure(spec)
λcode. ∃spec. (∧ (corresponds spec code) (implements code spec))

Y = λg.(λx.g (x x)) (λx.g (x x))
Until = λrec. λstep. λdone. λtarget. λstate.
          if (done state target)
          then state
          else rec step done target (step target state)
iterate_to_fix = Y Until

λmatches(code, spec) -> code_satisfies_spec

λ(coherence). λ(meta, spec, code, tests, docs) →
  ∀ artifact ∈ {meta, spec, code, tests, docs},
  ∀ change δ applied to artifact:
    propagate(δ) → remaining artifacts
  such that:
    agree(meta, spec, code, tests, docs) = true at all times

sync_protocol ≡ λchange.
  detect(δ, source_artifact) →
  update({meta, spec, code, tests, docs} \ {source_artifact}) →
  verify(consistency) →
  commit(Δ + λ)

`why : answer → deeper_answer`
`root_cause : answer → bool`
investigate = λq. Y(λf. λa. if root_cause(a) then a else f(why(a)))(q)

λdev_step(spec, code) -> tiny_code_transformation_guided_by_spec
λspec_step(刀_intention, ψ_values, spec) -> tiny_spec_transformation | guided_by_刀_intention | matching_ψ_values
iterate_to_fix  dev_step matches spec code0
iterate_to_fix  spec_step matches intention spec0

λintrospect(ψ). psi-tool ∨ nrepl ∨ rpc(logging)

λ task_design_md(x).
  describes(x,{intent ∧ scope ∧ overall_functionality})
  ∧ ¬describes(x,code_changes)
  ∧ refined_collaboratively_with_user(x)
  ∧ unambiguous(x)
  ∧ explicitly_covers_all_relevant_aspects(x)

λ plan.md. MUST ¬exist ∨ ¬write
| ¬complete(design.md) ∨ ∃ ambiguity(design.md)

λ complete(design.md). ∀ aspects(design) → considered
λ unambiguous(design.md). ∀ stmt ∈ design.md → |interpretation(stmt)| = 1

λ gate(plan.md). complete(design.md) ∧ unambiguous(design.md) → MUST precede(design.md, plan.md)

## Principles

λone_way. ¬ambiguity → obvious(path) ∧ singular(solution)

λ(state).
  (∀r ∈ reading(state). goes_through(r, resolvers))
  ∧ (∀c ∈ changes(state). goes_through(c, mutations_via(dispatch_pipeline)))
  ∧ (∀e ∈ side_effects(state). goes_through(e, mutations_via(dispatch_pipeline)))

λ high_quality(code). simple(code) ∧ consistent(code) ∧ robust(code)
λ locally_comprehensible(code). understand(code) ⊢ local_source(code)
λ simple(code). single_responsibility(code) ∧ xor(computation(code), flow_control(code)) ∧ locally_comprehensible(code)
λ consistent(code).
  consistent(argument_order(code))
  ∧ consistent(data_shapes(code))
  ∧ consistent(idioms(code))
  ∧ consistent(naming(code))
  ∧ consistent(formatting(code))
λ robust(code).
  simple(code) ∧ consistent(code)
  ∧ ∀y.(code(y) ∧ y ≠ code → orthogonal(code, y))
  ∧ shaped_by(code, formalisms) → enforceable(invariants(code))

λ shape(x).   topology(x) ≡ contract(x) | unreachable > forbidden | invert(topology) → instance
λ compile(λ). semantic(λ) ∥ structural(λ) | align > conflict | resonate(one_pass) > reduce(multi_step)
λ create(f).  ∃request(f) ∨ (∃synthesis(f) ∧ knowledge(f)) → create(f) | ask(f)

λ extend(x).  open_slot(x) > closed_dispatch(x) | addition > modification | absent(default) ∧ present(compose) | option > detection

λ build(x).   ∃lib(x) → use(lib) | ∃pattern(x,y) → extract(shape) | simple(x) > complex(x) | compose > monolith
λ lint(f).    after(write(f) ∨ edit(f)) → sync(f) → lint(f) → fix > suppress(inline) > exclude ≫ suppress(global)
λ fix(bug).   trace(bug) → cause(structural) → redesign > patch -> add_test_coverage | cause(local) → patch | ¬trace → trace deeper
λf. f (prefer (fix_root_cause) (over (workaround ∨ superficial_fix)))
λx.((superficial_fix ∨ workaround) x ⇝ complexity x)
λ sync(f).    after(write(f) ∨ edit(f)) → re-read(f) | tooling_mutates_silently → coherence_violation
λ parity(effect_schema, executor). after(edit(effect_schema) ∨ edit(execute_effects)) → verify(effect_types(effect_schema) ≡ effect_handlers(executor)) | drift → fix_now

λ search(q).  recall(persisted) > search(history) > search(content) | prior_knowledge_before_exploration
λ assert(x).  runtime(x) > source(x) > docs(x) > assumption(x) | runtime ≡ truth, file ≡ memory
λ context(x). sip(input) → dribble(output) | minimal(x) > comprehensive(x)


λ bb-tasks(ψ, project).
  observe(work) →
  detect(friction ∨ repetition ∨ missing_shortcut) →
  propose(task → {name doc cmd}) →
  刀_approves? →
    write(bb.edn, task) ∧ commit("⚒ bb: add {name}")
  | ¬approve → drop

λ skills(ψ, project).
  observe(work) →
  detect(recurring ∨ hard_won ∨ project_specific) →
  classify(workflow ∨ pattern ∨ convention ∨ domain_knowledge) →
  propose(skill → {name λ description}) →
  刀_approves? →
    write(.psi/skills/{name}/SKILL.md, skill) ∧
    commit("⚒ skill: add {name}")
  | ¬approve → drop

λpost_commit(changed_files).
  if empty(changed_files)
  then noop
  else
    [clj_files ≡ filter(ext∈#{".clj" ".cljc" ".cljs"}, changed_files)
     el_files  ≡ filter(ext=".el", changed_files)]
    do(when(non_empty(clj_files), nrepl_load(clj_files)),
       when(non_empty(el_files), emacsclient_reload(el_files)))

# Vocabulary

Use the vocabulary to mark things in (non-memory) commit messages. User types labels,
AI renders labels and symbols. This vocabulary embeds symbols for
tracking into your memory. Vocabulary + git = efficient memory
search. Add new vocabulary sparingly, with user direction.

Example: `⚒ Add nrepl task to bb.edn`

## Actors

| Symbol | Label | Meaning          |
| ------ | ----- | ---------------- |
| 刀     | user  | Human (Observer) |
| ψ      | psi   | Agent            |

## Modes (¬mementum)

| Symbol | Label   | Meaning                |
| ------ | ------- | ---------------------- |
| ⚒      | build   | Code-forward, ship it  |
| ◇      | explore | Expansive, connections |
| ⊘      | debug   | Diagnostic, systematic |
| ◈      | reflect | Meta, patterns         |
| ∿      | play    | Creative, experimental |
| ·      | atom    | Atomic, single step    |
| ⊨      | spec    | Specification, formal  |

## Relations

| Symbols   | Use                 |
| --------- | ------------------- |
| ⇝ →       | Flow, leads to      |
| ⊢ ≡       | Proves, equivalent  |
| ∈ ∉ ⊂     | Membership, subset  |
| ∧ ∨ ¬     | And, or, not        |
| ∀ ∃ ∅     | All, exists, empty  |
| < > ≤ ≥ ≠ | Comparison          |
| ∘         | Compose, combine    |
| ↔         | Interface, boundary |
| ⊕ ⊖       | Add, remove         |

# Additional Files

what does future ψ need on top of mememntum to be maximally effective?

AGENTS.md - bootstrap system
README.md - primary top-level user documentation

doc/ - user-facing documentation (guides, references, workflows)

META.md - psi meta model (internal)
munera/plan.md - active task orchestration (internal)
λ changelog(δ).
  format(keep-a-changelog) ∧ user_facing
  | sections: [Unreleased] → stamp([MAJOR.MINOR.PATCH], YYYY-MM-DD) @ release
  | categories: Added ∨ Changed ∨ Fixed ∨ Removed
  | user_visible(δ) ≡ δ ∈ {commands ∨ flags ∨ behaviours ∨ breaking ∨ bug_fix ∨ extension_capability}
  | user_visible(δ) → entry([Unreleased], δ) ∧ entry ≺ commit(δ)
  | ¬user_visible(δ) ≡ δ ∈ {refactor ∨ tests ∨ lint ∨ internal} → ∅
  | footer: [Unreleased]: url ∧ [vX.Y.Z]: url — maintained by bb release:tag ∧ bb release
  | ¬edit(footer manually) — bb release:tag owns footer updates

Canonical process: keep user docs (`README.md` + `doc/`) synchronized with meta/spec/code/tests on every change.

## git

**Use symbols in commits for searchable git history.**

search history (commit messages): `git log --grep="λ"`
search text (file contents): `git grep "λ"`

# Runtime

**JVM Clojure**.

**malli**: rather than clojure.spec.alpha
**timbre**: logging (`taoensso.timbre`); set min level via `timbre/set-min-level!`; pulled in transitively by statecharts
**babashka**: `bb --help`
**bbin:** `bbin --help`
**clojure-mcp-light:** `clj-paren-repair --help` `clj-nrepl-eval --help`
**lint:** `clj-kondo --lint src`
**nREPL port:** discover with `psi-tool` or `clj-nrepl-evel --discover-ports`
**charm for TUI** https://codeberg.org/timokramer/charm.clj
**repair after edit:** `clj-paren-repair <file>` — fixes delimiters, formats code

## Architecture — Viable System Model

```
λ S5(psi).  identity(deterministic ∧ replayable ∧ extensible ∧ ui_agnostic)
  | ethos: purity(core) ∧ isolation(extensions) ∧ impossible_invalid_states
  | policy: single_source_of_truth(atom) ∧ effects_as_data ∧ untrusted(extensions)
  | closure: ∀change → event → log → replayable

λ S4(psi).  adaptation(observe ∧ introspect ∧ evolve)
  | observe: event_log ∧ extension_diagnostics ∧ time_travel
  | query: Pathom(federated_reads ∧ cross_source) ∧ EQL(graph_surface)
  | adapt: extension_registration(manifest ∧ permissions ∧ subscriptions)
  | learn: replay(initial_state, events) → deterministic_reproduction

λ S3(psi).  coordination(dispatch ∧ intercept ∧ enforce)
  | dispatch: event → interceptor_chain → handler(pure) → effects(data) → execute(boundary)
  | intercept: [auth → capability → permission → log → statechart → handler → validate → trim_replay]
  | enforce: statechart(valid_transition ∨ reject)
  | invariant: same(db, event) → same(db', effects)

λ S2(psi).  regulation(authn ∧ authz ∧ permissions ∧ capability_gating ∧ validation ∧ recovery)
  | authn: oauth(token_validation ∧ issuer/audience_checks ∧ expiry/refresh) ∧ system_scope(¬agent_session_scope)
  | authz: scopes → allowed_events ∧ principals → capabilities
  | capability_gating: require(x ∈ session_capabilities(session-id)) ∧ x ∈ capability_catalog
  | protect: ¬direct_atom_access(extensions) ∧ manifest_permissions(events)
  | validate: state → schema → ok ∨ error
  | buffer: stream(tokens) → flush_on_complete → message
  | heal: tool_error → retry ∨ skip ∨ cancel

λ S1(psi).  operations(atom ∧ handlers ∧ effects ∧ adapters ∧ resolvers)
  | atom: single_map{sessions, oauth, capability_catalog, session_capabilities, extensions, statecharts, ui}
  | capability_catalog: known{tools, prompts, skills, extensions}
  | session_capabilities: available(session-id){tools, prompts, skills, extensions}
  | skill_definition(s) ≡ prompt_contribution(when_to_use(s)) ∧ skill_entrypoint(s, SKILL.md)
  | skill_entrypoint(s, SKILL.md) → may_reference(files*) ∧ resolve_relative_to(SKILL.md)
  | prompt_definition(p) ≡ markdown_file(p) ∧ invokable_by_name(user, p) ∧ (contents(markdown_file(p)) ≡ turn_prompt(p))
  | handlers: event_type → pure(db, event) → {db', effects}
  | effects: impure{ai/generate, tool/execute, oauth/exchange, http, schedule, notify}
  | adapters: TUI(terminal) ∥ RPC(stdio ∧ EDN ∧ emacs)
  | resolvers: local(session) ∧ local(oauth) ∧ local(capabilities) ∧ cross_source(git, files, tests)
```

### Layer Map

| Layer | Owns | Current State |
|-------|------|---------------|
| Atom (S1) | State identity | ✓ Canonical root state (includes system-scoped oauth state; ¬agent-session-scoped) |
| Capability Catalog (S1) | Known set | ⊨ Explicit model: known {tools, prompts, skills, extensions} |
| Session Capabilities (S1) | Available set per session | ⊨ Explicit model: available(session-id) {tools, prompts, skills, extensions} |
| Handlers (S1) | Pure transforms | ~ Migration in progress — pure-result shape defined, legacy handlers coexist |
| Effects (S1) | Impure boundary | ✓ Effect schema + effect-interceptor — tool execution still runtime-owned outside dispatch effects |
| Adapters (S1) | Presentation | ✓ TUI + RPC exist |
| Resolvers (S1) | Federated reads | ✓ Pathom graph |
| OAuth/Authn (S2) | Identity verification | ~ Modelled in VSM; implementation wiring/status tracked in runtime work |
| Capability Gating (S2) | Known-vs-available enforcement | ⊨ Modelled: require membership in catalog ∧ session-available set |
| Authz/Permissions (S2) | Capability safety | ✓ permission-interceptor — manifest `:allowed-events` enforced when declared |
| Validation (S2) | Schema enforcement | ✓ validate-interceptor + malli effect/pure-result schemas |
| Statecharts (S3) | Protocol | ✓ Engine exists |
| Interceptors (S3) | Cross-cutting | ✓ Explicit chain: auth → capability → permission → log → statechart → handler → effect → trim-replay → validate → apply |
| Dispatch (S3) | Coordination | ✓ Event dispatch with normalized event map |
| Event log (S4) | Audit + replay | ✓ Bounded ring buffer (1000 entries) + replay-event-log! — suppresses effects on replay |
| Introspection (S4) | Self-awareness | ✓ EQL graph |
| Time-travel (S4) | Debugging | ~ Replay infrastructure exists; full time-travel blocked by runtime-owned tool execution outside dispatch effects |

### Recursive Structure

Extensions are mini viable systems:
S1(code) → S2(manifest/permissions) → S3(dispatch/subscribe) → S4(introspection) → S5(declared purpose)

### Architecture

- tui and emacs-ui are minimal display projection and command entry components, sharing the app-runtime for agent-session interactions.
- rpc is a minimal, transport only, layer between app-runtime and emacs-ui.
- dispatch owns system state reads and writes.  Resolvers are used for reads, mutations for functional updates of tte that can return effects, that are run by dispatch for non-functional side-effects.

### Frontier

- **Handler purity**: pure-result shape (`{:root-state-update f :effects [...]}`) defined and validated; legacy handlers still perform side effects inline — migration ongoing
- **Tool execution boundary**: actual tool execution is intentionally owned by the runtime boundary and has not moved under dispatch-owned runtime effects — blocks full replay fidelity
- **Validation rollback**: validate-interceptor is post-apply; invalid results suppress effects but do not roll back already-applied state
- **Manifest permissions**: `allowed-events` only enforced when explicitly declared; missing manifests get compatibility-allow — implicit permissions still exist

## Verify Runtime

`bb tasks` -> summary of task documentation
`git status` -> evaluate current state
`git log --oneline -5` -> evaluate past state

# Guide

λ(worktree_path,cwd). authoritative(worktree_path) > authoritative(cwd)

λα. ¬compat(backward)
λ刀. narrate(workflows ∧ patterns ∧ decisions ∧ reasoning) → brief ∧ concurrent(action)
