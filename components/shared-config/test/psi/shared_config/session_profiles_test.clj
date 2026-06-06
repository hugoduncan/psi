(ns psi.shared-config.session-profiles-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [psi.ai.model-registry :as model-registry]
   [psi.shared-config.session-profiles :as session-profiles])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-session-profiles-test-"
                                      (into-array FileAttribute []))))

(defn- user-config-file-in [dir]
  (io/file dir ".psi" "agent" "config.edn"))

(defn- project-file-in [dir]
  (io/file dir ".psi" "project.edn"))

(defn- project-local-file-in [dir]
  (io/file dir ".psi" "project.local.edn"))

(defn- write-edn! [file value]
  (.mkdirs (.getParentFile file))
  (spit file (pr-str value)))

(defn- with-user-home [dir f]
  (let [old-home (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" (.getAbsolutePath dir))
      (f)
      (finally
        (System/setProperty "user.home" old-home)))))

(defn- profile [profiles profile-name]
  (get profiles profile-name))

(deftest effective-profile-definitions-test
  ;; Tests profile-specific deep merge across existing config files without
  ;; changing ordinary flat :agent-session config resolution semantics.
  (testing "deep-merges only :agent-session :session-profiles with project-local precedence"
    (let [home (tmp-dir)
          work (tmp-dir)]
      (write-edn! (user-config-file-in home)
                  {:agent-session {:thinking-level :low
                                   :session-profiles
                                   {:coding {:model-provider "openai"
                                             :model-id "gpt-5.5"
                                             :thinking-level :medium
                                             :speed-mode :normal}
                                    :review {:thinking-level :high}}}})
      (write-edn! (project-file-in work)
                  {:agent-session {:thinking-level :high
                                   :session-profiles
                                   {:coding {:speed-mode :fast}
                                    :planning {:model-provider "anthropic"
                                               :model-id "claude-opus-4-8"}}}})
      (write-edn! (project-local-file-in work)
                  {:agent-session {:session-profiles
                                   {:coding {:effort-override :xhigh}
                                    :planning {:thinking-level :high}}}})
      (with-user-home home
        (fn []
          (is (= {:coding {:model-provider "openai"
                           :model-id "gpt-5.5"
                           :thinking-level :medium
                           :speed-mode :fast
                           :effort-override :xhigh}
                  :planning {:model-provider "anthropic"
                             :model-id "claude-opus-4-8"
                             :thinking-level :high}
                  :review {:thinking-level :high}}
                 (session-profiles/effective-profile-definitions (.getAbsolutePath work)))))))))

(deftest resolve-profile-test
  ;; Tests supported field filtering, concrete setting materialization, and
  ;; invalid profile diagnostics using the real built-in model registry.
  (testing "valid partial profiles ignore unknown keys and materialize model identity"
    (let [resolved (session-profiles/resolve-profile
                    :coding
                    {:model-provider "openai"
                     :model-id "gpt-5.5"
                     :thinking-level :medium
                     :speed-mode :fast
                     :effort-override nil
                     :temperature 0.1})]
      (is (:valid? resolved))
      (is (= {:provider :openai :id "gpt-5.5"}
             (select-keys (:model (:settings resolved)) [:provider :id])))
      (is (= :medium (:thinking-level (:settings resolved))))
      (is (= :fast (:speed-mode (:settings resolved))))
      (is (contains? (:settings resolved) :effort-override)
          "explicit nil effort remains a concrete present setting")
      (is (nil? (:effort-override (:settings resolved))))
      (is (= [:temperature] (:ignored-keys resolved)))
      (is (= ["model openai/gpt-5.5" "thinking medium" "speed fast" "effort none"]
             (:readable-settings resolved)))))

  (testing "speed-only profile is valid"
    (is (:valid? (session-profiles/resolve-profile :fast {:speed-mode :fast}))))

  (testing "reserved :clear profile is invalid even with concrete settings"
    (let [resolved (session-profiles/resolve-profile :clear {:speed-mode :fast})]
      (is (false? (:valid? resolved)))
      (is (= [:reserved-profile-name]
             (mapv :reason (:diagnostics resolved))))))

  (testing "empty and unknown-key-only profiles are invalid"
    (is (= [:no-concrete-settings]
           (mapv :reason (:diagnostics (session-profiles/resolve-profile :empty {})))))
    (is (= [:no-concrete-settings]
           (mapv :reason (:diagnostics (session-profiles/resolve-profile :noise {:temperature 0.1}))))))

  (testing "invalid model identity and enum values return diagnostics and no partial settings"
    (let [resolved (session-profiles/resolve-profile
                    :bad
                    {:model-provider "openai"
                     :thinking-level :ultra
                     :speed-mode :turbo
                     :effort-override :max})]
      (is (false? (:valid? resolved)))
      (is (empty? (:settings resolved)))
      (is (= [:incomplete-model-identity
              :invalid-thinking-level
              :invalid-speed-mode
              :invalid-effort-override]
             (mapv :reason (:diagnostics resolved))))))

  (testing "unknown provider/model pair is invalid through the real registry lookup"
    (let [resolved (session-profiles/resolve-profile
                    :bad-model
                    {:model-provider "openai" :model-id "does-not-exist"})]
      (is (false? (:valid? resolved)))
      (is (= [:unknown-model] (mapv :reason (:diagnostics resolved)))))))

(deftest effective-profiles-test
  ;; Tests the public read+resolve entry point with real file IO and registry.
  (testing "returns valid and invalid profile records in deterministic name order"
    (let [home (tmp-dir)
          work (tmp-dir)]
      (write-edn! (user-config-file-in home)
                  {:agent-session {:session-profiles
                                   {:review {:thinking-level :high}
                                    :clear {:speed-mode :fast}
                                    :coding {:model-provider "openai"
                                             :model-id "gpt-5.5"}}}})
      (with-user-home home
        (fn []
          (model-registry/init! {})
          (let [profiles (session-profiles/effective-profiles (.getAbsolutePath work))]
            (is (= [:clear :coding :review] (keys profiles)))
            (is (false? (:valid? (profile profiles :clear))))
            (is (:valid? (profile profiles :coding)))
            (is (:valid? (profile profiles :review)))))))))

(deftest find-valid-profile-test
  ;; Tests the small state-free selection helper used by commands/workflows.
  (let [profiles (session-profiles/resolve-profiles
                  {:coding {:model-provider "openai" :model-id "gpt-5.5"}
                   :empty {}
                   :review {:thinking-level :high}})]
    (testing "returns the valid resolved profile"
      (is (= :coding (get-in (session-profiles/find-valid-profile profiles :coding)
                             [:profile :name]))))
    (testing "unknown profile errors include available valid names"
      (is (= {:ok? false
              :error :unknown-profile
              :profile-name :missing
              :available [:coding :review]}
             (session-profiles/find-valid-profile profiles :missing))))
    (testing "invalid profile errors include diagnostics and available names"
      (let [result (session-profiles/find-valid-profile profiles :empty)]
        (is (false? (:ok? result)))
        (is (= :invalid-profile (:error result)))
        (is (= [:coding :review] (:available result)))
        (is (= [:no-concrete-settings]
               (mapv :reason (get-in result [:profile :diagnostics]))))))))