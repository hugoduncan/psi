(ns psi.rpc-model-scope-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as agent-test-support]
   [psi.ai.model-registry :as model-registry]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.session.command-pickers]
   [psi.rpc-test-support :as support]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- run-cycle-model
  "Seed `scoped-models` and current `model` on the session, run a single
   `cycle_model` request in `direction` (\"next\"/\"prev\"), and return
   `{:response <cycle_model response frame> :persisted <session :model>}`.

   Leaves only the per-case `:scoped-models`/`:direction`/current-`:model` as the
   distinct inputs; all handshake/loop/frame-filter ceremony is shared here."
  [ctx sid {:keys [model scoped-models direction]}]
  (let [state   (atom {:transport {:ready? true :pending {}}
                       :connection {}})
        handler (support/make-handler ctx state)
        _       (ss/apply-root-state-update-in!
                 ctx
                 (ss/session-update sid #(assoc %
                                                :model model
                                                :scoped-models scoped-models)))
        input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"" direction "\" :session-id \"" sid "\"}}\n")
        {:keys [out-lines]} (support/run-loop input handler state)
        frames  (support/parse-frames out-lines)
        resp    (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
    {:response  resp
     :persisted (:model (ss/get-session-data-in ctx sid))}))

;; ── Direct set_model ─────────────────────────────────────────────────────────

(deftest rpc-set-model-rejects-unsupported-runtime-model-test
  (testing "direct set_model rejects unsupported runtime models without persisting"
    (let [[ctx sid] (support/create-session-context {:oauth-ctx (agent-test-support/oauth-openai-ctx)})
          original  (:model (ss/get-session-data-in ctx sid))
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"m1\" :kind :request :op \"set_model\" :params {:provider \"openai\" :model-id \"gpt-5.6\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          error     (some #(when (and (= :error (:kind %))
                                      (= "set_model" (:op %))) %)
                          frames)]
      (is (some? error))
      (is (= "m1" (:id error)))
      (is (= "request/unsupported-model" (:error-code error)))
      (is (= (agent-test-support/unsupported-runtime-model-message)
             (:error-message error)))
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))

;; ── Picker-backed selection ──────────────────────────────────────────────────

(deftest rpc-picker-model-selection-success-test
  (testing "picker-backed model selection preserves omitted-scope/default persistence semantics"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-rpc-picker-model-scope-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          local-f  (project-prefs/project-local-preferences-file cwd)
          [ctx sid] (support/create-session-context {:cwd cwd})
          emitted  (atom [])
          emit!    (fn [event payload]
                     (swap! emitted conj {:event event :payload payload}))]
      (psi.rpc.session.command-pickers/handle-model-selection!
       ctx sid
       (fn [ctx' provider id]
         (rpc.session/resolve-model ctx' provider id))
       emit!
       {:provider "openai" :id "gpt-5.3-codex"})
      (is (= [{:event "command-result"
               :payload {:type "text"
                         :message (model-registry/model-set-message
                                   {:provider "openai" :id "gpt-5.3-codex"})}}]
             @emitted))
      (is (= {:provider "openai"
              :id "gpt-5.3-codex"
              :reasoning true}
             (:model (ss/get-session-data-in ctx sid))))
      (is (.exists local-f))
      (is (= "openai" (get-in (read-string (slurp local-f)) [:agent-session :model-provider])))
      (is (= "gpt-5.3-codex" (get-in (read-string (slurp local-f)) [:agent-session :model-id]))))))

(deftest rpc-picker-model-selection-rejects-unsupported-runtime-model-test
  (testing "picker-backed model selection rejects unsupported resolved models without persisting"
    (let [[ctx sid] (support/create-session-context {:oauth-ctx (agent-test-support/oauth-openai-ctx)})
          original  (:model (ss/get-session-data-in ctx sid))
          emitted   (atom [])
          emit!     (fn [event payload]
                      (swap! emitted conj {:event event :payload payload}))]
      (psi.rpc.session.command-pickers/handle-model-selection!
       ctx sid
       (fn [ctx' provider id]
         (rpc.session/resolve-model ctx' provider id))
       emit!
       {:provider "openai" :id "gpt-5.6"})
      (is (= original (:model (ss/get-session-data-in ctx sid))))
      (is (= [{:event "command-result"
               :payload {:type "unsupported_model"
                         :message (agent-test-support/unsupported-runtime-model-message)
                         :provider "openai"
                         :model-id "gpt-5.6"}}]
             @emitted)))))

;; ── cycle_model — unsupported OAuth candidates ───────────────────────────────

(deftest rpc-cycle-model-skips-unsupported-forward-test
  (testing "cycle_model skips unsupported scoped models"
    (let [[ctx sid] (support/create-session-context {:oauth-ctx (agent-test-support/oauth-openai-ctx)})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model original :thinking-level :off}
                            {:model {:provider "openai" :id "gpt-5.6" :reasoning true}
                             :thinking-level :off}
                            {:model selected :thinking-level :off}]
            :direction     "next"})]
      (is (some? response))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in response [:data :model])))
      (is (= selected persisted)))))

(deftest rpc-cycle-model-skips-unsupported-backward-test
  (testing "cycle_model skips unsupported scoped models backward"
    (let [[ctx sid] (support/create-session-context {:oauth-ctx (agent-test-support/oauth-openai-ctx)})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model selected :thinking-level :off}
                            {:model {:provider "openai" :id "gpt-5.6" :reasoning true}
                             :thinking-level :off}
                            {:model original :thinking-level :off}]
            :direction     "prev"})]
      (is (some? response))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in response [:data :model])))
      (is (= selected persisted)))))

;; ── cycle_model — unknown/unresolvable candidates ────────────────────────────

(deftest rpc-cycle-model-skips-unknown-forward-test
  (testing "cycle_model skips unknown scoped models"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model original :thinking-level :off}
                            {:model {:provider "openai" :id "definitely-not-a-model" :reasoning true}
                             :thinking-level :off}
                            {:model selected :thinking-level :off}]
            :direction     "next"})]
      (is (some? response))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in response [:data :model])))
      (is (= selected persisted)))))

(deftest rpc-cycle-model-skips-unknown-backward-test
  (testing "cycle_model skips unknown scoped models backward"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model selected :thinking-level :off}
                            {:model {:provider "openai" :id "definitely-not-a-model" :reasoning true}
                             :thinking-level :off}
                            {:model original :thinking-level :off}]
            :direction     "prev"})]
      (is (some? response))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in response [:data :model])))
      (is (= selected persisted)))))

(deftest rpc-cycle-model-preserves-current-when-all-unknown-forward-test
  (testing "cycle_model preserves current model when all candidates are unknown"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model {:provider "openai" :id "definitely-not-a-model" :reasoning true}
                             :thinking-level :off}]
            :direction     "next"})]
      (is (some? response))
      (is (= {:provider "openai" :id "gpt-5.5"}
             (get-in response [:data :model])))
      (is (= original persisted)))))

(deftest rpc-cycle-model-preserves-current-when-all-unknown-backward-test
  (testing "cycle_model preserves current model when all candidates are unknown backward"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          {:keys [response persisted]}
          (run-cycle-model
           ctx sid
           {:model         original
            :scoped-models [{:model {:provider "openai" :id "definitely-not-a-model" :reasoning true}
                             :thinking-level :off}]
            :direction     "prev"})]
      (is (some? response))
      (is (= {:provider "openai" :id "gpt-5.5"}
             (get-in response [:data :model])))
      (is (= original persisted)))))
