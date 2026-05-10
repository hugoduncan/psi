---
name: gh-bug-triage-modular
description: Discover a triage bug, reproduce it in an issue worktree, then classify it for either reporter follow-up or a later fix handoff
---
{:steps [{:name      "discover"
          :type      :invoke
          :operation "github/find-issue"
          :args      {:labels ["bug" "triage"]
                      :input  {:from :workflow-input :path [:input]}}
          :outputs   {:summary {:source :invoke/summary}}
          :yields    {:type :text :text :summary}}
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
                     :from {:step "discover" :yield :text}}]}
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
                     :from {:step "discover" :yield :text}}
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
                     :from {:step "discover" :yield :text}}
                    {:type :source
                     :from {:step "worktree" :output :handoff}}
                    {:type :source
                     :from {:step "reproduce" :output :handoff}}]}]}

Coordinate a modular GitHub bug-triage workflow.

Flow:
- discover one bug+triage issue deterministically via the github extension
- create an issue worktree from origin/master
- attempt reproduction inside the worktree
- hand the structured reproduction report to a post-reproduction classification step
- the post-reproduction step either:
  - requests the minimum additional information and relabels to waiting, or
  - publishes the reproduction branch, comments with the branch link, and relabels to fix

Notes:
- This workflow remains intentionally linear at the orchestration layer.
- The `discover` step is a deterministic `:invoke` step using `github/find-issue` with labels `["bug" "triage"]` — no AI session or sampling during issue selection.
- `gh-bug-reproduce` reads the full issue body itself via `gh issue view`; the discover handoff supplies the minimum required: issue number, title, URL, and worktree description.
- It uses delegated yielded text for the immediate next ask and delegated structured `:handoff` outputs for stable machine-facing cross-workflow data.
- `post-repro` receives the reproduction report as its immediate ask and also receives original request context, upstream discover text and delegated handoffs, and a constrained reproduction transcript tail as support context.
- Use the issue worktree as authoritative for all reproduction activity after creation.
- This workflow classifies and hands off; it does not create a Munera task, implement a fix, or create a PR.
