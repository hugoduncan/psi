(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(defn- setup-api
  "Helper to create a nullable API and initialize the extension."
  []
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/context_manager.clj"})]
    (context-manager/init api)
    {:api api :state state}))

(deftest init-registers-turn-finished-handler-test
  (testing "init registers a session_turn_finished handler"
    (let [{:keys [state]} (setup-api)]
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (is (contains? (:turn-augmenters @state) "project-context")))))

(deftest turn-finished-handler-fires-and-logs-test
  (testing "handler fires on synthetic session_turn_finished event and logs session-id and turn-id"
    (let [{:keys [state]} (setup-api)
          handler (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "nominal case"
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil as per design requirement")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=s1 turn-id=t1" line)
              "log output must match exact project standard prefix and format")))
      (testing "payload is an empty map"
        (is (nil? (handler {}))
            "handler returns nil")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line))))
      (testing "payload is nil"
        (is (nil? (handler nil))
            "handler must return nil and not throw when payload is nil")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line))))
      (testing "payload is not a map"
        (is (nil? (handler "not-a-map"))
            "handler must return nil and not throw when payload is not a map")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line)))))))

(deftest init-reload-safety-test
  (testing "calling init twice does not register duplicate handlers"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (context-manager/init api)
      (context-manager/init api)
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"])))
          "init is idempotent: calling twice still registers only one handler"))))

(deftest init-registers-no-commands-tools-operations-or-prompts-test
  (testing "init registers no commands, tools, operations, or prompt contributions"
    (let [{:keys [api state]} (setup-api)]
      (is (empty? (:commands @state)) "no commands registered")
      (is (empty? (:tools @state)) "no tools registered")
      ;; Operations are not separately trackable in the nullable API — they
      ;; are dispatched through the same handler mechanism as other mutations,
      ;; so there is no :operations key on the nullable state map.
      ;; Prompt contributions live in :root-state, not directly on the state map;
      ;; use :list-prompt-contributions on the API to query them.
      (is (empty? ((:list-prompt-contributions api))) "no prompt contributions registered")
      (is (= ["entity-resolution" "project-context"] (-> @state :turn-augmenters keys sort vec))
          "both turn augmenters are registered"))))

(deftest handler-handles-missing-payload-keys-test
  (testing "handler logs gracefully when :session-id or :turn-id are missing"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "missing only :turn-id"
        (is (nil? (handler {:session-id "s2"}))
            "handler returns nil")
        (is (= "context-manager: session_turn_finished session-id=s2 turn-id=nil"
               (last (:log-lines @state)))))
      (testing "missing only :session-id"
        (is (nil? (handler {:turn-id "t2"}))
            "handler returns nil")
        (is (= "context-manager: session_turn_finished session-id=nil turn-id=t2"
               (last (:log-lines @state))))))))

(deftest init-robustness-test
  (testing "init handles non-standard api gracefully"
    (testing "missing :on key"
      (is (nil? (context-manager/init {:log (fn [_] nil)}))
          "should return nil and not throw NPE when :on is missing")
      (reset! context-manager/initialized? nil))
    (testing "api is nil"
      (is (nil? (context-manager/init nil))
          "should return nil and not throw NPE when api is nil"))
    (testing "api is not a map"
      (is (nil? (context-manager/init "not-a-map"))
          "should return nil and not throw NPE when api is not a map"))))

(deftest init-recovery-after-missing-on-key-test
  (testing "init recovers after a failed call due to missing :on key"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (nil? (context-manager/init {:log (fn [_] nil)})) "first call fails")
      (is (true? (context-manager/init api)) "subsequent call with valid API succeeds")
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (reset! context-manager/initialized? nil))))

(deftest handler-works-without-log-key-test
  (testing "handler works correctly when :log is missing from API"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (reset! context-manager/initialized? nil)
      (context-manager/init (dissoc api :log))
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler should return nil and not throw NPE when log-fn is missing")
        (is (empty? (:log-lines @state))
            "handler should not log anything when :log is missing from API")))))

