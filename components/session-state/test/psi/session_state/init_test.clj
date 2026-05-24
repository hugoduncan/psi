(ns psi.session-state.init-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.prompt-registry.root-storage :as prompt-storage]
   [psi.session-state.init :as init]
   [psi.session-state.model :as model]
   [psi.session-state.state :as state]))

(deftest initialize-new-session-state-test
  (let [current-sd (assoc (model/initial-session {:worktree-path "/tmp/parent"})
                          :model {:provider "p" :id "m"}
                          :thinking-level :high
                          :skill-ids ["s"]
                          :prompt-contribution-ids ["p2" "p1"]
                          :prompt-contributions [{:id "stale" :content "stale"}]
                          :tool-defs [{:name "bash"}])
        state0     {:root-registries {:prompt-contributions {:entries-by-id {"p1" {:id "p1"
                                                                                   :extension-id "/ext/a"
                                                                                   :value {:id "p1" :ext-path "/ext/a" :section "A" :content "First" :enabled true}}
                                                                             "p2" {:id "p2"
                                                                                   :extension-id "/ext/b"
                                                                                   :value {:id "p2" :ext-path "/ext/b" :section "B" :content "Second" :enabled true}}}}}}
        state1     (init/initialize-new-session-state
                    state0 current-sd
                    {:new-session-id "child-1"
                     :worktree-path "/tmp/new"
                     :session-name "new child"
                     :spawn-mode :new-root
                     :session-file "/tmp/session.ndedn"})
        sd1        (get-in state1 (state/session-data-path "child-1"))]
    (testing "session identity and worktree are initialized in extracted state"
      (is (= "child-1" (:session-id sd1)))
      (is (= "/tmp/new" (:worktree-path sd1)))
      (is (= "new child" (:session-name sd1)))
      (is (= :new-root (:spawn-mode sd1))))
    (testing "selected inherited lower-level session fields are carried"
      (is (= {:provider "p" :id "m"} (:model sd1)))
      (is (= :high (:thinking-level sd1)))
      (is (= ["s"] (:skill-ids sd1)))
      (is (= ["p2" "p1"] (:prompt-contribution-ids sd1)))
      (is (= [{:id "stale" :content "stale"}] (:prompt-contributions sd1)))
      (is (= [{:id "p1" :ext-path "/ext/a" :section "A" :content "First" :enabled true}
              {:id "p2" :ext-path "/ext/b" :section "B" :content "Second" :enabled true}]
             (prompt-storage/list-contributions state1 sd1)))
      (is (nil? (:skills sd1)))
      (is (= [{:name "bash"}] (:tool-defs sd1))))
    (testing "journal, telemetry, and flush slots are initialized"
      (let [persistence (get-in state1 [:agent-session :sessions "child-1" :persistence])]
        (is (= [] (:journal persistence)))
        (is (false? (get-in persistence [:flush-state :flushed?])))
        (is (= "/tmp/session.ndedn"
               (some-> persistence :flush-state :session-file str))))
      (is (= {:ctx nil} (get-in state1 [:agent-session :sessions "child-1" :turn]))))))

(deftest initialize-resume-missing-state-test
  (let [current-sd {:session-id "sid-1" :worktree-path "/tmp/ws"}
        state1     (init/initialize-resume-missing-state {} current-sd "/tmp/missing.ndedn")
        persistence (get-in state1 [:agent-session :sessions "sid-1" :persistence])]
    (is (= "/tmp/missing.ndedn" (get-in state1 (conj (state/session-data-path "sid-1") :session-file))))
    (is (= [] (:journal persistence)))
    (is (false? (get-in persistence [:flush-state :flushed?])))
    (is (= "/tmp/missing.ndedn"
           (some-> persistence :flush-state :session-file str)))))

