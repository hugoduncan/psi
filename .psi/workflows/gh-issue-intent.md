---
name: gh-issue-intent
description: Find an enhancement issue labeled refine, create a worktree, produce a refined task intent using the task-intent skill, push the branch, post an issue comment, and advance labels
---
{:steps [{:name      "discover"
          :type      :invoke
          :operation "github/find-issue"
          :args      {:labels ["enhancement" "refine"]
                      :input  {:from :workflow-input :path [:input]}}
          :outputs   {:summary {:source :invoke/summary}
                      :data    {:source :invoke/data}}
          :yields    {:type :text :text :summary}}
         {:name "worktree"
          :type :delegate
          :target "gh-issue-create-worktree"
          :prompt-string {:type :template
                          :text "{{discover_report}}"
                          :vars {"discover_report" {:from {:step "discover" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :yield :text}}]}
         {:name "intent"
          :type :delegate
          :target "gh-issue-task-intent"
          :prompt-string {:type :template
                          :text "{{worktree_report}}"
                          :vars {"worktree_report" {:from {:step "worktree" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :yield :text}}
                    {:type :source
                     :from {:step "worktree" :yield :text}}]}
         {:name "publish"
          :type :delegate
          :target "gh-issue-push-intent"
          :prompt-string {:type :template
                          :text "{{intent_report}}"
                          :vars {"intent_report" {:from {:step "intent" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "discover" :yield :text}}
                    {:type :source
                     :from {:step "worktree" :yield :text}}
                    {:type :source
                     :from {:step "intent" :yield :text}}]}
         {:name      "remove-refine"
          :type      :invoke
          :operation "github/remove-label"
          :args      {:number {:from {:step "discover" :output :data} :path [:issue-number]}
                      :labels ["refine"]
                      :target "issue"}}
         {:name      "add-waiting"
          :type      :invoke
          :operation "github/add-label"
          :args      {:number {:from {:step "discover" :output :data} :path [:issue-number]}
                      :labels ["waiting"]
                      :target "issue"}}]}

Coordinate intent-capture for a GitHub enhancement issue labeled `refine`: select the issue, create an issue-specific worktree, apply the task-intent skill to produce a clear and concise problem statement with constraints and success criteria, commit the intent, push the branch, post an issue comment with the refined intent, then remove the `refine` label and add `waiting` to the issue.
