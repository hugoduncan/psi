(ns psi.agent-session.workflow-file-authoring-session-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-authoring-preload :as authoring-preload]
   [psi.agent-session.workflow-file-authoring-session :as authoring-session]))

(def ^:private default-step-name->step-ref
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

(def ^:private preload-validation-cases
  [{:label "preload requires vector"
    :step {:session {:preload {:from :workflow-input}}}
    :expected-re #"expected vector in `:session preload`"}
   {:label "preload entry requires map"
    :step {:session {:preload [:bad]}}
    :expected-re #"expected map entries in `:session preload`"}
   {:label "preload transcript projection rejects unsupported operator"
    :step {:session {:preload [{:from {:step "discover" :kind :session-transcript}
                                :projection {:type :head :turns 1}}]}}
    :expected-re #"unsupported transcript/message projection"}
   {:label "preload transcript projection requires positive turns"
    :step {:session {:preload [{:from {:step "discover" :kind :session-transcript}
                                :projection {:type :tail :turns 0}}]}}
    :expected-re #"expected positive `:turns`"}
   {:label "preload rejects unknown step names"
    :step {:session {:preload [{:from {:step "missing" :kind :accepted-result}}]}}
    :expected-re #"Unknown step name"}
   {:label "preload rejects forward step references"
    :step {:session {:preload [{:from {:step "discover" :kind :accepted-result}}]}}
    :step-name->step-ref {}
    :current-step-idx 0
    :expected-re #"Unknown step name|Forward step reference"}
   {:label "value preload rejects :full projection"
    :step {:session {:preload [{:from {:step "discover" :kind :accepted-result}
                                :projection :full}]}}
    :expected-re #"value preload supports only `:projection :text`"}])

(deftest compile-step-input-bindings-validation-table-test
  (testing "malformed projection/source validation remains clear across representative cases"
    (doseq [{:keys [label step expected-re]} input-validation-cases]
      (let [{:keys [error]}
            (authoring-session/compile-step-input-bindings
             step
             "step-0"
             default-step-name->step-ref
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

(deftest compile-step-session-preload-validation-table-test
  (testing "preload validation remains clear across representative cases"
    (doseq [{:keys [label step expected-re step-name->step-ref current-step-idx]} preload-validation-cases]
      (let [{:keys [error]}
            (authoring-preload/compile-step-session-preload
             step
             (or step-name->step-ref default-step-name->step-ref)
             (or current-step-idx 1))]
        (is (string? error) label)
        (is (re-find expected-re error) label)))))
