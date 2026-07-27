(ns psi.rpc-model-scope-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.session.command-pickers]
   [psi.rpc-test-support :as support]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]))

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
                         :message "✓ Model set to openai gpt-5.3-codex"}}]
             @emitted))
      (is (= {:provider "openai"
              :id "gpt-5.3-codex"
              :reasoning true}
             (:model (ss/get-session-data-in ctx sid))))
      (is (.exists local-f))
      (is (= "openai" (get-in (read-string (slurp local-f)) [:agent-session :model-provider])))
      (is (= "gpt-5.3-codex" (get-in (read-string (slurp local-f)) [:agent-session :model-id]))))))

(deftest rpc-set-model-scope-test
  (testing "direct set_model rejects unsupported runtime models without persisting"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:openai {:type :oauth
                                             :access "tok"
                                             :refresh "ref"
                                             :expires 99999999999999}}})
          [ctx sid] (support/create-session-context {:oauth-ctx oauth-ctx})
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
      (is (= "Unsupported model: openai gpt-5.6 — gpt-5.6 is not supported for OpenAI OAuth without an evidenced ChatGPT/Codex alias or alternate OAuth-compatible transport"
             (:error-message error)))
      (is (= original (:model (ss/get-session-data-in ctx sid))))))

  (testing "picker-backed model selection rejects unsupported resolved models without persisting"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:openai {:type :oauth
                                             :access "tok"
                                             :refresh "ref"
                                             :expires 99999999999999}}})
          [ctx sid] (support/create-session-context {:oauth-ctx oauth-ctx})
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
                         :message "Unsupported model: openai gpt-5.6 — gpt-5.6 is not supported for OpenAI OAuth without an evidenced ChatGPT/Codex alias or alternate OAuth-compatible transport"
                         :provider "openai"
                         :model-id "gpt-5.6"}}]
             @emitted))))

  (testing "cycle_model skips unsupported scoped models"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:openai {:type :oauth
                                             :access "tok"
                                             :refresh "ref"
                                             :expires 99999999999999}}})
          [ctx sid] (support/create-session-context {:oauth-ctx oauth-ctx})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          _         (ss/apply-root-state-update-in!
                     ctx
                     (ss/session-update sid #(assoc %
                                                    :model original
                                                    :scoped-models [{:model original :thinking-level :off}
                                                                    {:model {:provider "openai"
                                                                             :id "gpt-5.6"
                                                                             :reasoning true}
                                                                     :thinking-level :off}
                                                                    {:model selected :thinking-level :off}])))
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"next\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          resp      (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in resp [:data :model])))
      (is (= selected (:model (ss/get-session-data-in ctx sid))))))

  (testing "cycle_model skips unsupported scoped models backward"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:openai {:type :oauth
                                             :access "tok"
                                             :refresh "ref"
                                             :expires 99999999999999}}})
          [ctx sid] (support/create-session-context {:oauth-ctx oauth-ctx})
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          _         (ss/apply-root-state-update-in!
                     ctx
                     (ss/session-update sid #(assoc %
                                                    :model original
                                                    :scoped-models [{:model selected :thinking-level :off}
                                                                    {:model {:provider "openai"
                                                                             :id "gpt-5.6"
                                                                             :reasoning true}
                                                                     :thinking-level :off}
                                                                    {:model original :thinking-level :off}])))
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"prev\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          resp      (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in resp [:data :model])))
      (is (= selected (:model (ss/get-session-data-in ctx sid))))))

  (testing "cycle_model skips unknown scoped models"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          _         (ss/apply-root-state-update-in!
                     ctx
                     (ss/session-update sid #(assoc %
                                                    :model original
                                                    :scoped-models [{:model original :thinking-level :off}
                                                                    {:model {:provider "openai"
                                                                             :id "definitely-not-a-model"
                                                                             :reasoning true}
                                                                     :thinking-level :off}
                                                                    {:model selected :thinking-level :off}])))
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"next\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          resp      (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in resp [:data :model])))
      (is (= selected (:model (ss/get-session-data-in ctx sid))))))

  (testing "cycle_model skips unknown scoped models backward"
    (let [[ctx sid] (support/create-session-context {})
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          _         (ss/apply-root-state-update-in!
                     ctx
                     (ss/session-update sid #(assoc %
                                                    :model original
                                                    :scoped-models [{:model selected :thinking-level :off}
                                                                    {:model {:provider "openai"
                                                                             :id "definitely-not-a-model"
                                                                             :reasoning true}
                                                                     :thinking-level :off}
                                                                    {:model original :thinking-level :off}])))
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"prev\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          resp      (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= {:provider "anthropic" :id "claude-sonnet-4-6"}
             (get-in resp [:data :model])))
      (is (= selected (:model (ss/get-session-data-in ctx sid))))))

  (testing "cycle_model preserves current model when all candidates are unknown"
    (let [[ctx sid] (support/create-session-context {})
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          _         (ss/apply-root-state-update-in!
                     ctx
                     (ss/session-update sid #(assoc %
                                                    :model original
                                                    :scoped-models [{:model {:provider "openai"
                                                                             :id "definitely-not-a-model"
                                                                             :reasoning true}
                                                                     :thinking-level :off}])))
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"c1\" :kind :request :op \"cycle_model\" :params {:direction \"next\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          resp      (some #(when (and (= :response (:kind %)) (= "cycle_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= {:provider "openai" :id "gpt-5.5"}
             (get-in resp [:data :model])))
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))
