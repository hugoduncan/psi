(ns psi.agent-session.session-settings-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-settings :as settings]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

(deftest cycle-model-in-skips-unsupported-runtime-models-test
  ;; Tests that model cycling does not select scoped models that resolve to an
  ;; explicitly unsupported runtime policy for the active auth context.
  (testing "skips OAuth-unsupported scoped models"
    (let [ctx       (session/create-context (test-support/safe-context-opts {:oauth-ctx (test-support/oauth-openai-ctx)}))
          sd        (session/new-session-in! ctx nil {})
          sid       (:session-id sd)
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped   {:provider "openai" :id "gpt-5.6" :reasoning true}
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model original :thinking-level :off}
                                                      {:model skipped :thinking-level :off}
                                                      {:model selected :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :forward)
      (is (= selected (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-skips-unsupported-runtime-models-backward-test
  ;; Tests that reverse model cycling skips scoped models that resolve to an
  ;; explicitly unsupported runtime policy for the active auth context.
  (testing "skips OAuth-unsupported scoped models while cycling backward"
    (let [ctx       (session/create-context (test-support/safe-context-opts {:oauth-ctx (test-support/oauth-openai-ctx)}))
          sd        (session/new-session-in! ctx nil {})
          sid       (:session-id sd)
          selected  {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          skipped   {:provider "openai" :id "gpt-5.6" :reasoning true}
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model selected :thinking-level :off}
                                                      {:model skipped :thinking-level :off}
                                                      {:model original :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :backward)
      (is (= selected (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unsupported-test
  ;; Tests that cycling is a no-op when every scoped candidate is unsupported
  ;; for the active auth context.
  (testing "does not select an unsupported scoped model"
    (let [ctx       (session/create-context (test-support/safe-context-opts {:oauth-ctx (test-support/oauth-openai-ctx)}))
          sd        (session/new-session-in! ctx nil {})
          sid       (:session-id sd)
          original  {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped   {:provider "openai" :id "gpt-5.6" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model skipped :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :forward)
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-skips-unknown-runtime-models-test
  ;; Tests that model cycling does not select scoped models that do not resolve
  ;; to any runtime model for the active auth context.
  (testing "skips unknown scoped models"
    (let [ctx      (session/create-context (test-support/safe-context-opts {}))
          sd       (session/new-session-in! ctx nil {})
          sid      (:session-id sd)
          original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}
          selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model original :thinking-level :off}
                                                      {:model skipped :thinking-level :off}
                                                      {:model selected :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :forward)
      (is (= selected (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-skips-unknown-runtime-models-backward-test
  ;; Tests that reverse model cycling does not select scoped models that do not
  ;; resolve to any runtime model for the active auth context.
  (testing "skips unknown scoped models while cycling backward"
    (let [ctx      (session/create-context (test-support/safe-context-opts {}))
          sd       (session/new-session-in! ctx nil {})
          sid      (:session-id sd)
          selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}
          original {:provider "openai" :id "gpt-5.5" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model selected :thinking-level :off}
                                                      {:model skipped :thinking-level :off}
                                                      {:model original :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :backward)
      (is (= selected (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unknown-test
  ;; Tests that cycling is a no-op when every scoped candidate is unresolvable.
  (testing "does not select an unknown scoped model"
    (let [ctx      (session/create-context (test-support/safe-context-opts {}))
          sd       (session/new-session-in! ctx nil {})
          sid      (:session-id sd)
          original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model skipped :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :forward)
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unknown-backward-test
  ;; Tests that reverse cycling is a no-op when every scoped candidate is
  ;; unresolvable.
  (testing "does not select an unknown scoped model while cycling backward"
    (let [ctx      (session/create-context (test-support/safe-context-opts {}))
          sd       (session/new-session-in! ctx nil {})
          sid      (:session-id sd)
          original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}]
      (ss/apply-root-state-update-in!
       ctx
       (ss/session-update sid #(assoc %
                                      :model original
                                      :scoped-models [{:model skipped :thinking-level :off}])))
      (settings/cycle-model-in! ctx sid :backward)
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))
