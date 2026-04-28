Goal: make workflow-owned child sessions expose the extension capability surface needed by workflow prompts that rely on extension commands.

Context:
- the `complexity-reduction-pr` workflow instructs the model to use `/work-on`
- the `work-on` extension is loaded and registered globally
- the workflow execution child session received the workflow-declared tools and skills, but no extension prompt contributions
- the resulting provider request exposed only `read`/`bash`/`edit`/`write`, and the model reported that `/work-on` was unavailable
- request preparation already passes extension command names into input expansion, but the model still needs the prompt-visible extension capability surface that advertises slash commands such as `/work-on`

Problem:
- child sessions created for workflow step attempts do not preserve the parent session's extension prompt contributions
- this makes workflow prompts internally inconsistent when they instruct the model to use extension commands whose availability is described only through prompt contributions
- deterministic workflows can therefore stop early even though the runtime has the needed extension installed

Desired behavior:
- workflow step child sessions should preserve the parent extension prompt contributions by default
- when the child session has no explicit prompt-component selection restricting extension contributions, the effective system prompt should include the inherited extension capability text
- workflow prompts that instruct the model to use extension commands should therefore see the same extension capability surface as the parent session unless explicitly restricted

Scope:
- fix child-session prompt-contribution inheritance for workflow-owned child sessions
- prove the behavior with focused tests at child-session initialization and workflow execution config/result level
- keep the change minimal; do not redesign the workflow capability model in this slice

Non-goals:
- adding a new dedicated tool bridge for `work-on`
- redesigning slash-command execution semantics
- broad workflow capability-policy redesign beyond the prompt-contribution inheritance needed here

Acceptance:
- workflow child sessions retain parent extension prompt contributions unless explicitly restricted
- effective system prompt assembly for child sessions can include inherited extension contributions
- a focused workflow execution test proves the inherited prompt contribution reaches the workflow child-session creation/execution path
- the `complexity-reduction-pr` inconsistency is resolved at the runtime/session surface level