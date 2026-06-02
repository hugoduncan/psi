(ns psi.agent-session.workflow-child-session-context-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.context]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session-core/create-context (test-support/safe-context-opts opts))
         sd (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- call-private-create-workflow-child-session!
  [ctx parent-session-id request]
  ((var-get #'psi.agent-session.context/create-workflow-child-session!)
   ctx parent-session-id request))

(deftest create-workflow-child-session-shared-realization-edge-attempt-shape-test
  (testing "create-workflow-child-session! applies the authoritative contract to the wider attempt caller shape"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          child-session-id "attempt-child-1"
          request {:child-session-id child-session-id
                   :session-name "workflow plan attempt"
                   :system-prompt "system"
                   :prompt-mode :lambda
                   :response-mode :non-streaming
                   :logprobs true
                   :top-logprobs 4
                   :tool-ids []
                   :thinking-level :off
                   :model {:provider "openai" :id "gpt-5"}
                   :skills []
                   :developer-prompt "dev"
                   :developer-prompt-source :explicit
                   :preloaded-messages [{:role "user" :content [{:type :text :text "hello child"}]}]
                   :cache-breakpoints #{:system :tools}
                   :prompt-component-selection {:components #{} :tool-names [] :skill-names [] :extension-prompt-contributions []}
                   :workflow-run-id "run-1"
                   :workflow-step-id "plan"
                   :workflow-attempt-id "attempt-1"
                   :workflow-owned? true}
          result (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)
          agent-msgs (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx child-session-id)))]
      (is (= {:psi.agent-session/session-id child-session-id} result))
      (is (= parent-session-id (:parent-session-id child-sd)))
      (is (= [] (:skill-ids child-sd)))
      (is (nil? (:skills child-sd)))
      (is (= "run-1" (:workflow-run-id child-sd)))
      (is (= "plan" (:workflow-step-id child-sd)))
      (is (= "attempt-1" (:workflow-attempt-id child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= :non-streaming (:response-mode child-sd)))
      (is (true? (:logprobs-enabled child-sd)))
      (is (= 4 (:top-logprobs child-sd)))
      (is (= {:provider "openai" :id "gpt-5"} (:model child-sd)))
      (is (= :explicit (:developer-prompt-source child-sd)))
      (is (= "dev" (:developer-prompt child-sd)))
      (is (= [{:role "user" :content [{:type :text :text "hello child"}]}] agent-msgs))
      (is (some? (ss/agent-ctx-in ctx child-session-id)))
      (is (some? (ss/sc-session-id-in ctx child-session-id)))
      (session-core/shutdown-context! ctx))))

(deftest create-workflow-child-session-shared-realization-edge-judge-shape-test
  (testing "create-workflow-child-session! applies the same authoritative contract to the narrower judge caller shape"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          child-session-id "judge-child-1"
          request {:child-session-id child-session-id
                   :session-name "workflow judge"
                   :system-prompt "judge system"
                   :tool-ids []
                   :thinking-level :off
                   :preloaded-messages [{:role "user" :content "judge this"}]
                   :workflow-owned? true}
          result (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)
          agent-msgs (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx child-session-id)))]
      (is (= {:psi.agent-session/session-id child-session-id} result))
      (is (= parent-session-id (:parent-session-id child-sd)))
      (is (= [] (:skill-ids child-sd)))
      (is (nil? (:skills child-sd)))
      (is (= "workflow judge" (:session-name child-sd)))
      (is (= "judge system" (:system-prompt child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= [{:role "user" :content "judge this"}] agent-msgs))
      (is (some? (ss/agent-ctx-in ctx child-session-id)))
      (is (some? (ss/sc-session-id-in ctx child-session-id)))
      (session-core/shutdown-context! ctx))))