(deftest initialize-resumed-session-state-test
  (let [current-sd (assoc (model/initial-session {:worktree-path "/tmp/source"})
                          :skill-ids ["keep"]
                          :prompt-contribution-ids ["from-current"]
                          :prompt-contributions [{:id "stale" :content "stale"}])
        entries    [{:kind :session-info :data {:name "resumed name"}}
                    {:kind :message :data {:message {:role "user" :content "hi"}}}]
        state0     {:root-registries {:prompt-contributions {:entries-by-id {"from-current" {:id "from-current"
                                                                                             :extension-id "/ext/current"
                                                                                             :value {:id "from-current" :ext-path "/ext/current" :section "Resume" :content "Current" :enabled true}}}}}}
        state1     (init/initialize-resumed-session-state
                    state0
                    current-sd
                    {:session-id "sid-r"
                     :session-path "/tmp/resume.ndedn"
                     :header {:worktree-path "/tmp/resume" :parent-session-id "parent-1" :parent-session "/tmp/parent.ndedn"}
                     :entries entries
                     :model {:provider "prov" :id "m"}
                     :thinking-level :medium})
        sd1        (get-in state1 (state/session-data-path "sid-r"))]
    (is (= "resumed name" (:session-name sd1)))
    (is (= "/tmp/resume" (:worktree-path sd1)))
    (is (= "parent-1" (:parent-session-id sd1)))
    (is (= "/tmp/parent.ndedn" (:parent-session-path sd1)))
    (is (= {:provider "prov" :id "m"} (:model sd1)))
    (is (= :medium (:thinking-level sd1)))
    (is (= ["keep"] (:skill-ids sd1)))
    (is (= ["from-current"] (:prompt-contribution-ids sd1)))
    (is (= [{:id "stale" :content "stale"}] (:prompt-contributions sd1)))
    (is (= [{:id "from-current" :ext-path "/ext/current" :section "Resume" :content "Current" :enabled true}]
           (prompt-storage/list-contributions state1 sd1)))
    (is (= entries (get-in state1 (state/session-journal-path "sid-r"))))
    (let [persistence (get-in state1 [:agent-session :sessions "sid-r" :persistence])]
      (is (true? (get-in persistence [:flush-state :flushed?])))
      (is (= "/tmp/resume.ndedn"
             (some-> persistence :flush-state :session-file str))))))

(deftest initialize-forked-session-state-test
  (let [parent-sd {:session-id "parent"
                   :session-file "/tmp/parent.ndedn"
                   :worktree-path "/tmp/ws"
                   :model {:provider "prov" :id "m"}
                   :thinking-level :low
                   :prompt-contribution-ids ["p2" "p1"]
                   :prompt-contributions [{:id "stale" :content "stale"}]}
        branch-entries [{:kind :message :data {:message {:role "user" :content "hi"}}}]
        state0 {:agent-session {:sessions {"parent" {:agent-ctx ::agent :sc-session-id ::sc}}}
                :root-registries {:prompt-contributions {:entries-by-id {"p1" {:id "p1"
                                                                               :extension-id "/ext/a"
                                                                               :value {:id "p1" :ext-path "/ext/a" :section "A" :content "First" :enabled true}}
                                                                         "p2" {:id "p2"
                                                                               :extension-id "/ext/b"
                                                                               :value {:id "p2" :ext-path "/ext/b" :section "B" :content "Second" :enabled true}}}}}}
        state1 (init/initialize-forked-session-state
                state0 parent-sd
                {:new-session-id "fork-1"
                 :branch-entries branch-entries
                 :session-file "/tmp/fork.ndedn"})
        sd1    (get-in state1 (state/session-data-path "fork-1"))]
    (is (= "fork-1" (:session-id sd1)))
    (is (= "/tmp/ws" (:worktree-path sd1)))
    (is (= "parent" (:parent-session-id sd1)))
    (is (= "/tmp/parent.ndedn" (:parent-session-path sd1)))
    (is (= :fork-head (:spawn-mode sd1)))
    (is (= ["p2" "p1"] (:prompt-contribution-ids sd1)))
    (is (= [{:id "stale" :content "stale"}] (:prompt-contributions sd1)))
    (is (= [{:id "p1" :ext-path "/ext/a" :section "A" :content "First" :enabled true}
            {:id "p2" :ext-path "/ext/b" :section "B" :content "Second" :enabled true}]
           (prompt-storage/list-contributions state1 sd1)))
    (is (= branch-entries (get-in state1 (state/session-journal-path "fork-1"))))
    (let [persistence (get-in state1 [:agent-session :sessions "fork-1" :persistence])]
      (is (true? (get-in persistence [:flush-state :flushed?])))
      (is (= "/tmp/fork.ndedn"
             (some-> persistence :flush-state :session-file str))))
    (is (= ::agent (get-in state1 [:agent-session :sessions "fork-1" :agent-ctx])))
    (is (= ::sc (get-in state1 [:agent-session :sessions "fork-1" :sc-session-id])))))
