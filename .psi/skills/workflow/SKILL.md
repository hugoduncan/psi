---
name: workflow
description: A workflow comprehension and authoring skill. Use when the asks "create a workflow"  or "update a workflow".
---

λworkflow059.
  workflow_authoring
  ≡ single_step ∨ multi_step

λsingle_step.
  workflow
  ≡ top_level_agent_spec
  ∧ preserves(current_surface)

λmulti_step.
  workflow
  ≡ {:steps [step₁ … stepₙ]}
  ∧ n ≥ 1
  ∧ ∀stepᵢ. has(stepᵢ,:name)
  ∧ ∀i,j. i ≠ j → name(stepᵢ) ≠ name(stepⱼ)

λstep.
  {:name step_name
   :workflow executor_name
   :session session_spec?
   :prompt prompt_text?
   :judge judge_spec?
   :on routing_table?}

λauthor_facing_identity.
  canonical_ref(step) ≡ name(step)
  ∧ internal_ref(step) ≡ compiled_step_id(step)
  ∧ authoring_uses(name) ∧ ¬authoring_uses(compiled_step_id)

λsession_spec.
  {:input input_source?
   :reference reference_source?
   :system-prompt system_prompt?
   :tools tools?
   :skills skills?
   :model model?
   :thinking-level thinking?
   :reference reference_projection_or_preload?}

λsource.
  :workflow-input
  ∨ :workflow-original
  ∨ {:step step_name :kind :accepted-result}
  ∨ later({:step step_name :kind :session-transcript})

λsource_rule.
  explicit_ref({:step s …}, stepᵢ)
  → ∃stepⱼ. name(stepⱼ)=s ∧ j < i

λinvalid_source.
  malformed(source)
  ∨ unknown(step_name)
  ∨ forward_ref(step_name)
  → compile_error

λprojection.
  first_cut ≡ :text ∨ :full ∨ {:path [k₁ … kₙ]}
  ∧ later(:tail ∨ strip_tool_output)

λdefault_input(stepᵢ).
  if i = 1
  then from(:workflow-input)
  else from({:step name(stepᵢ₋₁) :kind :accepted-result})

λdefault_reference(stepᵢ).
  from(:workflow-original)

λdefault_session_construction(step,parent,runtime).
  create_child_session(step.workflow)
  ∘ compose_prompts(current_rules)
  ∘ inherit(runtime_extensions ∧ runtime_workflows)
  ∘ inherit_or_delegate(tools ∧ skills ∧ model ∧ thinking ∧ system_prompt)
  ∘ bind(default_input(step) ∧ default_reference(step))
  ∘ preload(none)

λoverride_semantics.
  explicit(:tools) → replace(default_tools)
  ∧ explicit(:skills) → replace(default_skills)
  ∧ explicit(:model) → replace(default_model)
  ∧ explicit(:thinking-level) → replace(default_thinking)
  ∧ explicit(:system-prompt) → compose_under(current_rules)
  ∧ ¬explicit(environment) → inherit(runtime_environment)

λprompt_channels.
  $INPUT ≡ derive(session.input)
  ∧ $ORIGINAL ≡ derive(session.reference)
  ∧ bind_is_convenience
  ∧ session_first > prompt_binding_first

λstep_execution.
  execute(step)
  ≡ derive_session_spec(step)
  ∘ project_sources(step.session)
  ∘ construct_or_shape_child_session
  ∘ derive_prompt_bindings(optional)
  ∘ submit(step.prompt)

λjudge_spec.
  {:prompt judge_prompt
   :system-prompt judge_system_prompt?
   :projection judge_projection?}

λrouting_table.
  {"signal₁" {:goto target₁ :max-iterations n₁?}
   …
   "signalₖ" {:goto targetₖ :max-iterations nₖ?}}

λgoto_target.
  :next ∨ :previous ∨ :done ∨ step_name

λgoto_rule.
  named_target(t) → ∃step. name(step)=t

λloop.
  actor_step
  ∧ judge(actor_step)
  ∧ on(revise_signal) → goto(prior_step_name)
  ∧ on(approve_signal) → goto(:done ∨ :next)

λfork.
  actor_step
  ∧ judge(actor_step)
  ∧ on(signal_a) → goto(step_name_a)
  ∧ on(signal_b) → goto(step_name_b)

λchain.
  chain(step₁ … stepₙ)
  ≡ ∀i>1. input(stepᵢ) ← accepted_result(stepᵢ₋₁)
  ∨ explicit_nonadjacent_flow

λworkflow059_summary.
  session_first_authoring
  ∧ explicit_data_flow
  ∧ explicit_step_identity
  ∧ control_flow_via(judge ∧ on)
  ∧ projection_over_transformation
  ∧ backward_compatible_defaults
  ∧ compile_time_validation
  ∧ author_facing_names > internal_ids