(deftest create-workflow-child-session-inherited-snapshot-flag-survives-threading-test
  ;; Task 207 T5: end-to-end seam test for the `:inherited-snapshot?` contract.
  ;; The R4/R5 child-state snapshot isolation is gated on `:inherited-snapshot?`;
  ;; its producer (attempts) and consumer (child-session-base-state*) each have a
  ;; unit test, but the threading hop between them —
  ;; `create-workflow-child-session!` (`context.clj`) →
  ;; `:session/create-child` (`session_lifecycle.clj`) →
  ;; `child-session-base-state*` — was untested: both endpoints stay green even
  ;; if the flag were dropped on the wire. This drives the real chain and proves
  ;; the flag survives it.
  (testing "with :inherited-snapshot? true, nil snapshot-governed fields resolve
            to the initial-session default and a post-invoke live-parent mutation
            does NOT leak in (flag survives context → session_lifecycle → builder)"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          ;; Live parent carries non-default snapshot-governed values that would
          ;; leak via the (or arg parent-sd) fallback if the flag did not cross
          ;; the wire. :prompt-mode :prose ≠ the :lambda initial-session default.
          _ (swap! (:state* ctx) update-in
                   [:agent-session :sessions parent-session-id :data]
                   merge {:model {:provider "prov" :id "live-model"}
                          :prompt-mode :prose
                          :speed-mode :flex
                          :effort-override :low})
          child-session-id "snapshot-flag-child"
          request {:child-session-id child-session-id
                   :session-name "workflow snapshot child"
                   :system-prompt "system"
                   :tool-ids []
                   :thinking-level :off
                   :skills []
                   :workflow-run-id "run-1"
                   :workflow-step-id "plan"
                   :workflow-attempt-id "attempt-1"
                   :workflow-owned? true
                   :inherited-snapshot? true
                   ;; nil-at-invoke snapshot-governed fields (resolver emits none)
                   :model nil
                   :prompt-mode nil
                   :speed-mode nil
                   :effort-override nil}
          _ (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)]
      (is (nil? (:model child-sd))
          ":model = initial-session default (nil), not the live parent model — flag survived threading")
      (is (= :lambda (:prompt-mode child-sd))
          ":prompt-mode = initial-session default (:lambda), not live parent (:prose)")
      (is (nil? (:speed-mode child-sd))
          ":speed-mode = initial-session nil default, no live parent leak")
      (is (nil? (:effort-override child-sd))
          ":effort-override = initial-session nil default, no live parent leak")
      (session-core/shutdown-context! ctx)))

  (testing "control: WITHOUT :inherited-snapshot? the same nil-supplied fields
            fall back to the live parent through the full chain — proving the
            distinction is carried by the flag, not lost on the wire"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx) update-in
                   [:agent-session :sessions parent-session-id :data]
                   merge {:model {:provider "prov" :id "live-model"}
                          :prompt-mode :prose
                          :speed-mode :flex
                          :effort-override :low})
          child-session-id "no-flag-child"
          request {:child-session-id child-session-id
                   :session-name "workflow non-snapshot child"
                   :system-prompt "system"
                   :tool-ids []
                   :thinking-level :off
                   :skills []
                   :workflow-owned? true
                   :model nil
                   :prompt-mode nil
                   :speed-mode nil
                   :effort-override nil}
          _ (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)]
      (is (= {:provider "prov" :id "live-model"} (:model child-sd))
          "no flag → live parent model inherited through the chain")
      (is (= :prose (:prompt-mode child-sd)))
      (is (= :flex (:speed-mode child-sd)))
      (is (= :low (:effort-override child-sd)))
      (session-core/shutdown-context! ctx))))

(deftest create-workflow-child-session-invalid-request-fails-locally-test
  (testing "realization edge rejects malformed workflow child-session create requests clearly"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          ex (try
               (call-private-create-workflow-child-session!
                ctx
                parent-session-id
                {:child-session-id "bad-child"
                 :session-name "workflow child"
                 :tool-ids :not-a-vector
                 :thinking-level :off})
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :request (:stage (ex-data ex))))
      (is (= :psi.agent-session.context/create-workflow-child-session!
             (:caller (ex-data ex))))
      (session-core/shutdown-context! ctx))))
