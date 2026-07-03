(ns extensions.context-manager-entity-resolution-flow-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(def ^:private base-tp
  {:turn-augmentation/session-id "s1"
   :turn-augmentation/effective-cwd "/repo"
   :turn-augmentation/user-text "please look at the resolver"
   :turn-augmentation/history []})

(deftest entity-resolution-selected-model-flows-into-run-test
  (testing "the model select-model returns is the one passed to the helper run"
    ;; Single-attempt selection→run wiring (Required behaviour item 3): the
    ;; one top-ranked local candidate select-model returns must be the model
    ;; the helper run actually runs under. Capture run-helper's :model run-opt.
    (let [selected {:provider :ollama :id "qwen-selected"}
          captured (atom :unset)
          env (context-manager/entity-resolution-augmentation
               {} base-tp
               {:select-model (fn [_parent] selected)
                :run-helper   (fn [opts]
                                (reset! captured (:model opts))
                                {:child-session-id "helper-1"
                                 :text "the resolver → x (e; c)"})})]
      (is (= :success (:turn-augmentation/status env)))
      (is (= selected @captured)
          "model returned by select-model is threaded into the helper run-opts")
      (is (= ["helper-1"] (:turn-augmentation/child-session-ids env))
          "helper child-session id reported as provenance"))))
