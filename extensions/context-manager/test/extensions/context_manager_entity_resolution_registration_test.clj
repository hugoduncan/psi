(ns extensions.context-manager-entity-resolution-registration-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.ai.model-selection :as model-selection]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(defn- setup-api
  []
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/context_manager.clj"})]
    (context-manager/init api)
    {:api api :state state}))

(deftest entity-resolution-registered-handler-threads-real-api-test
  (testing "the registered handler threads the real api through the default
            collaborators (no injected stubs) to a well-formed envelope"
    ;; Every other augmenter test injects stub :select-model/:run-helper with
    ;; an empty {} api, so the default branch
    ;; (`#(default-select-model api %)` / `#(default-run-helper api %)`) that
    ;; the registered 2-arity handler actually uses is never exercised. Drive
    ;; the real registered handler with the real nullable api and NO
    ;; collaborators. An empty model catalog makes the real
    ;; default-select-model → resolve-selection path return nil, so the
    ;; handler must reach the deterministic no-local-model :no-op — proving
    ;; api is threaded into default-select-model through the registration
    ;; seam, not the stub-injected path.
    (with-redefs [model-selection/catalog-view (fn [] {:candidates []})]
      (let [{:keys [state]} (setup-api)
            handler (get-in @state [:turn-augmenters "entity-resolution" :handler])
            env (handler {:turn-augmentation/session-id "s1"
                          :turn-augmentation/effective-cwd "/repo"
                          :turn-augmentation/user-text "please look at the resolver"
                          :turn-augmentation/history []})]
        (is (= :no-op (:turn-augmentation/status env)))
        (is (= "no local model" (:turn-augmentation/diagnostic env))
            "real default-select-model ran through the threaded api → no-op")))))
