(ns extensions.plan-state-learning-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.plan-state-learning :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- git-log-response
  [sha subject]
  {:psi.extension.tool/content (str sha "\n" subject)
   :psi.extension.tool/is-error false})

(defn- invoke-private
  [sym & args]
  (apply (var-get (ns-resolve 'extensions.plan-state-learning sym)) args))

(defn- set-psl-state!
  [m]
  (reset! (var-get (ns-resolve 'extensions.plan-state-learning 'state)) m))

(deftest init-registers-git-commit-created-handler-test
  (testing "extension registers git_commit_created handler and widget"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "git_commit_created"]))))
      (is (contains? (:workflow-types @state) :psl))
      (is (contains? (:commands @state) "psl"))
      (is (contains? (:widgets @state) "psl"))
      (is (= :above-editor (get-in @state [:widgets "psl" :position])))
      (is (some #(str/includes? (str %) "PSL")
                (get-in @state [:widgets "psl" :lines]))))))

(deftest psl-widget-placement-follows-ui-type-test
  (testing "PSL widget renders below editor in emacs ui"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"
                                :ui-type :emacs})]
      (sut/init api)
      (is (= :below-editor (get-in @state [:widgets "psl" :position]))))))

(deftest workflow-public-display-test
  (testing "workflow public data includes shared display-map shape"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (let [public-data-fn (get-in @state [:workflow-types :psl :public-data-fn])
            public        (public-data-fn {:psl/source-sha "feedbeef"
                                           :psl/subject "docs(agent-session): λ clarify canonical tool history guidance"
                                           :psl/phase :running
                                           :psl/result nil})]
        (is (= {:top-line "… PSL running for feedbee"
                :detail-line "commit: docs(agent-session): λ clarify canonical tool history guidance"
                :action-line "updating munera/mementum state"}
               (:psl/display public)))
        (is (= :running (:psl/phase public)))
        (is (= "feedbeef" (:psl/source-sha public)))))))

