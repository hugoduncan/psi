---
name: gh-bug-triage-modular
description: Discover a triage bug, reproduce it in an issue worktree, then classify it for either reporter follow-up or a later fix handoff
---
{:steps [{:name "discover"
          :type :delegate
          :target "gh-bug-discover-and-read"
          :outputs {:handoff {:source :delegate/handoff}}
          :prompt-string {:type :template
                          :text "{{input}}"
                          :vars {"input" {:from :workflow-input}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "worktree"
          :type :delegate
          :target "gh-issue-create-worktree"
          :outputs {:handoff {:source :delegate/handoff}}
          :prompt-string {:type :template
                          :text "{{discover_report}}"
                          :vars {"discover_report" {:from {:step "discover" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :output :handoff}}]}
         {:name "reproduce"
          :type :delegate
          :target "gh-bug-reproduce"
          :outputs {:handoff {:source :delegate/handoff}}
          :prompt-string {:type :template
                          :text "{{discover_report}}\n\n{{worktree_report}}"
                          :vars {"discover_report" {:from {:step "discover" :yield :text}}
                                 "worktree_report" {:from {:step "worktree" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :output :handoff}}
                    {:type :source
                     :from {:step "worktree" :output :handoff}}]}
         {:name "post-repro"
          :type :delegate
          :target "gh-bug-post-repro"
          :outputs {:handoff {:source :delegate/handoff}}
          :prompt-string {:type :template
                          :text "{{report}}"
                          :vars {"report" {:from {:step "reproduce" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :output :handoff}}
                    {:type :source
                     :from {:step "worktree" :output :handoff}}
                    {:type :source
                     :from {:step "reproduce" :output :transcript}
                     :projection {:type :tail :turns 4 :tool-output false}}]}]}

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
- It is now the authoritative executable richer target-authored bug-triage example.
- It uses delegated yielded text for the immediate next ask and delegated structured `:handoff` outputs for stable machine-facing cross-workflow data.
- `post-repro` receives the reproduction report as its immediate ask and also receives original request context, upstream delegated handoffs, and a constrained reproduction transcript tail as support context.
- Use the issue worktree as authoritative for all reproduction activity after creation.
- This workflow classifies and hands off; it does not create a Munera task, implement a fix, or create a PR.
