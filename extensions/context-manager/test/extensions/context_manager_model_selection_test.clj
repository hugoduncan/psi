(ns extensions.context-manager-model-selection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]))

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