(deftest psl-widget-shows-workflow-display-lines-test
  (testing "widget includes workflow public display lines when a PSL run exists"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (swap! state assoc :workflows {"psl-1" {:psi.extension/path "/test/plan_state_learning.clj"
                                              :psi.extension.workflow/id "psl-1"
                                              :psi.extension.workflow/type :psl
                                              :psi.extension.workflow/phase :running
                                              :psi.extension.workflow/running? true
                                              :psi.extension.workflow/done? false
                                              :psi.extension.workflow/error? false
                                              :psi.extension.workflow/error-message nil
                                              :psi.extension.workflow/input {:source-sha "feedbeef"}
                                              :psi.extension.workflow/data {:psl/display {:top-line "… PSL running for feedbee"
                                                                                          :detail-line "commit: test commit"
                                                                                          :action-line "updating munera/mementum state"}}
                                              :psi.extension.workflow/result nil
                                              :psi.extension.workflow/elapsed-ms 0
                                              :psi.extension.workflow/started-at nil}})
      (#'sut/refresh-widget!)
      (let [lines (get-in @state [:widgets "psl" :lines])
            text  (clojure.string/join "\n" (map #(if (map? %) (:text %) (str %)) lines))]
        (is (str/includes? text "PSL"))
        (is (str/includes? text "PSL running for feedbee"))
        (is (str/includes? text "commit: test commit"))))))

(deftest psl-command-lists-workflow-public-display-test
  (testing "/psl reuses workflow public display lines"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (swap! state assoc :workflows {"psl-1" {:psi.extension/path "/test/plan_state_learning.clj"
                                              :psi.extension.workflow/id "psl-1"
                                              :psi.extension.workflow/type :psl
                                              :psi.extension.workflow/phase :running
                                              :psi.extension.workflow/running? true
                                              :psi.extension.workflow/done? false
                                              :psi.extension.workflow/error? false
                                              :psi.extension.workflow/error-message nil
                                              :psi.extension.workflow/input {:source-sha "feedbeef"}
                                              :psi.extension.workflow/data {:psl/source-sha "feedbeef"
                                                                            :psl/subject "docs(agent-session): λ clarify canonical tool history guidance"
                                                                            :psl/phase :running
                                                                            :psl/display {:top-line "… PSL running for feedbee"
                                                                                          :detail-line "commit: docs(agent-session): λ clarify canonical tool history guidance"
                                                                                          :action-line "updating munera/mementum state"}}
                                              :psi.extension.workflow/result nil
                                              :psi.extension.workflow/elapsed-ms 0
                                              :psi.extension.workflow/started-at nil}})
      (let [handler (get-in @state [:commands "psl" :handler])
            out     (with-out-str (handler ""))]
        (is (re-find #"PSL running for feedbee" out))
        (is (re-find #"commit: docs\(agent-session\): λ clarify canonical tool history guidance" out))
        (is (re-find #"updating munera/mementum state" out)))))

  (testing "/psl prints empty-state message when no runs exist"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "psl" :handler])
            out     (with-out-str (handler ""))]
        (is (re-find #"No PSL runs\." out))))))

(deftest handler-skips-self-marker-commits-test
  (testing "handler emits skip message when marker is present"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})
          calls (atom [])]
      (with-redefs [sut/bash! (fn [_ cmd]
                                (swap! calls conj cmd)
                                (if (= cmd "git log -1 --pretty=format:%H%n%s")
                                  (git-log-response "abc1234" "◈ Δ Auto-update munera/mementum state [psi:psl-auto]")
                                  {:psi.extension.tool/content "" :psi.extension.tool/is-error false}))
                    sut/notify! (fn [mutate-fn text]
                                  (mutate-fn 'psi.extension/notify
                                             {:role "assistant"
                                              :content text
                                              :custom-type "plan-state-learning"}))]
        (sut/init api)
        (let [handler (first (get-in @state [:handlers "git_commit_created"]))
              result  (handler {:head "abc1234" :previous-head "aaa111" :cwd "/tmp/repo"})
              texts   (map :content (:messages @state))]
          (is (true? (:skip? result)))
          (is (some #(re-find #"PSL skipped" (str %)) texts))
          (is (= ["git log -1 --pretty=format:%H%n%s"] @calls)))))))

(deftest psl-job-runs-agent-in-forked-sync-mode-test
  (testing "psl job uses agent tool-plan chain with fork_session=true"
    (let [sent     (atom [])
          captured (atom nil)
          mutate-fn (fn [op params]
                      (case op
                        psi.extension.tool/chain
                        (do
                          (reset! captured params)
                          {:psi.extension.tool-plan/succeeded? true
                           :psi.extension.tool-plan/results [{:id "psl-agent"
                                                              :result {:content "ok" :is-error false}}]})
                        psi.extension/notify
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message params})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})
            step   (first (:steps @captured))
            args   (:args step)
            task   (get args "task")]
        (is (= :done (:status result)))
        (is (true? (:accepted? result)))
        (is (= :agent (:delivery result)))
        (is (= "agent" (:tool step)))
        (is (= "create" (get args "action")))
        (is (= "sync" (get args "mode")))
        (is (= true (get args "fork_session")))
        (is (str/includes? task "Update munera/plan.md"))
        (is (str/includes? task "Update mementum/state.md"))
        (is (str/includes? task "do not create or edit mementum/memories/* or mementum/knowledge/*"))
        (is (some #(re-find #"PSL agent run completed" (str %)) @sent)))))

  (testing "psl job emits failure message when agent plan fails"
    (let [sent      (atom [])
          mutate-fn (fn [op params]
                      (case op
                        psi.extension.tool/chain
                        {:psi.extension.tool-plan/succeeded? false
                         :psi.extension.tool-plan/results [{:id "psl-agent"
                                                            :result {:content "boom" :is-error true}}]}
                        psi.extension/notify
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message params})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})]
        (is (= :done (:status result)))
        (is (false? (:accepted? result)))
        (is (= :agent (:delivery result)))
        (is (some #(re-find #"PSL agent run failed" (str %)) @sent))))))
