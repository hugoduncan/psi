(ns extensions.context-manager-model-selection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]
   [extensions.context-manager-test-support :refer [base-tp stub]]))

(deftest entity-resolution-no-local-model-no-op-test
  (testing "no local model yields no-op with no helper run"
    (let [calls (atom {})
          env (context-manager/entity-resolution-augmentation
               {} base-tp (stub {:model nil :calls calls}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (= [] (:turn-augmentation/operations env)) "well-formed no-op: no operations")
      (is (= "no local model" (:turn-augmentation/diagnostic env)))
      (is (nil? (:run @calls)) "no helper run attempted"))))

(deftest default-select-model-rejects-cloud-winner-test
  (testing "a cheap-tier cloud model that survives the required filter is rejected (local-only)"
    ;; The required constraints (:supports-text, :latency-tier :low,
    ;; :cost-tier #{:zero :low}) admit cheap cloud models; :locality :local
    ;; is only a strong preference, so a cloud candidate can rank first when
    ;; no local model is configured. default-select-model must still return
    ;; nil so the augmenter runs no cloud helper on the per-turn path.
    (let [cloud-candidate {:provider :openai
                           :id       "cheap-cloud"
                           :name     "Cheap Cloud"
                           :facts    {:supports-text true
                                      :latency-tier  :low
                                      :cost-tier     :low
                                      :locality      :cloud}}]
      ;; Inject the candidate pool via default-select-model's :catalog seam
      ;; (nullable-over-mock: pass a candidate pool as a parameter rather than
      ;; with-redefs-ing the model-registry infrastructure boundary).
      (is (nil? (#'context-manager/default-select-model
                 {:query-session (fn [_ _] {})} "s1"
                 {:candidates [cloud-candidate]}))
          "cloud-only pool must yield nil (→ :no-op, no cloud helper run)"))))

(deftest default-select-model-accepts-local-winner-test
  (testing "a qualifying local model is returned as the top-ranked candidate"
    (let [local-candidate {:provider :ollama
                           :id       "local-q"
                           :name     "Local Q"
                           :facts    {:supports-text true
                                      :latency-tier  :low
                                      :cost-tier     :zero
                                      :locality      :local}}]
      ;; Inject the candidate pool via the :catalog seam (nullable-over-mock).
      (is (= "local-q"
             (:id (#'context-manager/default-select-model
                   {:query-session (fn [_ _] {})} "s1"
                   {:candidates [local-candidate]})))
          "local winner is selected"))))

(deftest helper-model-selection-request-inherits-parent-model-test
  (testing "the parent session's model flows into the request :context
            {:session-model {:provider :id}} (Required behaviour item 2)"
    ;; helper-model-selection-request builds the :session-model context that
    ;; drives resolve-selection's :same-provider-as-session weak preference /
    ;; provider-match tie-break. A model-ctx carrying the parent session's
    ;; provider/id must be threaded through (provider keywordized, id verbatim).
    (let [request (#'context-manager/helper-model-selection-request
                   {:psi.agent-session/model-provider "anthropic"
                    :psi.agent-session/model-id "claude-x"})]
      (is (= {:provider :anthropic :id "claude-x"}
             (get-in request [:context :session-model]))
          "parent provider (keywordized) and id flow into :context :session-model")))

  (testing "a nil parent model yields a nil-valued session-model context"
    (let [request (#'context-manager/helper-model-selection-request nil)]
      (is (= {:provider nil :id nil}
             (get-in request [:context :session-model]))
          "absent parent model → nil provider/id (no inheritance)"))))

(deftest default-select-model-inherits-parent-model-context-test
  (testing "default-select-model queries the parent session for its model and
            selects the matching candidate via the inherited :context"
    ;; Drive the real default-select-model with a :query-session returning a
    ;; concrete parent model. resolve-selection's :mode :resolve pool is
    ;; built from all candidates, but the :same-provider-as-session weak
    ;; preference (fed by the inherited :context) tie-breaks toward the
    ;; parent's provider — proving the query→context wiring rather than the
    ;; happy-path ranking. Two equally-qualifying local candidates from
    ;; different providers; the parent-provider one must win.
    (let [queried  (atom nil)
          matching {:provider :ollama
                    :id       "match"
                    :name     "Match"
                    :facts    {:supports-text true
                               :latency-tier  :low
                               :cost-tier     :zero
                               :locality      :local}}
          other    {:provider :llamafile
                    :id       "other"
                    :name     "Other"
                    :facts    {:supports-text true
                               :latency-tier  :low
                               :cost-tier     :zero
                               :locality      :local}}
          selected (#'context-manager/default-select-model
                    {:query-session
                     (fn [sid keys]
                       (reset! queried {:session-id sid :keys keys})
                       {:psi.agent-session/model-provider "ollama"
                        :psi.agent-session/model-id "match"})}
                    "s1"
                    {:candidates [other matching]})]
      (is (= {:session-id "s1"
              :keys [:psi.agent-session/model-provider
                     :psi.agent-session/model-id]}
             @queried)
          "parent session queried for its model provider/id")
      (is (= "match" (:id selected))
          "the parent-provider candidate wins via the inherited :session-model context"))))

(deftest default-select-model-catches-thrown-selection-test
  (testing "a throwing query-session collapses to nil rather than propagating"
    ;; default-select-model wraps its body in (catch Exception _ nil): this is
    ;; the *only* guard against a thrown selection propagating out of
    ;; entity-resolution-augmentation onto 237's blocking pre-turn path (the
    ;; augmenter wraps only run-helper, not select-model, in its own
    ;; try/catch). A throw from :query-session must yield nil (→ :no-op).
    (is (nil? (#'context-manager/default-select-model
               {:query-session (fn [_ _] (throw (ex-info "boom" {})))} "s1"))
        "thrown query-session must be caught → nil (→ :no-op, not propagated)")))
