(ns extensions.workflow-loader-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.workflow-loader :as wl]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-file-parser :as parser]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-model :as workflow-model]))

;;; End-to-end: raw file text → parsed → compiled → valid canonical definition

(def planner-raw
  (str "---\nname: planner\ndescription: Plans tasks\n---\n"
       "{:tools [\"read\" \"bash\"]}\n\n"
       "You are a planner."))

(def builder-raw
  (str "---\nname: builder\ndescription: Builds code\n---\n"
       "{:tools [\"read\" \"bash\" \"edit\" \"write\"]\n"
       " :skills [\"clojure-coding-standards\"]}\n\n"
       "You are a builder agent."))

(def reviewer-raw
  (str "---\nname: reviewer\ndescription: Reviews code\n---\n"
       "You are a reviewer."))

(def chain-raw
  (str "---\nname: plan-build-review\ndescription: Plan, build, and review\n---\n"
       "{:steps [{:workflow \"planner\" :prompt \"$INPUT\"}\n"
       "         {:workflow \"builder\" :prompt \"Execute: $INPUT\\nOriginal: $ORIGINAL\"}\n"
       "         {:workflow \"reviewer\" :prompt \"Review: $INPUT\\nOriginal: $ORIGINAL\"}]}\n\n"
       "Coordinate a plan-build-review cycle."))

