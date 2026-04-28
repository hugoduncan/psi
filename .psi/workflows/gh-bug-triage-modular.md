---
name: gh-bug-triage-modular
description: Discover a triage bug, reproduce it in an issue worktree, then classify it for either reporter follow-up or a later fix handoff
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
- hand the structured reproduction report to a post-reproduction classification step
- the post-reproduction step either:
  - requests the minimum additional information and relabels to waiting, or
  - publishes the reproduction branch, comments with the branch link, and relabels to fix

Notes:
- This workflow remains intentionally linear at the orchestration layer.
- Current dogfood update uses explicit `:session :input` source selection and `:session :preload` reference context rather than relying on implicit file-order-only wiring.
- `post-repro` now receives the reproduction report as `$INPUT` and also preloads original request, upstream accepted results, and a tail of the reproduction transcript for constrained context.
- Use the issue worktree as authoritative for all reproduction activity after creation.
- This workflow classifies and hands off; it does not create a Munera task, implement a fix, or create a PR.
