(ns extensions.context-manager-friction-default-wiring-test
  "Tests for the two seams the friction-analysis orchestration tests
   deliberately mask (task 239, task-test-review round 3):

   1. `friction-analysis*`'s **default-collaborator binding map** — the
      `or`-bindings that wire `friction/run-analysis` to the real
      disk-touching collaborators (`default-fetch-history`,
      `default-session-info`, real `open-tasks`/`recent-closed-tasks`,
      real `create-friction-task!`). Every `friction-analysis` test injects
      its own collaborators, so `run-analysis` is well-covered but the
      wiring that binds each real fn into the *correct* slot is not. This is
      the friction-path analog of
      `context_manager_entity_resolution_registration_test.clj`.

   2. The `session_turn_finished` future body's outer **catch-all** in
      `init` — the belt-and-braces `(future (try (friction-analysis ..)
      (catch Throwable e .. \"uncaught error:\" ..)))` guard — is otherwise
      unexercised: the wiring test drives only the no-worktree no-op path,
      never the success path nor the uncaught-error path the outer catch
      exists to swallow."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/friction-helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (reset! context-manager/friction-in-flight-session-ids #{})
                      (f)))

(defn- temp-worktree []
  (let [dir (java.io.File/createTempFile "friction-default-wiring-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getCanonicalPath dir)))

(defn- await-until
  "Poll (up to ~2s) until `pred` returns truthy; return its value or nil."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))

(def ^:private issue-output
  (str "ISSUE: slow-tests | Test suite is slow\n"
       "FRICTION: bb test takes minutes\n"
       "EVIDENCE: turn 3 waited 5 minutes for feedback\n"
       "SUGGESTION: add a faster focused test runner\n"))

(deftest default-collaborators-write-a-real-task-through-the-wiring-test
  (testing "friction-analysis with NO injected collaborators (nil) resolves
            its real default binding map — driving the real
            default-session-info → default-fetch-history → real
            open-tasks/recent-closed-tasks → real create-friction-task!
            against a temp worktree — and writes an actual
            munera/open/NNN-slug/design.md on disk. Only :select-model and
            :run-helper are injected (a real model pick + child helper
            session are the infrastructure boundary), so every filesystem/
            EQL slot exercises its real default binding, not a stub — the
            wiring, not just the orchestration, is proven."
    (let [root      (temp-worktree)
          messages  [{:role "user" :content [{:type :text :text "run the tests"}]}
                     {:role "assistant"
                      :content [{:type :text :text "did it via a slow bash workaround"}]}]
          ;; Dispatch the nullable :query-session by EQL query so the real
          ;; default-session-info and default-fetch-history each get a
          ;; realistic result shape.
          query-fn  (fn [{:keys [query]}]
                      (cond
                        (= query [:psi.agent-session/worktree-path
                                  :psi.agent-session/session-name])
                        {:psi.agent-session/worktree-path root
                         :psi.agent-session/session-name  "top-level"}

                        (= query [:psi.agent-session/message-history])
                        {:psi.agent-session/message-history messages}

                        :else {}))
          {:keys [api]} (nullable/create-nullable-extension-api
                         {:path "/test/context_manager.clj"
                          :query-fn query-fn})
          ;; Inject only the model/helper boundary; leave :fetch-history,
          ;; :session-info, :list-tasks, :create-task!, :task-cap to their
          ;; real default bindings.
          result    (context-manager/friction-analysis
                     api {:session-id "s1"}
                     {:select-model (fn [_sid] {:provider :ollama :id "qwen"})
                      :run-helper   (fn [_opts]
                                      {:child-session-id "helper-1"
                                       :text issue-output})})]
      (is (= :success (:status result))
          "the real-wiring run reaches the success branch")
      (is (= ["001-slow-tests"] (:created-task-ids result))
          "the real create-friction-task! allocated an id via real
           open-tasks/recent-closed-tasks over the temp worktree")
      (let [design (io/file root "munera" "open" "001-slow-tests" "design.md")]
        (is (.exists design)
            "the real create-friction-task! wrote design.md on disk")
        (let [content (slurp design)]
          (is (str/includes? content "# Test suite is slow")
              "the detected issue's title was rendered into the written file")
          (is (str/includes? content "Auto-generated")
              "the auto-generated marker reached the written file"))))))

(deftest turn-finished-future-logs-a-success-path-diagnostic-test
  (testing "the session_turn_finished future actually invokes
            friction-analysis and surfaces its diagnostic (round-3 success
            path): a friction-analysis whose run logs a task-created line
            reaches the log through the future body, not just the
            no-worktree no-op path the existing wiring test drives."
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      ;; Redefine friction-analysis to a fast stub that logs a success line
      ;; via the real api :log — driving the future body's happy path (the
      ;; try's non-throwing arm) without a real model/helper. This exercises
      ;; the future wiring itself (that it calls friction-analysis and the
      ;; call's effects land), which the internals-level analysis tests
      ;; can't observe through init's handler.
      (with-redefs [context-manager/friction-analysis
                    (fn [api* _payload]
                      ((:log api*) "context-manager: friction-analysis: task created 001-x")
                      {:status :success :created-task-ids ["001-x"]})]
        (context-manager/init api)
        (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
          (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
              "handler returns nil promptly")
          (is (await-until
               #(some (fn [l] (re-find #"friction-analysis: task created" l))
                      (:log-lines @state)))
              "the future invoked friction-analysis and its success-path log
               line reached the api log"))))))

(deftest turn-finished-future-outer-catch-all-swallows-a-thrown-analysis-test
  (testing "the future body's outer catch-all (belt-and-braces) fires when
            friction-analysis throws: the handler still returns nil promptly
            and the future logs the \"uncaught error:\" diagnostic rather
            than crashing its thread with an uncaught exception. This pins
            the last-line-of-defence arm the wiring never otherwise reaches
            (round-3 follow-up)."
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (with-redefs [context-manager/friction-analysis
                    (fn [_api _payload]
                      (throw (ex-info "boom from friction-analysis" {})))]
        (context-manager/init api)
        (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
          (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
              "handler returns nil promptly even though the future's
               friction-analysis throws")
          (is (await-until
               #(some (fn [l] (re-find #"friction-analysis: uncaught error: boom" l))
                      (:log-lines @state)))
              "the outer catch-all logged the uncaught-error diagnostic,
               proving the future's last line of defence fired"))))))
