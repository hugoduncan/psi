(ns psi.app-runtime.footer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.footer :as footer]))

(deftest footer-model-from-data-builds-canonical-lines-test
  (let [home  (System/getProperty "user.home")
        model (footer/footer-model-from-data
               {:psi.agent-session/worktree-path (str home "/projects/hugoduncan/psi/psi-main")
                :psi.agent-session/git-branch "master"
                :psi.agent-session/session-name "xhig"
                :psi.agent-session/session-display-name "xhig"
                :psi.agent-session/usage-input 172000
                :psi.agent-session/usage-output 17000
                :psi.agent-session/usage-cache-read 5200000
                :psi.agent-session/usage-cache-write 1200
                :psi.agent-session/usage-cost-total 1.444
                :psi.agent-session/context-fraction 0.319
                :psi.agent-session/context-window 272000
                :psi.agent-session/auto-compaction-enabled true
                :psi.agent-session/model-provider "openai-codex"
                :psi.agent-session/model-id "gpt-5.3-codex"
                :psi.agent-session/model-reasoning true
                :psi.agent-session/thinking-level :xhigh
                :psi.agent-session/effective-reasoning-effort "high"
                :psi.ui/statuses [{:extension-id "b" :text "TS+ESL,Prett"}
                                  {:extension-id "a" :text "Clojure-LSP\nclojure-lsp"}]})]
    (is (= "~/projects/hugoduncan/psi/psi-main (master) • xhig"
           (get-in model [:footer/lines :path-line])))
    (is (= ["↑172k" "↓17k" "CR5.2M" "CW1.2k" "$1.444" "31.9%/272k (auto)"]
           (get-in model [:footer/usage :parts])))
    (is (= "(openai-codex) gpt-5.3-codex • thinking high"
           (get-in model [:footer/model :text])))
    (is (= "Clojure-LSP clojure-lsp TS+ESL,Prett"
           (get-in model [:footer/lines :status-line])))
    (is (= ["a" "b"]
           (mapv :status/extension-id (:footer/statuses model))))))

(deftest footer-model-from-data-prefers-session-display-name-test
  (testing "derived display name wins when explicit session name is absent"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/git-branch "master"
                  :psi.agent-session/session-name nil
                  :psi.agent-session/session-display-name "Investigate failing tests"
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/model-provider "openai"
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning true
                  :psi.agent-session/thinking-level :high
                  :psi.ui/statuses []})]
      (is (= "/repo/project (master) • Investigate failing tests"
             (get-in model [:footer/lines :path-line]))))))

(deftest footer-model-from-data-handles-missing-usage-test
  (testing "footer still produces context/model text when usage counters are zero or absent"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/context-fraction nil
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/auto-compaction-enabled false
                  :psi.agent-session/model-provider "openai"
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning true
                  :psi.agent-session/thinking-level :off
                  :psi.ui/statuses []})]
      (is (= ["?/400k"]
             (get-in model [:footer/usage :parts])))
      (is (= "(openai) gpt-5.3-codex • thinking off"
             (get-in model [:footer/model :text])))
      (is (= "?/400k (openai) gpt-5.3-codex • thinking off"
             (get-in model [:footer/lines :stats-line])))
      (is (nil? (get-in model [:footer/lines :status-line]))))))

(deftest footer-model-from-data-includes-session-activity-line-test
  (testing "footer exposes compact backend-owned session activity text for multi-session contexts"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/model-provider "openai"
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning false
                  :psi.ui/statuses []}
                 {:context-sessions [{:session-id "s1"
                                      :display-name "main"
                                      :runtime-state "running"}
                                     {:session-id "s2"
                                      :display-name "helper"
                                      :runtime-state "waiting"}
                                     {:session-id "s3"
                                      :session-name "notes"
                                      :runtime-state "waiting"}]})]
      (is (= "sessions: waiting helper, notes · running main"
             (get-in model [:footer/session-activity :line])))
      (is (= [{:state "waiting" :labels ["helper" "notes"]}
              {:state "running" :labels ["main"]}]
             (get-in model [:footer/session-activity :buckets])))
      (is (= "sessions: waiting helper, notes · running main"
             (get-in model [:footer/lines :session-activity-line]))))))

(deftest footer-model-from-data-orders-parent-before-child-in-session-activity-line-test
  (testing "footer session activity uses parent-first tree order instead of raw recency order"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/model-provider "openai"
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning false
                  :psi.ui/statuses []}
                 {:context-sessions [{:session-id "child"
                                      :parent-session-id "parent"
                                      :display-name "helper"
                                      :runtime-state "waiting"}
                                     {:session-id "parent"
                                      :display-name "main"
                                      :runtime-state "running"}
                                     {:session-id "s3"
                                      :display-name "notes"
                                      :runtime-state "waiting"}]})]
      (is (= "sessions: waiting helper, notes · running main"
             (get-in model [:footer/session-activity :line]))))))

(deftest footer-model-from-data-includes-all-canonical-phase-groups-test
  (testing "footer preserves all sessions across waiting/running/retrying/compacting buckets"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/model-provider "openai"
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning false
                  :psi.ui/statuses []}
                 {:context-sessions [{:session-id "s1" :display-name "main" :runtime-state "waiting"}
                                     {:session-id "s2" :display-name "helper" :runtime-state "running"}
                                     {:session-id "s3" :display-name "summarize" :runtime-state "retrying"}
                                     {:session-id "s4" :display-name "prune-history" :runtime-state "compacting"}]})]
      (is (= "sessions: waiting main · running helper · retrying summarize · compacting prune-history"
             (get-in model [:footer/session-activity :line]))))))

(deftest footer-model-from-data-ignores-keyword-sentinels-test
  (testing "keyword sentinel values are treated as absent instead of seqable collections/strings"
    (let [model (footer/footer-model-from-data
                 {:psi.agent-session/worktree-path "/repo/project"
                  :psi.agent-session/git-branch :pathom/unknown
                  :psi.agent-session/session-display-name :pathom/unknown
                  :psi.agent-session/context-window 400000
                  :psi.agent-session/model-provider :pathom/unknown
                  :psi.agent-session/model-id "gpt-5.3-codex"
                  :psi.agent-session/model-reasoning false
                  :psi.agent-session/thinking-level :off
                  :psi.ui/statuses :pathom/unknown})]
      (is (= "/repo/project"
             (get-in model [:footer/lines :path-line])))
      (is (= "(no-provider) gpt-5.3-codex"
             (get-in model [:footer/model :text])))
      (is (nil? (get-in model [:footer/lines :status-line]))))))
