(ns psi.agent-session.workflow-file-authoring-session-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-authoring-session :as authoring-session]))

(def ^:private step-name->step-ref
  {"discover" {:step-id "step-1-discover" :idx 0}})

(deftest compile-step-input-bindings-validation-table-test
  (testing "malformed projection validation remains clear across representative cases"
    (doseq [{:keys [label step expected-re]} [{:label "unsupported projection operator"
                                               :step {:session {:input {:from :workflow-input
                                                                        :projection :tail}}}
                                               :expected-re #"Unsupported `:projection`"}
                                              {:label "path projection requires vector"
                                               :step {:session {:input {:from :workflow-input
                                                                        :projection {:path :not-a-vector}}}}
                                               :expected-re #"expected vector path"}
                                              {:label "projection path entries must be scalar path parts"
                                               :step {:session {:input {:from :workflow-input
                                                                        :projection {:path [:ok {:bad true}]}}}}
                                               :expected-re #"path entries must be keyword, string, or int"}
                                              {:label "projection rejects unexpected keys"
                                               :step {:session {:input {:from :workflow-input
                                                                        :projection {:path [:task]
                                                                                     :extra true}}}}
                                               :expected-re #"unexpected keys"}
                                              {:label "source rejects unexpected keys"
                                               :step {:session {:input {:from {:step "discover"
                                                                               :kind :accepted-result
                                                                               :extra true}}}}
                                               :expected-re #"unexpected keys .*:session source"}
                                              {:label "binding rejects unexpected keys"
                                               :step {:session {:input {:from :workflow-input
                                                                        :projection :text
                                                                        :extra true}}}
                                               :expected-re #"unexpected keys"}]]
      (let [{:keys [error]}
            (authoring-session/compile-step-input-bindings
             step
             "step-0"
             step-name->step-ref
             1)]
        (is (string? error) label)
        (is (re-find expected-re error) label)))))
