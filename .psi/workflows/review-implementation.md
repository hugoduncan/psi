---
name: review-implementation
description: Review a Munera task implementation, record terse notes, execute added follow-up steps, and repeat until no new actionable feedback remains
---
{:steps [{:name "review-task-implementation"
          :type :delegate
          :target "review-step"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :skill {:value "task-implementation-review"}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "review-task-tests"
          :type :delegate
          :target "review-step"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :skill {:value "task-test-review"}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "review-task-implementation" :yield :text}}]}
         {:name "review-test-shape"
          :type :delegate
          :target "review-step"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :skill {:value "test-shaper"}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "review-task-implementation" :yield :text}}
                    {:type :source
                     :from {:step "review-task-tests" :yield :text}}]}
         {:name "review-code-shape"
          :type :delegate
          :target "review-step"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :skill {:value "code-shaper"}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "review-task-implementation" :yield :text}}
                    {:type :source
                     :from {:step "review-task-tests" :yield :text}}
                    {:type :source
                     :from {:step "review-test-shape" :yield :text}}]}]}

Run four sequential `review-step` passes against a Munera task, in order:
1. `task-implementation-review` — correctness and completeness of the implementation
2. `task-test-review` — correctness and completeness of the tests
3. `test-shaper` — clarity, signal, and robustness of the tests
4. `code-shaper` — simplicity, consistency, and robustness of the code

Each pass loops internally (review → follow-up → judge) until it produces no new actionable feedback. Prior pass results are forwarded as context so later passes avoid duplicating already-addressed issues.

Input shape: `{:input "munera/open/003-foo"}`

Child sessions inherit the invoking session's worktree — no worktree setup is needed. For pipeline use with structured handoff data, use `review-implementation-in-worktree` instead.