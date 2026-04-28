(ns psi.agent-session.workflow-file-authoring-session-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-authoring-session :as authoring-session]))

(def ^:private step-name->step-ref
  {"discover" {:step-id "step-1-discover" :idx 0}})

(def ^:private input-validation-cases
  [{:label "unsupported projection operator"
    :step {:session {:input {:from :workflow-input
                             :projection :tail}}}
    :expected-re #"unsupported `:projection`"}
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
    :expected-re #"unexpected keys .*`:projection`"}
   {:label "source rejects unexpected keys"
    :step {:session {:input {:from {:step "discover"
                                    :kind :accepted-result
                                    :extra true}}}}
    :expected-re #"unexpected keys .*`:session source`"}
   {:label "binding rejects unexpected keys"
    :step {:session {:input {:from :workflow-input
                             :projection :text
                             :extra true}}}
    :expected-re #"unexpected keys .*`:session input`"}])

(def ^:private override-validation-cases
  [{:label "tools override requires vector of strings"
    :step {:session {:tools ["read" :bash]}}
    :expected-re #"expected vector of strings in `:session tools`"}
   {:label "skills override requires vector of strings"
    :step {:session {:skills "testing-best-practices"}}
    :expected-re #"expected vector of strings in `:session skills`"}
   {:label "system prompt override requires string"
    :step {:session {:system-prompt :strict}}
    :expected-re #"expected string in `:session system-prompt`"}
   {:label "thinking level override requires canonical level"
    :step {:session {:thinking-level :ultra}}
    :expected-re #"expected one of :off, :minimal, :low, :medium, :high, :xhigh in `:session thinking-level`"}])

(deftest compile-step-input-bindings-validation-table-test
  (testing "malformed projection/source validation remains clear across representative cases"
    (doseq [{:keys [label step expected-re]} input-validation-cases]
      (let [{:keys [error]}
            (authoring-session/compile-step-input-bindings
             step
             "step-0"
             step-name->step-ref
             1)]
        (is (string? error) label)
        (is (re-find expected-re error) label)))))

(deftest compile-step-session-overrides-validation-table-test
  (testing "override validation remains clear across representative cases"
    (doseq [{:keys [label step expected-re]} override-validation-cases]
      (let [{:keys [error]}
            (authoring-session/compile-step-session-overrides step)]
        (is (string? error) label)
        (is (re-find expected-re error) label)))))