(deftest init-return-value-test
  (testing "init returns true on successful first-time initialization"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (true? (context-manager/init api))
          "init should return true on successful first-time initialization"))
    (testing "init returns nil on second call after successful initialization"
      (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (context-manager/init api)
        (is (nil? (context-manager/init api))
            "init should return nil on subsequent calls after successful initialization")))))

(deftest init-registration-contract-test
  (testing "init registration contract"
    ;; Use a spy on :on to verify call arguments explicitly,
    ;; rather than inspecting the resulting state map.
    (let [call-args (atom nil)
          spy-on    (fn [event-name handler-fn]
                      (reset! call-args {:event-name event-name
                                         :handler-fn handler-fn})
                      handler-fn)
          base-api  (nullable/create-nullable-extension-api
                     {:path "/test/context_manager.clj"})]
      (reset! context-manager/initialized? nil)
      (context-manager/init (assoc (:api base-api) :on spy-on))
      (testing "registered with correct event name"
        (is (= "session_turn_finished" (:event-name @call-args))
            "(:on api) must be called with event name session_turn_finished"))
      (testing "handler is a function"
        (is (fn? (:handler-fn @call-args))
            "registered handler must be a function to be compatible with dispatch pipeline")))))

(deftest handler-purity-test
  (testing "handler does not mutate external state"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))
          before-state    @state]
      (handler {:session-id "s1" :turn-id "t1"})
      (is (= (dissoc before-state :log-lines) (dissoc @state :log-lines))
          "handler must not mutate the API state map; it should only use the provided log-fn"))))

