---
name: plan-build-review
description: Plan, build, and review code changes
---
{:steps [{:name "plan"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "build"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Execute this plan:\n\n{{plan}}\n\nOriginal request: {{original}}"
                           :vars {"plan" {:from {:step "plan" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}
         {:name "review"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the following implementation:\n\n{{implementation}}\n\nOriginal request: {{original}}"
                           :vars {"implementation" {:from {:step "build" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}]}

Coordinate a plan → build → review cycle using the converged target workflow
grammar.

This example is the primary compact teaching surface for multi-step inline
session authoring with explicit `:contributions` and prior-step yield
references.
