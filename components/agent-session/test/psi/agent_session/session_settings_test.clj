(ns psi.agent-session.session-settings-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-settings :as settings]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- run-cycle-model
  "Seed the session with `model` and `scoped-models`, run a single
   `cycle-model-in!` in `direction`, and return the persisted session `:model`.

   `scoped-models` is a vector of `:model` maps; each is wrapped as
   `{:model <m> :thinking-level :off}`. Leaves only the per-case current
   `:model`/`:scoped-models`/`:direction` as distinct inputs; all
   context/session/seed ceremony is shared here."
  [ctx-opts {:keys [model scoped-models direction]}]
  (let [ctx (session/create-context (test-support/safe-context-opts ctx-opts))
        sd  (session/new-session-in! ctx nil {})
        sid (:session-id sd)]
    (ss/apply-root-state-update-in!
     ctx
     (ss/session-update sid #(assoc %
                                    :model model
                                    :scoped-models (mapv (fn [m]
                                                           {:model m :thinking-level :off})
                                                         scoped-models))))
    (settings/cycle-model-in! ctx sid direction)
    (:model (ss/get-session-data-in ctx sid))))

;; ── Unsupported OAuth runtime models ─────────────────────────────────────────

(deftest cycle-model-in-skips-unsupported-runtime-models-test
  ;; Tests that model cycling does not select scoped models that resolve to an
  ;; explicitly unsupported runtime policy for the active auth context.
  (testing "skips OAuth-unsupported scoped models"
    (let [original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "gpt-5.6" :reasoning true}
          selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (is (= selected
             (run-cycle-model {:oauth-ctx (test-support/oauth-openai-ctx)}
                              {:model original
                               :scoped-models [original skipped selected]
                               :direction :forward}))))))

(deftest cycle-model-in-skips-unsupported-runtime-models-backward-test
  ;; Tests that reverse model cycling skips scoped models that resolve to an
  ;; explicitly unsupported runtime policy for the active auth context.
  (testing "skips OAuth-unsupported scoped models while cycling backward"
    (let [selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          skipped  {:provider "openai" :id "gpt-5.6" :reasoning true}
          original {:provider "openai" :id "gpt-5.5" :reasoning true}]
      (is (= selected
             (run-cycle-model {:oauth-ctx (test-support/oauth-openai-ctx)}
                              {:model original
                               :scoped-models [selected skipped original]
                               :direction :backward}))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unsupported-test
  ;; Tests that cycling is a no-op when every scoped candidate is unsupported
  ;; for the active auth context.
  (testing "does not select an unsupported scoped model"
    (let [original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "gpt-5.6" :reasoning true}]
      (is (= original
             (run-cycle-model {:oauth-ctx (test-support/oauth-openai-ctx)}
                              {:model original
                               :scoped-models [skipped]
                               :direction :forward}))))))

;; ── Unknown/unresolvable runtime models ──────────────────────────────────────

(deftest cycle-model-in-skips-unknown-runtime-models-test
  ;; Tests that model cycling does not select scoped models that do not resolve
  ;; to any runtime model for the active auth context.
  (testing "skips unknown scoped models"
    (let [original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}
          selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (is (= selected
             (run-cycle-model {}
                              {:model original
                               :scoped-models [original skipped selected]
                               :direction :forward}))))))

(deftest cycle-model-in-skips-unknown-runtime-models-backward-test
  ;; Tests that reverse model cycling does not select scoped models that do not
  ;; resolve to any runtime model for the active auth context.
  (testing "skips unknown scoped models while cycling backward"
    (let [selected {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}
          original {:provider "openai" :id "gpt-5.5" :reasoning true}]
      (is (= selected
             (run-cycle-model {}
                              {:model original
                               :scoped-models [selected skipped original]
                               :direction :backward}))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unknown-test
  ;; Tests that cycling is a no-op when every scoped candidate is unresolvable.
  (testing "does not select an unknown scoped model"
    (let [original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}]
      (is (= original
             (run-cycle-model {}
                              {:model original
                               :scoped-models [skipped]
                               :direction :forward}))))))

(deftest cycle-model-in-preserves-current-model-when-all-candidates-unknown-backward-test
  ;; Tests that reverse cycling is a no-op when every scoped candidate is
  ;; unresolvable.
  (testing "does not select an unknown scoped model while cycling backward"
    (let [original {:provider "openai" :id "gpt-5.5" :reasoning true}
          skipped  {:provider "openai" :id "definitely-not-a-model" :reasoning true}]
      (is (= original
             (run-cycle-model {}
                              {:model original
                               :scoped-models [skipped]
                               :direction :backward}))))))
