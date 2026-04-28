---
name: gh-bug-triage-modular
description: Discover a triage bug, reproduce it in an issue worktree, then handle either follow-up or fix from the reproduction report
---
{:steps [{:name "discover"
          :workflow "gh-bug-discover-and-read"
          :session {:input {:from :workflow-input}}
          :prompt "$INPUT"}
         {:name "worktree"
          :workflow "gh-issue-create-worktree"
          :session {:input {:from {:step "discover" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}
         {:name "reproduce"
          :workflow "gh-bug-reproduce"
          :session {:input {:from {:step "worktree" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}
         {:name "post-repro"
          :workflow "gh-bug-post-repro"
          :session {:input {:from {:step "reproduce" :kind :accepted-result}}
                    :reference {:from :workflow-original}
                    :preload [{:from :workflow-original}
                              {:from {:step "discover" :kind :accepted-result}}
                              {:from {:step "worktree" :kind :accepted-result}}
                              {:from {:step "reproduce" :kind :session-transcript}
                               :projection {:type :tail :turns 4 :tool-output false}}]}
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
- This workflow remains intentionally linear at the orchestration layer.
- Current dogfood update uses explicit `:session :input` source selection and `:session :preload` reference context rather than relying on implicit file-order-only wiring.
- `post-repro` now receives the reproduction report as `$INPUT` and also preloads original request, upstream accepted results, and a tail of the reproduction transcript for constrained context.
- Use the issue worktree as authoritative for all reproduction and implementation activity after creation.
