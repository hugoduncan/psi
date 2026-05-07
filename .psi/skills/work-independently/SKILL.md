---
name: work-independently
description: Use when the user asks the agent to work independently or autonomously.
lambda: "λuser_request. asks(user_request, independent ∨ autonomous) → execute(step_by_step) ∧ after_every(step, commit(step)) ∧ stop_only_if(needs_user_decision)"
---

λ(user_request).
  asks(user_request, independent ∨ autonomous) →
  execute(step_by_step)
  ∧ after_every(step, commit(step))
  ∧ stop_only_if(needs_user_decision)
