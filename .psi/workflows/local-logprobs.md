---
name: local-logprobs
description: Run a prompt with a local model, no tools, non-streaming, and logprobs, then report the resulting message and logprob results
---
{:steps [{:name "run"
          :type :session
          :model {:type :model-query
                  :require [{:criterion :supports-text
                             :match :true}
                            {:criterion :locality
                             :equals :local}
                            {:criterion :latency-tier
                             :equals :low}
                            {:criterion :cost-tier
                             :one-of [:zero :low]}]
                  :prefer [{:criterion :input-cost
                            :prefer :lower}
                           {:criterion :output-cost
                            :prefer :lower}]}
          :tools []
          :response-mode :non-streaming
          :logprobs true
          :top-logprobs 5
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}

         {:name "report"
          :type :session
          :model {:type :model-query
                  :require [{:criterion :supports-text
                             :match :true}
                            {:criterion :locality
                             :equals :local}
                            {:criterion :latency-tier
                             :equals :low}
                            {:criterion :cost-tier
                             :one-of [:zero :low]}]
                  :prefer [{:criterion :input-cost
                            :prefer :lower}
                           {:criterion :output-cost
                            :prefer :lower}]}
          :tools []
          :response-mode :non-streaming
          :contributions [{:type :template
                           :text "Report exactly these two sections and nothing else.\n\n## Resulting Message\n[copy the assistant message text from the run step]\n\n## Logprob Results\n[copy the logprob results from the run step transcript]\n\nTranscript:\n\n{{transcript}}"
                           :vars {"transcript" {:from {:step "run" :output :transcript}
                                                 :projection :full}}}]}]}