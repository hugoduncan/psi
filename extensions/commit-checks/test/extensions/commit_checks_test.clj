(ns extensions.commit-checks-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [extensions.commit-checks :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- temp-dir []
  (.getCanonicalPath (doto (java.io.File/createTempFile "psi-commit-checks" "")
                       (.delete)
                       (.mkdirs))))

(defn- write-config! [workspace-dir cfg]
  (let [f (io/file workspace-dir ".psi" "commit-checks.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str cfg))
    (.getCanonicalPath f)))

(deftest init-registers-handler-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})]
    (sut/init api)
    (is (= 1 (count (get-in @state [:handlers "git_commit_created"]))))))

(deftest missing-config-does-nothing-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc"})
      (is (= [] (:messages @state))))))

(deftest all-success-does-not-send-prompt-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "ok" :cmd ["bash" "-lc" "exit 0"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc"})
      (is (= [] (:messages @state))))))

(deftest failures-send-one-prompt-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "bad-1" :cmd ["bash" "-lc" "echo bad1 && exit 1"]}
                               {:id "ok" :cmd ["bash" "-lc" "echo ok && exit 0"]}
                               {:id "bad-2" :cmd ["bash" "-lc" "echo bad2 1>&2 && exit 2"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [messages (:messages @state)
            prompt   (first (filter #(= "extension-prompt" (:custom-type %)) messages))
            notice   (first (filter #(= "commit-checks" (:custom-type %)) messages))]
        (is (some? prompt))
        (is (some? notice))
        (is (.contains (str (:content prompt)) "workspace-dir: "))
        (is (.contains (str (:content prompt)) "session-id: s1"))
        (is (.contains (str (:content prompt)) "commit: abc123"))
        (is (.contains (str (:content prompt)) "## bad-1"))
        (is (.contains (str (:content prompt)) "## bad-2"))
        (is (not (.contains (str (:content prompt)) "## ok")))))))

(deftest handler-prefers-workspace-dir-over-cwd-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)
        wrong-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "pwd-check" :cmd ["bash" "-lc" "pwd && exit 1"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1"
                :workspace-dir workspace-dir
                :cwd wrong-dir
                :head "abc123"})
      (let [prompt (first (filter #(= "extension-prompt" (:custom-type %)) (:messages @state)))]
        (is (some? prompt))
        (is (.contains (str (:content prompt)) (str "workspace-dir: " workspace-dir)))
        (is (.contains (str (:content prompt)) workspace-dir))
        (is (not (.contains (str (:content prompt)) (str "workspace-dir: " wrong-dir))))))))

(deftest prompt-output-is-truncated-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :max-output-chars 20
                    :commands [{:id "long" :cmd ["bash" "-lc" "printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' 1>&2; exit 1"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [prompt (first (filter #(= "extension-prompt" (:custom-type %)) (:messages @state)))]
        (is (some? prompt))
        (is (.contains (str (:content prompt)) "[output truncated to 20 chars]"))))))
