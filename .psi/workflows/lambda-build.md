---
name: lambda-build
description: Build a lambda expression
---
{:steps [{:name "compile-1"
          :type :delegate
          :target "lambda-compiler"
          :prompt-string {:type :template
                          :text "compile a lambda for: {{input}}"
                          :vars {"input" {:from :workflow-input
                                           :path [:input]}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "decompile"
          :type :delegate
          :target "lambda-decompiler"
          :prompt-string {:type :template
                          :text "decompile the lambda expression: {{input}}"
                          :vars {"input" {:from {:step "compile-1" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "compile-1" :yield :text}}]}
         {:name "compile-2"
          :type :delegate
          :target "lambda-compiler"
          :prompt-string {:type :template
                          :text "compile a lambda for: {{input}}"
                          :vars {"input" {:from {:step "decompile" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "decompile" :yield :text}}]}]}

Iteratively compile and refine a lambda expression through target-authored delegate steps that preserve the original request as carried context while chaining each prior yielded text result into the next ask.
