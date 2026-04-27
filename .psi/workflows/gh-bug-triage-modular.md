---
name: gh-bug-triage-modular
description: Discover a triage bug, reproduce it in an issue worktree, then handle either follow-up or fix from the reproduction report
---
{:steps [{:workflow "gh-bug-discover-and-read"
          :prompt "$INPUT"}
         {:workflow "gh-issue-create-worktree"
          :prompt "$INPUT"}
         {:workflow "gh-bug-reproduce"
          :prompt "$INPUT"}
         {:workflow "gh-bug-post-repro"
          :prompt "$INPUT"}]}

Coordinate a modular GitHub bug-triage workflow.

Flow:
- discover and read one bug+triage issue
- create an issue worktree from origin/master
- attempt reproduction inside the worktree
- hand the structured reproduction report to a post-reproduction step
- the post-reproduction step either:
  - requests the minimum additional information and relabels to waiting, or
  - creates a Munera task, refines the design, fixes the bug, and creates a PR

Notes:
- This workflow is intentionally linear at the orchestration layer because current `.psi/workflows` compilation wires step inputs by definition order.
- The branch decision therefore lives inside `gh-bug-post-repro`, which consumes the reproduction report directly.
- Use the issue worktree as authoritative for all reproduction and implementation activity after creation.
