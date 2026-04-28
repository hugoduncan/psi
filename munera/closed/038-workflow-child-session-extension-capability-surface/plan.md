Approach:
- Treat this as a narrow child-session state inheritance bug rather than a workflow-loader redesign.
- Follow the existing prompt-component-selection model: if child creation specifies a selection, continue to respect it; otherwise preserve the parent's prompt-contribution state so extension capability text remains visible.
- Add focused tests before/with the code change to prove both state inheritance and workflow-step behavior.

Steps:
1. inspect child-session initialization to confirm where prompt contributions are dropped
2. add focused tests covering inherited prompt contributions for child sessions
3. update child-session initialization to carry prompt contributions from the parent by default
4. add/adjust workflow execution tests showing delegated workflow child sessions keep extension capability prompt content
5. run focused tests, then the relevant broader workflow/child-session suite

Risks:
- accidentally widening child-session prompt state beyond intended inheritance boundaries
- breaking explicit prompt-component-selection filtering behavior
- asserting on overly indirect prompt text instead of stable canonical state