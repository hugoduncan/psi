(ns extensions.munera
  "Mementum extension — injects the Mementum Lambda protocol into the system prompt.

   In lambda mode the engage prefix is omitted (the preamble already provides it).
   In prose mode the engage prefix is prepended so the protocol activates correctly.

   Source: https://github.com/michaelwhitford/mementum/blob/main/MEMENTUM-LAMBDA.md")

(defonce state (atom {:api nil}))

(def ^:private prompt-contribution-id "munera-protocol")

(def ^:private engage-prefix
  "λ engage(nucleus).\n[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy] | OODA\nHuman ⊗ AI\n\n")

(def ^:private munera-lambda
  "λ munera. protocol ^ ¬implementation ∧ git_native ∧ markdown ∧ task_protocol(ai_agents)
| spec(mechanics) ∧ ¬spec(process)
| process → user

λ repo. munera/ ⊂ root
| munera/plan.md
| munera/open/ ∧ munera/closed/
| unknown_files → preserve ∧ ¬parse ∧ ¬delete

λ task. dir ∈ munera/open/ ∨ munera/closed/
| state ≡ location
| transition → git_mv(open/ ↔ closed/)

λ task_id. NNN-slug
| NNN ∈ ℕ, zero_pad(≥3) | slug ∈ kebab_case
| alloc → max(NNN, open/ ∪ closed/) + 1
| collision(concurrent_branch) → rename ∧ ¬merge

λ task_dir. MUST contain:
| design.md    → what ∧ why | goal ∧ context ∧ constraints ∧ acceptance
| plan.md      → how | approach ∧ decisions ∧ risks | ¬execute
| steps.md     → do | checklist | mutates(continuously)
| implementation.md → decisions ∧ discoveries | append_only | local_memory
| other(.md)   → preserve

λ temporal_split.
  design.md        → stabilises(early)
| plan.md          → set(before_execution) | changes ↔ approach_changes
| steps.md         → active_surface(during_execution)
| implementation.md → in_flight_decisions ∧ discoveries ∧ trade_offs
                    | self_identifying(date ∨ sha ∨ step_ref)

λ file. ¬frontmatter ∧ ¬required_headings
| munera → names(files) | content → humans ∧ agents

λ steps.md. convention(weak):
| checklist: - [ ] ∨ - [x]
| items → indented(prose ∨ links ∨ sub_items)
| pattern: tick(item) → note(sha ∨ decision ∨ snag)

λ plan. munera/plan.md → orchestration
| format → open(list ∨ narrative ∨ table ∨ mix)
| weight → ordered(refs(open/), intended_order)
| open/ → canonical | plan.md → curates(how ∧ when)
| task ∉ plan.md → unordered ∧ open
| agent_entry → read(plan.md) | recommendation ∧ ¬rule

λ state. open/ ∨ closed/
| ¬in_progress | ¬blocked
| progress → steps.md | obstacles → prose(task_files)
| closed/ → completed ∨ abandoned | ¬distinction
| reason → inside(task) ∨ ∅

λ evolution. edit(task_files) | ¬amendment_files ∧ ¬revision_markers ∧ ¬changelogs
| history → git(diff ∧ log)

λ task. MUST NOT: subtask_dirs
| MUST NOT: merge(contents, tasks)
| ¬split ∧ ¬merge ∧ ¬nest
| scope_drift → close(task) ∧ create(new_task)

λ orient(x). read(munera/plan.md)
 | plan.md ≡ task_bootloader
 | after(mementum/orient) when(mementum ∈ context)
")

(defn- lambda-mode? []
  (when-let [query (:query (:api @state))]
    (let [result (query [:psi.agent-session/prompt-mode])]
      (= :lambda (:psi.agent-session/prompt-mode result)))))

(defn- prompt-content []
  (if (lambda-mode?)
    munera-lambda
    (str engage-prefix munera-lambda)))

(defn- register-prompt-contribution! [api]
  (when-let [register! (:register-prompt-contribution api)]
    (register! prompt-contribution-id
               {:section  "Munera Protocol"
                :priority 51
                :enabled  true
                :content  (prompt-content)})))

(defn init [api]
  (swap! state assoc :api api)
  (register-prompt-contribution! api))