(defn reset-extension-state [f]
  (reset! @#'wl/inflight-runs {})
  (reset! @#'wl/state nil)
  (f)
  (reset! @#'wl/inflight-runs {}))

(use-fixtures :each reset-extension-state)

(defn- make-loader-api
  [mutate-results]
  (let [tools (atom {})
        commands (atom {})
        notifications (atom [])
        mutate-calls (atom [])]
    {:api {:query (fn [_] {:psi.agent-session/worktree-path "/tmp/test-worktree"
                           :psi.agent-session/session-id "test-session-1"})
           :mutate (fn [sym params]
                     (swap! mutate-calls conj {:sym sym :params params})
                     (let [result (get mutate-results sym)]
                       (if (fn? result)
                         (result params)
                         (or result {}))))
           :log (fn [_] nil)
           :notify (fn [msg level] (swap! notifications conj {:msg msg :level level}))
           :register-tool (fn [tool-def] (swap! tools assoc (:name tool-def) tool-def))
           :register-command (fn [name cmd-def] (swap! commands assoc name cmd-def))
           :register-prompt-contribution (fn [_] nil)
           :on (fn [_ _] nil)}
     :tools tools
     :commands commands
     :mutate-calls mutate-calls
     :notifications notifications}))

(deftest end-to-end-single-step-test
  (testing "raw planner file → parse → compile → valid canonical definition"
    (let [parsed (parser/parse-workflow-file planner-raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (= ["step-1"] (:step-order definition)))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Tools carry through
      (is (= #{"read" "bash"} (get-in definition [:steps "step-1" :capability-policy :tools])))
      ;; System prompt in metadata
      (is (= "You are a planner." (get-in definition [:workflow-file-meta :system-prompt]))))))

(deftest end-to-end-multi-step-test
  (testing "raw chain file → parse → compile → valid canonical multi-step definition"
    (let [parsed (parser/parse-workflow-file chain-raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "plan-build-review" (:definition-id definition)))
      (is (= 3 (count (:step-order definition))))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Framing prompt in metadata
      (is (= "Coordinate a plan-build-review cycle."
             (get-in definition [:workflow-file-meta :framing-prompt]))))))

(deftest end-to-end-batch-with-validation-test
  (testing "batch parse-compile-validate across all workflow types"
    (let [all-raw [planner-raw builder-raw reviewer-raw chain-raw]
          parsed (mapv parser/parse-workflow-file all-raw)
          {:keys [definitions errors]} (compiler/compile-workflow-files parsed)]
      (is (= 4 (count definitions)))
      (is (empty? errors))
      (is (every? workflow-model/valid-workflow-definition? definitions))
      ;; Step references all resolve
      (is (true? (:valid? (compiler/validate-step-references definitions))))
      ;; No name collisions
      (is (true? (:valid? (compiler/validate-no-name-collisions definitions)))))))

(deftest legacy-agent-profile-compatibility-test
  (testing "existing .psi/agents/*.md files parse and compile cleanly"
    ;; Current agent profiles have YAML frontmatter with tools: and lambda: keys
    (let [raw (str "---\n"
                   "name: planner\n"
                   "description: Analyzes tasks, creates implementation plans\n"
                   "lambda: λtask. analyze(task)\n"
                   "tools: read,bash\n"
                   "---\n\n"
                   "You are a planning agent.")
          parsed (parser/parse-workflow-file raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Body becomes system prompt (no EDN config detected since body doesn't start with '{')
      (is (= "You are a planning agent."
             (get-in definition [:workflow-file-meta :system-prompt])))
      ;; Note: tools: from YAML frontmatter is not auto-migrated to EDN config —
      ;; migration step will handle that conversion
      )))

(deftest reload-definitions-retires-removed-definition-test
  (testing "reload retires definitions removed from disk"
    (let [{:keys [api commands mutate-calls]} (make-loader-api
                                               {'psi.workflow/register-definition (fn [_] {:psi.workflow/registered? true})
                                                'psi.workflow/remove-definition (fn [_] {:psi.workflow/removed? true})})
          load-call* (atom 0)]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      (case (swap! load-call* inc)
                        1 {:definitions {"planner" {:definition-id "planner"
                                                    :name "planner"
                                                    :summary "Plans"
                                                    :step-order ["step-1"]
                                                    :steps {"step-1" {:label "planner"}}}}
                           :errors []
                           :warnings []}
                        2 {:definitions {}
                           :errors []
                           :warnings []}))]
        (wl/init api)
        ((:handler (get @commands "delegate-reload")) nil)
        (is (some #(= 'psi.workflow/remove-definition (:sym %)) @mutate-calls))))))

(deftest reload-definitions-retires-renamed-definition-test
  (testing "reload retires old definition id when a workflow is renamed on disk"
    (let [{:keys [api commands mutate-calls]} (make-loader-api
                                               {'psi.workflow/register-definition (fn [_] {:psi.workflow/registered? true})
                                                'psi.workflow/remove-definition (fn [_] {:psi.workflow/removed? true})})
          load-call* (atom 0)]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      (case (swap! load-call* inc)
                        1 {:definitions {"planner" {:definition-id "planner"
                                                    :name "planner"
                                                    :summary "Plans"
                                                    :step-order ["step-1"]
                                                    :steps {"step-1" {:label "planner"}}}}
                           :errors []
                           :warnings []}
                        2 {:definitions {"planner-v2" {:definition-id "planner-v2"
                                                       :name "planner-v2"
                                                       :summary "Plans v2"
                                                       :step-order ["step-1"]
                                                       :steps {"step-1" {:label "planner-v2"}}}}
                           :errors []
                           :warnings []}))]
        (wl/init api)
        ((:handler (get @commands "delegate-reload")) nil)
        (is (some #(and (= 'psi.workflow/remove-definition (:sym %))
                        (= "planner" (get-in % [:params :definition-id])))
                  @mutate-calls))))))

(deftest init-registers-notifications-via-map-opts-test
  (testing "init sends startup load notices through extension ui notifications when available"
    (let [notifications (atom [])
          register-prompt-calls (atom [])
          tools (atom {})
          commands (atom {})
          handlers (atom [])
          api {:query (fn [_]
                        {:psi.agent-session/worktree-path "/tmp/test-worktree"
                         :psi.agent-session/session-id "test-session-1"})
               :mutate (fn [_ _] {})
               :query-session (fn [& _] nil)
               :mutate-session (fn [& _] nil)
               :log (fn [_] nil)
               :notify (fn [content & [opts]]
                         (swap! notifications conj {:surface :transcript
                                                    :content content
                                                    :opts opts}))
               :ui {:notify (fn [message level]
                              (swap! notifications conj {:surface :ui
                                                         :message message
                                                         :level level}))}
               :register-tool (fn [tool-def] (swap! tools assoc (:name tool-def) tool-def))
               :register-command (fn [name cmd-def] (swap! commands assoc name cmd-def))
               :register-prompt-contribution (fn [& args] (swap! register-prompt-calls conj args))
               :on (fn [event-name handler-fn] (swap! handlers conj [event-name handler-fn]))}]
      (with-redefs [loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {}
                       :errors [{:error "boom"}]
                       :warnings []})]
        (wl/init api)
        (is (= [{:surface :ui
                 :message "Workflow loader: 1 error(s) loading definitions"
                 :level :warn}
                {:surface :ui
                 :message "workflow-loader: 0 workflows loaded"
                 :level :info}]
               @notifications))
        (is (= 1 (count @register-prompt-calls)))
        (is (contains? @tools "delegate"))
        (is (contains? @commands "delegate"))
        (is (contains? @commands "delegate-reload"))
        (is (= ["session_switch"] (mapv first @handlers)))))))

(deftest reload-preserves-extension-state-atom-test
  (testing "namespace reload preserves workflow-loader state so registered command handlers keep working"
    (let [{:keys [api commands mutate-calls]} (make-loader-api
                                               {'psi.workflow/register-definition (fn [_] {:psi.workflow/registered? true})
                                                'psi.workflow/remove-definition (fn [_] {:psi.workflow/removed? true})})
          load-call* (atom 0)]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      (case (swap! load-call* inc)
                        1 {:definitions {"complexity-reduction-pr" {:definition-id "complexity-reduction-pr"
                                                                    :name "complexity-reduction-pr"
                                                                    :summary "Reduce complexity"
                                                                    :step-order ["step-1"]
                                                                    :steps {"step-1" {:label "complexity-reduction-pr"
                                                                                      :capability-policy {:tools #{"read" "bash" "edit" "write" "work-on"}}}}}}
                           :errors []
                           :warnings []}
                        2 {:definitions {"complexity-reduction-pr" {:definition-id "complexity-reduction-pr"
                                                                    :name "complexity-reduction-pr"
                                                                    :summary "Reduce complexity"
                                                                    :step-order ["step-1"]
                                                                    :steps {"step-1" {:label "complexity-reduction-pr"
                                                                                      :capability-policy {:tools #{"read" "bash" "edit" "write" "work-on"}}}}}}
                           :errors []
                           :warnings []}))]
        (wl/init api)
        (is (= ["complexity-reduction-pr"]
               (sort (keys (:loaded-definitions @@#'wl/state)))))
        (require 'extensions.workflow-loader :reload)
        ((:handler (get @commands "delegate-reload")) nil)
        (is (= ["complexity-reduction-pr"]
               (sort (keys (:loaded-definitions @@#'wl/state)))))
        (is (= 2 (count (filter #(= 'psi.workflow/register-definition (:sym %)) @mutate-calls))))))))
