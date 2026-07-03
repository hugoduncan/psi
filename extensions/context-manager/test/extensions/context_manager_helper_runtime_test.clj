(ns extensions.context-manager-helper-runtime-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

;; --- default-run-helper: run-ok gating, prompt-selection, no worktree-path --

(defn- fake-run-api
  "A minimal `api` map for exercising default-run-helper: records the
   create-child-session params, records the run-agent-loop-in-session params
   (when a `run-calls` atom is supplied), records the close-session id (when a
   `closed` atom is supplied), and returns the supplied run-result from
   run-agent-loop-in-session."
  [{:keys [run-result create-calls run-calls closed child-id]
    :or   {child-id "child-1"}}]
  {:mutate-session
   (fn [_sid op params]
     (case op
       psi.extension/create-child-session
       (do (when create-calls (reset! create-calls params))
           {:psi.agent-session/session-id child-id})
       psi.extension/run-agent-loop-in-session
       (do (when run-calls (reset! run-calls params))
           run-result)))
   :mutate (fn [op params]
             (when (and closed (= op 'psi.extension/close-session))
               (reset! closed (:session-id params)))
             nil)})

(defn- await-untracked
  "Block (up to ~2s) until `id` is no longer tracked in the
   entity-resolution helper-session atom. The settled run future closes +
   untracks on its own thread, so tests must await it."
  [id]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (while (and (contains? @context-manager/entity-resolution-helper-session-ids id)
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 5))))

(deftest default-run-helper-gates-on-run-ok-test
  (testing "a failed helper run (ok? false) surfaces no text, not the error string"
    (let [api (fake-run-api
               {:run-result {:psi.agent-session/agent-run-ok? false
                             :psi.agent-session/agent-run-text "Error: boom"}})
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (= "child-1" (:child-session-id result)))
      (is (nil? (:text result))
          "failed run must not surface agent-run-text for parsing")))

  (testing "a successful helper run (ok? true) surfaces the run text"
    (let [api (fake-run-api
               {:run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text "the resolver → x (e; c)"}})
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (= "the resolver → x (e; c)" (:text result))))))

(deftest default-run-helper-settled-run-closes-and-untracks-test
  (testing "on a normal settled run the child is closed and untracked
            (the common-path cleanup, not only the timeout branch)"
    (let [closed (atom nil)
          api (fake-run-api
               {:closed closed
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text "the resolver → x (e; c)"}})
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (= "child-1" (:child-session-id result)))
      ;; The future's finally closes + untracks on its own thread; await it.
      (await-untracked "child-1")
      (is (= "child-1" @closed)
          "settled run closes the child session")
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids
                          "child-1"))
          "settled run untracks the child from the recursion-avoidance atom"))))

(deftest default-run-helper-forwards-selected-model-test
  (testing "the selected :model is threaded into run-agent-loop-in-session's
            params (the model-present cond-> arm) at the real-fn level"
    ;; The turn-9 selected-model-flows test captures :model only at the
    ;; stubbed :run-helper boundary; this drives the real default-run-helper's
    ;; `(cond-> {:prompt ..} model (assoc :model model))` params construction
    ;; so a dropped/mis-keyed/moved :model arm is caught in production wiring.
    (let [run-calls (atom nil)
          model     {:provider :ollama :id "qwen2.5-coder"}
          api (fake-run-api
               {:run-calls run-calls
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text ""}})
          _result (#'context-manager/default-run-helper
                   api {:parent-session-id "s1"
                        :system-prompt "sys"
                        :user-prompt "usr"
                        :model model})]
      (await-untracked "child-1")
      (is (= model (:model @run-calls))
          "selected model forwarded into run params (model-present cond-> arm)")
      (is (= "usr" (:prompt @run-calls))
          "user prompt is passed alongside the model")))

  (testing "no :model supplied → no :model key in the run params (nil arm)"
    (let [run-calls (atom nil)
          api (fake-run-api
               {:run-calls run-calls
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text ""}})]
      (#'context-manager/default-run-helper
       api {:parent-session-id "s1"
            :system-prompt "sys"
            :user-prompt "usr"})
      (await-untracked "child-1")
      (is (not (contains? @run-calls :model))
          "nil model arm omits :model from the run params"))))

(deftest default-run-helper-suppresses-default-prompt-and-omits-worktree-test
  (testing "create-child-session gets prompt-component-selection and no :worktree-path"
    (let [create-calls (atom nil)
          api (fake-run-api
               {:create-calls create-calls
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text ""}})]
      (#'context-manager/default-run-helper
       api {:parent-session-id "s1"
            :system-prompt "sys"
            :user-prompt "usr"})
      (let [params @create-calls]
        (is (= {:agents-md? false
                :extension-prompt-contributions []
                :tool-names ["bash"]
                :skill-names []
                :components #{}}
               (:prompt-component-selection params))
            "helper suppresses default full system prompt, keeping only bash")
        ;; The actual tool grant (acceptance criterion "created with access to
        ;; the existing `bash` tool only"): `:tool-ids` is the grant mechanism
        ;; (resolve-tool-defs tool-source (:tool-ids sd)); `:tool-names` in the
        ;; prompt-component-selection above only controls prompt *fragments*.
        (is (= ["bash"] (:tool-ids params))
            "helper is granted the bash tool only via :tool-ids")
        (is (= :off (:thinking-level params))
            "helper runs with thinking disabled")
        (is (not (contains? params :worktree-path))
            "no silently-ignored :worktree-path passed; cwd comes from parent inheritance")))))

(deftest default-run-helper-timeout-branch-test
  (testing "wall-clock timeout: real deref/::timeout branch returns nil text,
            child tracked during the run, closed+untracked after orphan settles"
    (let [release   (atom false)
          run-began (promise)
          closed    (atom nil)
          ;; run-agent-loop-in-session blocks (simulating a live, NOT reliably
          ;; interruptible, model/HTTP call) via a busy flag `future-cancel`
          ;; cannot unwind — until `release` is set — so the orphan outlives
          ;; the injected budget and the mid-run assertions are deterministic.
          api {:mutate-session
               (fn [_sid op _params]
                 (case op
                   psi.extension/create-child-session
                   {:psi.agent-session/session-id "child-1"}
                   psi.extension/run-agent-loop-in-session
                   (do (deliver run-began true)
                       ;; Uninterruptible spin modelling the real, blocking,
                       ;; not-reliably-interruptible model/HTTP call: clears
                       ;; interrupt status so it genuinely cannot be unwound
                       ;; until `release` is set.
                       (while (not @release)
                         (Thread/interrupted)
                         (Thread/onSpinWait))
                       {:psi.agent-session/agent-run-ok? true
                        :psi.agent-session/agent-run-text "late → x (e; c)"})))
               :mutate (fn [_op params] (reset! closed (:session-id params)) nil)}
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"
                       :wall-clock-ms 20})]
      (is (= "child-1" (:child-session-id result)))
      (is (nil? (:text result))
          "timeout branch surfaces no text (→ :no-op)")
      ;; During the orphan run, before it settles, the child is still tracked
      ;; (recursion-safe) and NOT yet closed.
      @run-began
      (is (contains? @context-manager/entity-resolution-helper-session-ids "child-1")
          "child stays tracked until the orphan future settles")
      (is (nil? @closed) "child not closed while orphan still running")
      ;; Let the orphan settle; the detached watcher then closes + untracks.
      (reset! release true)
      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (while (and (contains? @context-manager/entity-resolution-helper-session-ids "child-1")
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids "child-1"))
          "child untracked after orphan settles")
      (is (= "child-1" @closed)
          "child closed after orphan settles, not on the augmenter thread"))))
