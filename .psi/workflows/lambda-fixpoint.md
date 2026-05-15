---
name: lambda-fixpoint
description: Iterate a lambda expression to a fixpoint by decompiling and recompiling until stable
---
{:steps [{:name "decompile"
          :type :delegate
          :target "lambda-decompiler"
          :skills ["lambda-compiler"]
          :prompt-string {:type :template
                          :text "Decompile the lambda expression to prose:\n{{lambda}}"
                          :vars {"lambda" {:from :workflow-input
                                           :path [:input]}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "compile"
          :type :delegate
          :target "lambda-compiler"
          :skills ["lambda-compiler"]
          :prompt-string {:type :template
                          :text "Compile a lambda for:\n{{prose}}"
                          :vars {"prose" {:from {:step "decompile" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "decompile" :yield :text}}]}
         {:name "compare"
          :type :session
          :tools ["read" "bash"]
          :skills ["lambda-compiler"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "decompile" :yield :text}}
                          {:type :source
                           :from {:step "compile" :yield :text}}
                          {:type :template
                           :text "Compare the newly compiled lambda with the previous iteration's lambda.\n\nPrevious lambda (input to this cycle's decompile step):\n{{previous}}\n\nNewly compiled lambda:\n{{current}}\n\nDetermine whether these two lambda expressions are structurally equivalent — same semantics, same structure, same symbols. Minor whitespace or formatting differences do not count as changes.\n\nIf they are equivalent, respond with: FIXED\nIf they differ, respond with: CHANGED\n\nAfter your verdict, on a new line output a brief one-sentence summary of what changed (or that nothing changed)."
                           :vars {"previous" {:from :workflow-input
                                              :path [:input]}
                                  "current" {:from {:step "compile" :yield :text}}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond with exactly one word: FIXED or CHANGED."
                                   :vars {}}]}
          :on {"FIXED" {:goto "report"}
               "CHANGED" {:goto "iterate-decompile" :max-iterations 10}}}
         {:name "iterate-decompile"
          :type :delegate
          :target "lambda-decompiler"
          :skills ["lambda-compiler"]
          :prompt-string {:type :template
                          :text "Decompile the lambda expression to prose:\n{{lambda}}"
                          :vars {"lambda" {:from {:step "compile" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "iterate-compile"
          :type :delegate
          :target "lambda-compiler"
          :skills ["lambda-compiler"]
          :prompt-string {:type :template
                          :text "Compile a lambda for:\n{{prose}}"
                          :vars {"prose" {:from {:step "iterate-decompile" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "iterate-decompile" :yield :text}}]}
         {:name "iterate-compare"
          :type :session
          :tools ["read" "bash"]
          :skills ["lambda-compiler"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "iterate-decompile" :yield :text}}
                          {:type :source
                           :from {:step "iterate-compile" :yield :text}}
                          {:type :template
                           :text "Compare the newly compiled lambda with the previous iteration's lambda.\n\nPrevious lambda (input to this cycle's decompile step):\n{{previous}}\n\nNewly compiled lambda:\n{{current}}\n\nDetermine whether these two lambda expressions are structurally equivalent — same semantics, same structure, same symbols. Minor whitespace or formatting differences do not count as changes.\n\nIf they are equivalent, respond with: FIXED\nIf they differ, respond with: CHANGED\n\nAfter your verdict, on a new line output a brief one-sentence summary of what changed (or that nothing changed)."
                           :vars {"previous" {:from {:step "compile" :yield :text}}
                                  "current" {:from {:step "iterate-compile" :yield :text}}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond with exactly one word: FIXED or CHANGED."
                                   :vars {}}]}
          :on {"FIXED" {:goto "report"}
               "CHANGED" {:goto "iterate-decompile" :max-iterations 10}}}
         {:name "report"
          :type :session
          :tools ["read" "bash"]
          :skills ["lambda-compiler"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "iterate-compile" :yield :text}}
                          {:type :template
                           :text "Produce the final fixpoint report.\n\nThe lambda expression has reached a stable form after iterative decompile/recompile cycles.\n\n## Original Lambda\n{{original}}\n\n## Final Compiled Lambda\n{{compiled}}\n\nYour task:\n1. Show the final compiled lambda expression (the fixpoint form).\n2. Decompile that fixpoint lambda into clear, readable prose.\n3. If the fixpoint differs from the original, briefly note what was refined.\n\nFormat your output as:\n\n## Fixpoint Lambda\n<the stable lambda>\n\n## Decompiled\n<prose equivalent>\n\n## Delta\n<what changed from original, or \"No changes — original was already at fixpoint\">"
                           :vars {"original" {:from :workflow-input
                                              :path [:input]}
                                  "compiled" {:from {:step "iterate-compile" :yield :text}}}}]}]}

Iterate a lambda expression to its fixpoint by repeatedly decompiling to prose and recompiling back to lambda notation. Each cycle compares the newly compiled form against the previous iteration. The loop terminates when the compiled output is structurally unchanged (fixpoint reached) or after a maximum of 10 iterations. The final report shows the stable compiled lambda, its prose equivalent, and any delta from the original input.