(deftest handler-log-fn-throws-test
  (testing "handler does not throw when log-fn itself throws an exception"
    (let [throwing-log (fn [_] (throw (ex-info "deliberate" {})))
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          api-with-throwing-log (assoc api :log throwing-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-throwing-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil and not throw when log-fn throws")
        (is (empty? (:log-lines @state))
            "no log output produced when log-fn always throws")))))

(deftest handler-log-fn-throws-logs-error-test
  (testing "handler logs error message when log-fn throws during normal logging"
    (let [call-count (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          real-log (:log api)
          flaky-log  (fn [text]
                       (swap! call-count inc)
                       (if (= @call-count 1)
                         (throw (ex-info "first call fails" {}))
                         (real-log text)))
          api-with-flaky-log (assoc api :log flaky-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-flaky-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil and not throw")
        (is (= "context-manager: handler error: first call fails"
               (last (:log-lines @state)))
            "error message with exact prefix and exception message is logged")))))

(deftest handler-log-fn-returns-non-nil-test
  (testing "handler returns nil even when log-fn returns a non-nil value"
    (let [returning-log (fn [text] (str "not-nil-" text))
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          api-with-returning-log (assoc api :log returning-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-returning-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil regardless of what log-fn returns")))))

(deftest project-context-augmentation-test
  (testing "returns working-directory append-context-block"
    (is (= {:turn-augmentation/status :success
            :turn-augmentation/operations
            [{:op :append-context-block
              :id "project-context"
              :title "Project context"
              :content "Working directory: /repo"}]
            :turn-augmentation/child-session-ids []}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "s1"
             :turn-augmentation/effective-cwd "/repo"}))))

  (testing "returns no-op when effective cwd is absent"
    (is (= {:turn-augmentation/status :no-op
            :turn-augmentation/operations []
            :turn-augmentation/child-session-ids []
            :turn-augmentation/diagnostic "no effective cwd"}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "s1"
             :turn-augmentation/effective-cwd ""}))))

  (testing "returns no-op for tracked helper sessions to avoid recursion"
    (swap! context-manager/helper-session-ids conj "helper-1")
    (is (= {:turn-augmentation/status :no-op
            :turn-augmentation/operations []
            :turn-augmentation/child-session-ids []}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "helper-1"
             :turn-augmentation/effective-cwd "/repo"})))))

(deftest init-registers-turn-augmenter-test
  (testing "init registers project-context with the dedicated augmentation API"
    (let [{:keys [state]} (setup-api)
          registration (get-in @state [:turn-augmenters "project-context"])]
      (is (= "project-context" (:augmenter-id registration)))
      (is (fn? (:handler registration)))
      (is (= :success
             (:turn-augmentation/status
              ((:handler registration)
               {:turn-augmentation/session-id "s"
                :turn-augmentation/effective-cwd "/repo"})))))))

;; ---------------------------------------------------------------------------
;; Entity-resolution augmenter (task 238)
;; ---------------------------------------------------------------------------

(def ^:private base-tp
  {:turn-augmentation/session-id "s1"
   :turn-augmentation/effective-cwd "/repo"
   :turn-augmentation/user-text "please look at the resolver"
   :turn-augmentation/history []})

(defn- stub
  "Build a collaborators map with a fixed model and a run-helper returning
   the supplied raw text under a fixed child-session-id. Records calls."
  [{:keys [model text child-id throw? calls]
    :or   {model {:provider :ollama :id "qwen"} child-id "helper-1"}}]
  {:select-model (fn [_parent]
                   (swap! (or calls (atom nil)) (fnil update {}) :select (fnil inc 0))
                   model)
   :run-helper   (fn [_opts]
                   (when calls (swap! calls (fnil update {}) :run (fnil inc 0)))
                   (if throw?
                     (throw (ex-info "boom" {}))
                     {:child-session-id child-id :text text}))})

;; --- pure: prompt / parse / render ---------------------------------------

(deftest parse-mapping-lines-test
  (testing "accepts well-formed lines, discards everything else"
    (let [raw (str "Here are the mappings I found:\n"
                   "the resolver → components/pathom/resolver.clj (exact path; high)\n"
                   "some malformed line without arrow\n"
                   "that task → munera/open/238-x/ (git ls-files; medium)\n"
                   "Should I proceed? (question)\n")
          parsed (context-manager/parse-mapping-lines raw)]
      (is (= 2 (count parsed)))
      (is (= {:surface "the resolver"
              :canonical "components/pathom/resolver.clj"
              :evidence "exact path"
              :confidence "high"}
             (first parsed)))
      (is (= "that task" (:surface (second parsed))))))

  (testing "supports ascii arrow"
    (is (= 1 (count (context-manager/parse-mapping-lines
                     "foo -> bar (ev; conf)")))))

  (testing "canonical containing parentheses does not leak into evidence"
    (is (= [{:surface "the fn"
             :canonical "foo/bar (arity 2)"
             :evidence "exact path"
             :confidence "high"}]
           (context-manager/parse-mapping-lines
            "the fn → foo/bar (arity 2) (exact path; high)"))))

  (testing "evidence containing a semicolon is preserved (splits at last ;)"
    (is (= [{:surface "the term"
             :canonical "foo"
             :evidence "git grep; 3 hits"
             :confidence "medium"}]
           (context-manager/parse-mapping-lines
            "the term → foo (git grep; 3 hits; medium)"))))

  (testing "nested parens in evidence split at last ;, not leaking across boundary"
    (is (= [{:surface "the fn"
             :canonical "foo (bar)"
             :evidence "baz (qux)"
             :confidence "high"}]
           (context-manager/parse-mapping-lines
            "the fn → foo (bar) (baz (qux); high)"))))

  (testing "empty canonical is rejected, not emitted as a confident mapping"
    (is (= [] (context-manager/parse-mapping-lines "a →  (e; c)"))))

  (testing "incidental code-shaped line with unbalanced parens is rejected"
    (is (= [] (context-manager/parse-mapping-lines
               "(fn [x] -> (foo x)) (call; note)"))))

  (testing "zero well-formed lines yields empty vector"
    (is (= [] (context-manager/parse-mapping-lines "no lines here at all")))
    (is (= [] (context-manager/parse-mapping-lines nil)))))

(deftest render-mapping-content-test
  (testing "renders three fields, confidence dropped"
    (is (= "the resolver → components/pathom/resolver.clj (exact path)"
           (context-manager/render-mapping-content
            [{:surface "the resolver"
              :canonical "components/pathom/resolver.clj"
              :evidence "exact path"
              :confidence "high"}])))))

(deftest build-entity-resolution-prompt-test
  (testing "prompt embeds method, safety, contract, user text, and history"
    (let [{:keys [system-prompt user-prompt]}
          (context-manager/build-entity-resolution-prompt
           (assoc base-tp
                  :turn-augmentation/history
                  ;; real 237 projection shape: {:message-count :tail [{:role :snippet ...}]}
                  {:message-count 3
                   :tail [{:index 0 :role "user" :snippet "look at the pathom resolver"}
                          {:index 1 :role "assistant" :snippet "which one?"}
                          {:index 2 :role "user" :snippet "/help"}]}))]
      (is (re-find #"Identify referring expressions" system-prompt))
      (is (re-find #"evidence gathering only" system-prompt))
      (is (re-find #"surface . canonical \(evidence; confidence\)" system-prompt))
      (testing "design-required capability-gap disclosure (Resolved decision 6)"
        (is (re-find #"cannot query the Psi runtime/session graph" system-prompt)
            "helper is told it cannot query the runtime/session graph")
        (is (re-find #"sessions are not a resolvable entity type" system-prompt)
            "sessions are explicitly not a resolvable entity type"))
      (testing "round-cap prompt instruction (the only representation of the bound)"
        (is (re-find #"at most 8 rounds" system-prompt)
            "system prompt states the max-helper-rounds round cap"))
      (is (re-find #"please look at the resolver" user-prompt))
      (is (re-find #"look at the pathom resolver" user-prompt)
          "prior-turn user :snippet line is included for anaphora")
      (is (re-find #"which one\?" user-prompt))
      (is (not (re-find #"/help" user-prompt))
          "slash-command history lines are dropped")))
  (testing "map-shaped history :tail snippets appear in the user prompt"
    (let [{:keys [user-prompt]}
          (context-manager/build-entity-resolution-prompt
           (assoc base-tp
                  :turn-augmentation/history
                  {:message-count 1
                   :tail [{:index 0 :role "user" :snippet "the former one"}]}))]
      (is (re-find #"User: the former one" user-prompt)
          "map-shaped :tail snippet is rendered as a Role: text line")))
  (testing "nil / empty-tail / flat-vector history yields no excerpt"
    (doseq [history [nil
                     {:message-count 0 :tail []}
                     ;; a flat vector is NOT the real projection shape → no :tail → no excerpt
                     [{:role "user" :text "look at the resolver"}]]]
      (let [{:keys [user-prompt]}
            (context-manager/build-entity-resolution-prompt
             (assoc base-tp :turn-augmentation/history history))]
        (is (not (re-find #"Conversation history excerpt" user-prompt))
            (str "no excerpt for history=" (pr-str history)))))))

(deftest build-entity-resolution-prompt-tail-truncation-test
  (testing "an over-long history excerpt is length-bounded and keeps the tail"
    ;; Build a :tail long enough that the rendered excerpt exceeds
    ;; max-history-chars (4000). An OLD-marker in the earliest line must be
    ;; truncated away; a NEW-marker in the most-recent line must survive.
    (let [filler (apply str (repeat 200 "padding words here "))
          tail   (vec
                  (concat
                   [{:index 0 :role "user" :snippet (str "OLDMARKER " filler)}]
                   (for [i (range 1 30)]
                     {:index i :role "user" :snippet filler})
                   [{:index 30 :role "user" :snippet (str filler " NEWMARKER")}]))
          {:keys [user-prompt]}
          (context-manager/build-entity-resolution-prompt
           (assoc base-tp
                  :turn-augmentation/history {:message-count (count tail)
                                              :tail tail}))
          excerpt (second (re-find #"(?s)Conversation history excerpt:\n\n(.*?)\n\nCurrent user request:"
                                   user-prompt))]
      (is (some? excerpt) "an excerpt is present")
      (is (<= (count excerpt) 4000)
          "excerpt is bounded by max-history-chars")
      (is (re-find #"NEWMARKER" excerpt)
          "most-recent (tail) content is retained")
      (is (not (re-find #"OLDMARKER" excerpt))
          "earliest (head) content is truncated away, not the tail"))))

;; --- eligibility pre-filter no-ops ---------------------------------------

(deftest entity-resolution-helper-session-no-op-test
  (testing "tracked helper session yields no-op without creating a helper"
    (swap! context-manager/entity-resolution-helper-session-ids conj "s1")
    (let [calls (atom {})
          env (context-manager/entity-resolution-augmentation
               {} base-tp (stub {:text "x → y (e; c)" :calls calls}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (nil? (:select @calls)) "model selection not attempted")
      (is (nil? (:run @calls)) "helper session never created"))))

(deftest entity-resolution-blank-cwd-no-op-test
  (testing "blank effective-cwd yields no-op without helper"
    (let [calls (atom {})
          env (context-manager/entity-resolution-augmentation
               {} (assoc base-tp :turn-augmentation/effective-cwd "")
               (stub {:text "x → y (e; c)" :calls calls}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (nil? (:run @calls))))))

(deftest entity-resolution-slash-command-only-no-op-test
  (testing "slash-command-only prompt is pre-filtered before any helper run"
    (let [calls (atom {})
          env (context-manager/entity-resolution-augmentation
               {} (assoc base-tp :turn-augmentation/user-text "/status")
               (stub {:text "x → y (e; c)" :calls calls}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (nil? (:select @calls)) "no model selected for slash-command-only turn")
      (is (nil? (:run @calls)) "helper session never created"))))

;; --- orchestration outcomes ----------------------------------------------

(deftest entity-resolution-confident-mapping-success-test
  (testing "one confident line becomes a success append-context-block"
    (let [env (context-manager/entity-resolution-augmentation
               {} base-tp
               (stub {:text "the resolver → components/pathom/resolver.clj (exact path; high)"
                      :child-id "helper-7"}))]
      (is (= :success (:turn-augmentation/status env)))
      (is (= [{:op :append-context-block
               :id "entity-resolution"
               :title "Resolved entities"
               :content "the resolver → components/pathom/resolver.clj (exact path)"}]
             (:turn-augmentation/operations env)))
      (is (= ["helper-7"] (:turn-augmentation/child-session-ids env))
          "helper child-session id reported as provenance")
      (is (not (contains? (first (:turn-augmentation/operations env)) :source))
          "augmenter omits :source; core injects provenance"))))

(deftest entity-resolution-selected-model-flows-into-run-test
  (testing "the model select-model returns is the one passed to the helper run"
    ;; Single-attempt selection→run wiring (Required behaviour item 3): the
    ;; one top-ranked local candidate select-model returns must be the model
    ;; the helper run actually runs under. Capture run-helper's :model run-opt.
    (let [selected {:provider :ollama :id "qwen-selected"}
          captured (atom :unset)
          env (context-manager/entity-resolution-augmentation
               {} base-tp
               {:select-model (fn [_parent] selected)
                :run-helper   (fn [opts]
                                (reset! captured (:model opts))
                                {:child-session-id "helper-1"
                                 :text "the resolver → x (e; c)"})})]
      (is (= :success (:turn-augmentation/status env)))
      (is (= selected @captured)
          "model returned by select-model is threaded into the helper run-opts"))))

(deftest entity-resolution-no-local-model-no-op-test
  (testing "no local model yields no-op with no helper run"
    (let [calls (atom {})
          env (context-manager/entity-resolution-augmentation
               {} base-tp (stub {:model nil :calls calls}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (= "no local model" (:turn-augmentation/diagnostic env)))
      (is (nil? (:run @calls)) "no helper run attempted"))))

(deftest default-select-model-rejects-cloud-winner-test
  (testing "a cheap-tier cloud model that survives the required filter is rejected (local-only)"
    ;; The required constraints (:supports-text, :latency-tier :low,
    ;; :cost-tier #{:zero :low}) admit cheap cloud models; :locality :local
    ;; is only a strong preference, so a cloud candidate can rank first when
    ;; no local model is configured. default-select-model must still return
    ;; nil so the augmenter runs no cloud helper on the per-turn path.
    (let [cloud-candidate {:provider :openai
                           :id       "cheap-cloud"
                           :name     "Cheap Cloud"
                           :facts    {:supports-text true
                                      :latency-tier  :low
                                      :cost-tier     :low
                                      :locality      :cloud}}]
      ;; Inject the candidate pool via default-select-model's :catalog seam
      ;; (nullable-over-mock: pass a candidate pool as a parameter rather than
      ;; with-redefs-ing the model-registry infrastructure boundary).
      (is (nil? (#'context-manager/default-select-model
                 {:query-session (fn [_ _] {})} "s1"
                 {:candidates [cloud-candidate]}))
          "cloud-only pool must yield nil (→ :no-op, no cloud helper run)"))))

(deftest default-select-model-accepts-local-winner-test
  (testing "a qualifying local model is returned as the top-ranked candidate"
    (let [local-candidate {:provider :ollama
                           :id       "local-q"
                           :name     "Local Q"
                           :facts    {:supports-text true
                                      :latency-tier  :low
                                      :cost-tier     :zero
                                      :locality      :local}}]
      ;; Inject the candidate pool via the :catalog seam (nullable-over-mock).
      (is (= "local-q"
             (:id (#'context-manager/default-select-model
                   {:query-session (fn [_ _] {})} "s1"
                   {:candidates [local-candidate]})))
          "local winner is selected"))))

(deftest entity-resolution-empty-run-no-op-test
  (testing "helper run producing no parseable lines yields no-op"
    (let [env (context-manager/entity-resolution-augmentation
               {} base-tp
               (stub {:text "I could not find anything conclusive."
                      :child-id "helper-3"}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (= ["helper-3"] (:turn-augmentation/child-session-ids env))
          "helper id still reported even on empty run"))))

(deftest entity-resolution-nil-run-no-op-test
  (testing "failed helper run (nil result) yields no-op"
    (let [env (context-manager/entity-resolution-augmentation
               {} base-tp
               {:select-model (fn [_] {:provider :ollama :id "q"})
                :run-helper   (fn [_] nil)})]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (= [] (:turn-augmentation/child-session-ids env))))))

(deftest entity-resolution-throwing-helper-no-op-test
  (testing "a helper run that throws collapses to a well-formed :no-op"
    ;; Required behaviour item 5: a failed helper run yields a well-formed
    ;; :no-op. A collaborator (or future run-helper) that throws rather than
    ;; returning nil must not propagate onto 237's blocking pre-turn path.
    (let [env (context-manager/entity-resolution-augmentation
               {} base-tp (stub {:throw? true}))]
      (is (= :no-op (:turn-augmentation/status env)))
      (is (= [] (:turn-augmentation/child-session-ids env))
          "no child id reported when the run threw"))))

(deftest entity-resolution-ambiguous-dropped-test
  (testing "ambiguous surface dropped while a confident one is kept"
    ;; The helper self-gates: it emits a confident line for the unambiguous
    ;; surface ("the resolver") and only prose (no mapping line) for the
    ;; ambiguous one ("that thing"). Distinct from the empty-run path — a
    ;; :success block is produced, but only for the confident surface, so the
    ;; ambiguous surface never appears in the rendered content.
    (let [env (context-manager/entity-resolution-augmentation
               {} (assoc base-tp
                         :turn-augmentation/user-text
                         "look at the resolver and fix that thing")
               (stub {:text (str "the resolver → components/pathom/resolver.clj "
                                 "(exact path; high)\n"
                                 "\"that thing\" is ambiguous — could be several "
                                 "files, so I am not resolving it.")
                      :child-id "helper-9"}))
          content (-> env :turn-augmentation/operations first :content)]
      (is (= :success (:turn-augmentation/status env)))
      (is (re-find #"the resolver" content)
          "confident surface is rendered in the block")
      (is (not (re-find #"that thing" content))
          "ambiguous surface (no mapping line parsed) is absent from the block"))))

;; --- default-run-helper: run-ok gating, prompt-selection, no worktree-path --

(defn- fake-run-api
  "A minimal `api` map for exercising default-run-helper: records the
   create-child-session params, records the close-session id (when a
   `closed` atom is supplied), and returns the supplied run-result from
   run-agent-loop-in-session."
  [{:keys [run-result create-calls closed child-id]
    :or   {child-id "child-1"}}]
  {:mutate-session
   (fn [_sid op params]
     (case op
       psi.extension/create-child-session
       (do (when create-calls (reset! create-calls params))
           {:psi.agent-session/session-id child-id})
       psi.extension/run-agent-loop-in-session
       run-result))
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

(deftest entity-resolution-recursion-loop-end-to-end-test
  (testing "the real default-run-helper producer and the augmenter pre-filter
            consumer share the tracking atom: a child id tracked by a real run
            makes the augmenter no-op for that same id"
    ;; The two halves of the recursion guarantee live in different code paths
    ;; sharing `entity-resolution-helper-session-ids`: default-run-helper
    ;; `conj`s the real child id, and the augmenter pre-filter reads it. Other
    ;; tests seed the atom manually or stub :run-helper (never touching it);
    ;; this links producer → consumer in one flow. A blocking run keeps the
    ;; child tracked while the augmenter checks it.
    (let [release   (atom false)
          run-began (promise)
          api {:mutate-session
               (fn [_sid op _params]
                 (case op
                   psi.extension/create-child-session
                   {:psi.agent-session/session-id "child-1"}
                   psi.extension/run-agent-loop-in-session
                   (do (deliver run-began true)
                       (while (not @release)
                         (Thread/interrupted)
                         (Thread/onSpinWait))
                       {:psi.agent-session/agent-run-ok? true
                        :psi.agent-session/agent-run-text ""})))
               :mutate (fn [_op _params] nil)}]
      ;; Drive the real producer: it tracks "child-1" before the (blocking)
      ;; run and returns on the injected timeout while the orphan runs on.
      (#'context-manager/default-run-helper
       api {:parent-session-id "s1"
            :system-prompt "sys"
            :user-prompt "usr"
            :wall-clock-ms 20})
      @run-began
      (is (contains? @context-manager/entity-resolution-helper-session-ids
                     "child-1")
          "real run tracked the child id")
      ;; Now the real consumer: an augmenter turn *for that tracked child id*
      ;; must pre-filter to :no-op — the recursion guarantee, end to end.
      (let [env (context-manager/entity-resolution-augmentation
                 {} (assoc base-tp :turn-augmentation/session-id "child-1"))]
        (is (= :no-op (:turn-augmentation/status env))
            "augmenter no-ops for a session id the real run is tracking"))
      ;; Release the orphan and let it untrack, keeping the fixture clean.
      (reset! release true)
      (await-untracked "child-1"))))

(deftest init-registers-entity-resolution-augmenter-test
  (testing "init registers entity-resolution with a handler"
    (let [{:keys [state]} (setup-api)
          registration (get-in @state [:turn-augmenters "entity-resolution"])]
      (is (= "entity-resolution" (:augmenter-id registration)))
      (is (fn? (:handler registration))))))

