---
name: allium-check
description: Check the implementation of an allium spec
---
{:steps [{:name "check"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :template
                           :text "Use the allium-compiler skill.\nCheck the implementation of the given allium-spec.\n\nInput:\n{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}]}
