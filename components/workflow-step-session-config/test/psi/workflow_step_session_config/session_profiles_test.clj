(ns psi.workflow-step-session-config.session-profiles-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.step-test-support :as support]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- workflow-run-for
  [ctx definitions run-opts]
  (swap! (:state* ctx)
         (fn [state]
           (let [state (reduce (fn [s definition]
                                 (let [[s' _ _] (workflow-registry/register-definition s definition)]
                                   s'))
                               state
                               definitions)
                 [state' _ _] (workflow-runtime/create-run state run-opts)]
             state')))
  (workflow-runtime/workflow-run-in @(:state* ctx) (:run-id run-opts)))

(defn- profile-record
  [name settings]
  {:name name
   :status :valid
   :valid? true
   :settings settings
   :readable-settings []
   :diagnostics []})

(defn- invalid-profile-record
  [profile-name reason]
  {:name profile-name
   :status :invalid
   :valid? false
   :settings {}
   :readable-settings []
   :diagnostics [{:field :settings
                  :reason reason
                  :message (name reason)}]})

(defn- profile-snapshot
  [profiles]
  {:profiles profiles
   :valid-profile-names (->> profiles
                             (keep (fn [[profile-name profile]]
                                     (when (:valid? profile) profile-name)))
                             vec)
   :invalid-profile-names (->> profiles
                               (keep (fn [[profile-name profile]]
                                       (when-not (:valid? profile) profile-name)))
                               vec)})

(def base-inherited-defaults
  {:model {:provider "anthropic" :id "claude-parent"}
   :prompt-mode :lambda
   :tool-defs []
   :skills []
   :thinking-level :off
   :speed-mode :normal
   :effort-override :high})

(deftest resolve-step-session-config-applies-profile-snapshot-test
  ;; Tests workflow :session-profile resolution from the stored run snapshot,
  ;; including explicit override precedence and nil effort presence.
  (testing "session steps merge explicit overrides above profile settings above inherited defaults"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:definition-id "profiled-session"
                      :name "profiled-session"
                      :steps [{:name "plan"
                               :type :session
                               :session-profile :coding
                               :model "gpt-5"
                               :thinking-level :high
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          snapshot (profile-snapshot
                    {:coding (profile-record :coding
                                             {:model {:provider "anthropic" :id "claude-profile"}
                                              :thinking-level :low
                                              :speed-mode :fast
                                              :effort-override nil})})
          workflow-run (workflow-run-for ctx [definition]
                                         {:definition-id "profiled-session"
                                          :run-id "run-profiled-session"
                                          :parent-session-id session-id
                                          :inherited-defaults base-inherited-defaults
                                          :session-profile-snapshot snapshot
                                          :workflow-input {:input "build it"}})
          config (workflow-step-session-config/resolve-step-session-config
                  ctx session-id workflow-run "plan")]
      (is (= {:provider "openai" :id "gpt-5"} (:model config))
          "explicit step model overrides the profile model")
      (is (= :high (:thinking-level config))
          "explicit step thinking-level overrides the profile thinking-level")
      (is (= :fast (:speed-mode config))
          "profile speed overrides the inherited default")
      (is (contains? config :effort-override)
          "profile-derived nil effort is preserved by key presence")
      (is (nil? (:effort-override config))
          "explicit nil effort clears inherited effort"))))

(deftest resolve-delegate-step-session-profile-test
  ;; Tests delegate steps read canonical profile config from [:delegate :session]
  ;; and project profile-derived speed/effort into child inherited defaults.
  (testing "delegate step profile config participates in effective config and child snapshot projection"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:definition-id "profiled-delegate"
                      :name "profiled-delegate"
                      :steps [{:name "delegate-build"
                               :type :delegate
                               :target "child"
                               :prompt-string "Build it"
                               :session-profile :coding
                               :thinking-level :medium}]}
          snapshot (profile-snapshot
                    {:coding (profile-record :coding
                                             {:model {:provider "anthropic" :id "claude-profile"}
                                              :thinking-level :low
                                              :speed-mode :fast
                                              :effort-override nil})})
          workflow-run (workflow-run-for ctx [definition]
                                         {:definition-id "profiled-delegate"
                                          :run-id "run-profiled-delegate"
                                          :parent-session-id session-id
                                          :inherited-defaults base-inherited-defaults
                                          :session-profile-snapshot snapshot
                                          :workflow-input {:input "build it"}})
          config (workflow-step-session-config/resolve-step-session-config
                  ctx session-id workflow-run "delegate-build")
          child-snapshot (workflow-step-session-config/effective-config->snapshot
                          config (:inherited-defaults workflow-run))]
      (is (= {:provider "anthropic" :id "claude-profile"} (:model config)))
      (is (= :medium (:thinking-level config))
          "explicit delegate thinking-level overrides the profile")
      (is (= :fast (:speed-mode child-snapshot))
          "profile-derived speed outranks the parent snapshot for delegated children")
      (is (contains? child-snapshot :effort-override)
          "profile-derived nil effort remains present in the delegated snapshot")
      (is (nil? (:effort-override child-snapshot))
          "profile-derived nil effort clears the parent inherited effort"))))

(deftest resolve-step-session-config-profile-errors-test
  ;; Tests invalid profile requests fail during step config resolution, before
  ;; any attempt child session can be created by the caller.
  (testing "unknown profile fails with available profile names"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:definition-id "unknown-profile"
                      :steps [{:name "plan"
                               :type :session
                               :session-profile :missing
                               :contributions [{:type :source :from :workflow-input}]}]}
          workflow-run (workflow-run-for ctx [definition]
                                         {:definition-id "unknown-profile"
                                          :run-id "run-unknown-profile"
                                          :parent-session-id session-id
                                          :inherited-defaults base-inherited-defaults
                                          :session-profile-snapshot (profile-snapshot
                                                                     {:coding (profile-record :coding
                                                                                              {:speed-mode :fast})})})
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (workflow-step-session-config/resolve-step-session-config
                           ctx session-id workflow-run "plan")))]
      (is (= :unknown-session-profile (:reason (ex-data ex))))
      (is (= [:coding] (:available-profile-names (ex-data ex))))))

  (testing "invalid profile fails with diagnostics"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:definition-id "invalid-profile"
                      :steps [{:name "plan"
                               :type :session
                               :session-profile :empty
                               :contributions [{:type :source :from :workflow-input}]}]}
          workflow-run (workflow-run-for ctx [definition]
                                         {:definition-id "invalid-profile"
                                          :run-id "run-invalid-profile"
                                          :parent-session-id session-id
                                          :inherited-defaults base-inherited-defaults
                                          :session-profile-snapshot (profile-snapshot
                                                                     {:empty (invalid-profile-record :empty
                                                                                                     :no-concrete-settings)})})
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (workflow-step-session-config/resolve-step-session-config
                           ctx session-id workflow-run "plan")))]
      (is (= :invalid-session-profile (:reason (ex-data ex))))
      (is (= [:no-concrete-settings]
             (mapv :reason (:diagnostics (ex-data ex)))))
      (is (= ["no-concrete-settings"]
             (mapv :message (:diagnostics (ex-data ex))))))))

(deftest effective-config->snapshot-preserves-task-207-fallback-test
  ;; Tests no-profile workflows keep task-207 speed/effort inheritance.
  (testing "absent effective speed and effort fall back to the parent run snapshot"
    (let [effective-config {:model {:provider "anthropic" :id "claude-effective"}
                            :prompt-mode :lambda
                            :tool-defs []
                            :skills []
                            :thinking-level :medium}
          snapshot (workflow-step-session-config/effective-config->snapshot
                    effective-config base-inherited-defaults)]
      (is (= :normal (:speed-mode snapshot)))
      (is (= :high (:effort-override snapshot)))
      (is (= workflow-step-session-config/inherited-defaults-snapshot-keys
             (set (keys snapshot)))))))
