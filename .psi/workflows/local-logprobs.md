---
name: local-logprobs
description: Run a prompt with a local model, no tools, non-streaming, and logprobs, then report the perplexity
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
          :tools ["read"]
          :skills ["lambda-compiler"]
          :response-mode :non-streaming
          :logprobs true
          :top-logprobs 3
          :thinking-level :off
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}

         {:name "perplexity"
          :type :invoke
          :operation "logprobs/perplexity"
          :args {:session-id {:from {:step "run" :output :result}
                              :path [:outputs :session-id]}}}

         {:name "report"
          :type :session
          :tools ["read"]
          :contributions [{:type :template
                           :text "Report exactly these two sections and nothing else.\n\n## Reply\n{{reply-text}}\n\n## Perplexity\nPerplexity: {{perplexity}}\nToken count: {{token-count}}"
                           :vars {"reply-text" {:from {:step "run" :output :final-llm-reply}}
                                  "perplexity" {:from {:step "perplexity" :output :result}
                                                :path [:outputs :data :perplexity]}
                                  "token-count" {:from {:step "perplexity" :output :result}
                                                 :path [:outputs :data :token-count]}}}]}]}
