(ns psi.agent-session.tools-test
  (:require
   [psi.agent-session.test-support :as test-support]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.workflow-runtime]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.project-nrepl-ops]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.tools :as tools]
   [psi.ai.model-registry :as model-registry]))

(defn- delete-tree! [path]
  (when path
    (doseq [f (reverse (file-seq (io/file path)))]
      (.delete ^java.io.File f))))

(defmacro with-temp-dir [[sym prefix] & body]
  `(let [~sym (str (java.nio.file.Files/createTempDirectory ~prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
     ~sym
     (try
       ~@body
       (finally
         (delete-tree! ~sym)))))
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest psi-tool-schema-test
  (testing "psi-tool schema is well-formed"
    (is (= "psi-tool" (:name tools/psi-tool)))
    (is (string? (:description tools/psi-tool)))
    (is (map? (:parameters tools/psi-tool)))))

(deftest psi-tool-in-all-schemas-test
  (testing "psi-tool appears in all-tool-schemas"
    (is (some #(= "psi-tool" (:name %)) tools/all-tool-schemas))))

(deftest psi-tool-not-in-all-tools-test
  (testing "psi-tool is excluded from all-tools (requires context)"
    (is (not (some #(= "psi-tool" (:name %)) tools/all-tools)))))

(deftest make-psi-tool-valid-query-test
  (testing "valid EQL query returns EDN result"
    (let [query-fn (fn [q]
                     (is (= [:foo/bar] q))
                     {:foo/bar 42})
          tool     (tools/make-psi-tool query-fn)
          result   ((:execute tool) {"query" "[:foo/bar]"})]
      (is (false? (:is-error result)))
      (is (= {:foo/bar 42} (read-string (:content result))))))

  (testing "canonical action-based query is equivalent"
    (let [query-fn (fn [q]
                     (is (= [:foo/bar] q))
                     {:foo/bar 42})
          tool     (tools/make-psi-tool query-fn)
          result   ((:execute tool) {"action" "query" "query" "[:foo/bar]"})]
      (is (false? (:is-error result)))
      (is (= {:foo/bar 42} (read-string (:content result)))))))

(deftest make-psi-tool-nested-query-test
  (testing "nested EQL query with joins"
    (let [query-fn (fn [_q]
                     {:psi.agent-session/stats {:total-messages 5}})
          tool     (tools/make-psi-tool query-fn)
          result   ((:execute tool)
                    {"query" "[{:psi.agent-session/stats [:total-messages]}]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result))))))

(deftest make-psi-tool-validation-test
  (let [tool (tools/make-psi-tool (fn [_q] {}))]
    (testing "missing action and query returns explicit validation error"
      (let [result ((:execute tool) {})]
        (is (true? (:is-error result)))
        (is (re-find #"action is required" (:content result)))))

    (testing "unknown action returns explicit validation error"
      (let [result ((:execute tool) {"action" "wat"})]
        (is (true? (:is-error result)))
        (is (re-find #"Unknown psi-tool action" (:content result)))))

    (testing "eval requires ns"
      (let [result ((:execute tool) {"action" "eval" "form" "(+ 1 2)"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires `ns`" (:content result)))))

    (testing "eval requires form"
      (let [result ((:execute tool) {"action" "eval" "ns" "clojure.core"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires `form`" (:content result)))))

    (testing "reload-code rejects simultaneous targeting modes"
      (let [result ((:execute tool) {"action" "reload-code"
                                     "namespaces" ["psi.agent-session.tools"]
                                     "worktree-path" "/tmp"})]
        (is (true? (:is-error result)))
        (is (re-find #"exactly one targeting mode" (:content result)))))

    (testing "project-repl requires valid op"
      (let [result ((:execute tool) {"action" "project-repl"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires valid `op`" (:content result)))))

    (testing "project-repl eval requires code"
      (let [result ((:execute tool) {"action" "project-repl" "op" "eval"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires `code`" (:content result)))))

    (testing "workflow requires valid op"
      (let [result ((:execute tool) {"action" "workflow"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires valid `op`" (:content result)))))

    (testing "workflow create-run requires definition-id or definition"
      (let [result ((:execute tool) {"action" "workflow" "op" "create-run"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires `definition-id` or `definition`" (:content result)))))

    (testing "workflow create-run rejects both definition-id and definition"
      (let [result ((:execute tool) {"action" "workflow"
                                     "op" "create-run"
                                     "definition-id" "x"
                                     "definition" "{:step-order [] :steps {}}"})]
        (is (true? (:is-error result)))
        (is (re-find #"either `definition-id` or `definition`, not both" (:content result)))))

    (testing "workflow read-run requires run-id"
      (let [result ((:execute tool) {"action" "workflow" "op" "read-run"})]
        (is (true? (:is-error result)))
        (is (re-find #"requires `run-id`" (:content result)))))))

(deftest make-psi-tool-invalid-edn-test
  (testing "invalid EDN returns error"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"query" "[not valid edn!!!"})]
      (is (true? (:is-error result)))
      (is (re-find #"EQL query error" (:content result))))))

(deftest make-psi-tool-not-a-vector-test
  (testing "non-vector EDN returns error"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"query" ":just-a-keyword"})]
      (is (true? (:is-error result)))
      (is (re-find #"must be an EDN vector" (:content result))))))

(deftest make-psi-tool-eval-test
  (let [tool (tools/make-psi-tool (fn [_q] {}))]
    (testing "eval runs in named namespace"
      (let [result ((:execute tool) {"action" "eval"
                                     "ns" "clojure.core"
                                     "form" "(ns-name *ns*)"})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :eval (:psi-tool/action parsed)))
        (is (= "clojure.core" (:psi-tool/ns parsed)))
        (is (= "clojure.core" (:psi-tool/value parsed)))
        (is (= "clojure.lang.Symbol" (:psi-tool/value-type parsed)))
        (is (integer? (:psi-tool/duration-ms parsed)))))

    (testing "eval rejects unknown namespace"
      (let [result ((:execute tool) {"action" "eval"
                                     "ns" "psi.no.such.ns"
                                     "form" "(+ 1 2)"})
            parsed (read-string (:content result))]
        (is (true? (:is-error result)))
        (is (= :eval (:psi-tool/action parsed)))
        (is (= "psi.no.such.ns" (:psi-tool/ns parsed)))
        (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

    (testing "eval rejects invalid form text safely"
      (let [result ((:execute tool) {"action" "eval"
                                     "ns" "clojure.core"
                                     "form" "#=(+ 1 2)"})
            parsed (read-string (:content result))]
        (is (true? (:is-error result)))
        (is (= :eval (:psi-tool/action parsed)))
        (is (= :eval (get-in parsed [:psi-tool/error :phase])))))

    (testing "eval errors return structured failure output"
      (let [result ((:execute tool) {"action" "eval"
                                     "ns" "clojure.core"
                                     "form" "(throw (ex-info \"boom\" {:secret [:x]}))"})
            parsed (read-string (:content result))]
        (is (true? (:is-error result)))
        (is (= :eval (:psi-tool/action parsed)))
        (is (= "boom" (get-in parsed [:psi-tool/error :message])))
        (is (= {:secret [:x]} (get-in parsed [:psi-tool/error :data])))))

    (testing "eval ignores unrelated extra arguments"
      (let [result ((:execute tool) {"action" "eval"
                                     "ns" "clojure.core"
                                     "form" "(+ 1 2)"
                                     "query" "[:ignored]"
                                     "worktree-path" "/tmp"})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= "3" (:psi-tool/value parsed)))))))

(deftest make-psi-tool-reload-code-test
  (testing "namespace mode rejects empty namespace vectors"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code" "namespaces" []})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :reload-code (:psi-tool/action parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "namespace mode rejects duplicate namespace names"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code"
                                   "namespaces" ["clojure.string" "clojure.string"]})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :reload-code (:psi-tool/action parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "namespace mode rejects blank namespace names"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code"
                                   "namespaces" ["clojure.string" ""]})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :reload-code (:psi-tool/action parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "namespace mode rejects unloaded namespaces"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code"
                                   "namespaces" ["psi.no.such.ns"]})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :reload-code (:psi-tool/action parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "namespace mode reloads exactly requested namespaces in request order"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code"
                                   "namespaces" ["clojure.string" "clojure.edn"]})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :namespaces (:psi-tool/reload-mode parsed)))
      (is (= ["clojure.string" "clojure.edn"] (:psi-tool/namespaces-requested parsed)))
      (is (= ["clojure.string" "clojure.edn"] (get-in parsed [:psi-tool/code-reload :namespaces])))
      (is (= :ok (get-in parsed [:psi-tool/code-reload :status])))
      (is (= :ok (get-in parsed [:psi-tool/graph-refresh :status])))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= "preserved current extension registry without rediscovery"
             (get-in parsed [:psi-tool/graph-refresh :steps 3 :summary])))))

  (testing "reloading model namespaces reinitializes the model registry for the effective worktree"
    (let [dir (str (java.nio.file.Files/createTempDirectory "psi-tool-model-registry-reload-"
                                                            (make-array java.nio.file.attribute.FileAttribute 0)))
          captured-init-opts* (atom nil)]
      (try
        (with-redefs [model-registry/init! (fn [opts]
                                             (reset! captured-init-opts* opts)
                                             :ok)
                      model-registry/all-models-seq (fn [] [{:provider :local :id "m1"} {:provider :openai :id "m2"}])
                      model-registry/get-load-error (fn [] nil)]
          (psi-tool/reload-namespace! dir "psi.ai.models")
          (is (= {:user-models-path (model-registry/default-user-models-path)
                  :project-models-path (str dir "/.psi/models.edn")}
                 @captured-init-opts*)))
        (finally
          (delete-tree! dir)))))

  (testing "namespace mode stops at first namespace failure and reports successful prefix"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result (with-redefs [clojure.core/require (fn [ns-sym & _]
                                                      (when (= 'clojure.edn ns-sym)
                                                        (throw (ex-info "boom" {:ns ns-sym}))))]
                   ((:execute tool) {"action" "reload-code"
                                     "namespaces" ["clojure.string" "clojure.edn" "clojure.walk"]}))
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (get-in parsed [:psi-tool/code-reload :status])))
      (is (= ["clojure.string"] (get-in parsed [:psi-tool/code-reload :namespaces])))
      (is (= :ok (get-in parsed [:psi-tool/graph-refresh :status])))
      (is (= :error (:psi-tool/overall-status parsed)))))

  (testing "worktree mode uses session worktree-path when explicit target absent"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :source-path (str worktree-path "/components/agent-session/src/psi/agent_session/tools.clj")}])]
      (let [[ctx session-id] (create-session-context {:persist? false
                                                      :session-defaults {:worktree-path (System/getProperty "user.dir")}})
            tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id :cwd (System/getProperty "user.dir")})
            result           ((:execute tool) {"action" "reload-code"})
            parsed           (read-string (:content result))]
        (is (= :worktree (:psi-tool/reload-mode parsed)))
        (is (= :session (:psi-tool/worktree-source parsed)))
        (is (string? (:psi-tool/worktree-path parsed)))
        (is (= :ok (get-in parsed [:psi-tool/code-reload :status])))
        (is (contains? parsed :psi-tool/graph-refresh))
        (is (contains? (get-in parsed [:psi-tool/graph-refresh :steps 3]) :install)))))

  (testing "worktree mode rejects non-absolute explicit worktree-path"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code" "worktree-path" "relative/path"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "worktree mode rejects non-existent explicit worktree-path"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code" "worktree-path" "/definitely/not/here"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "worktree mode rejects unreloadable explicit worktree-path"
    (let [dir (str (java.nio.file.Files/createTempDirectory "psi-tools-test-"
                                                            (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (let [tool   (tools/make-psi-tool (fn [_q] {}))
              result ((:execute tool) {"action" "reload-code" "worktree-path" dir})
              parsed (read-string (:content result))]
          (is (true? (:is-error result)))
          (is (= :validate (get-in parsed [:psi-tool/error :phase]))))
        (finally
          (delete-tree! dir)))))

  (testing "worktree mode explicit target reports explicit worktree source"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :source-path (str worktree-path "/components/agent-session/src/psi/agent_session/tools.clj")}])]
      (let [dir    (System/getProperty "user.dir")
            tool   (tools/make-psi-tool (fn [_q] {}))
            result ((:execute tool) {"action" "reload-code" "worktree-path" dir})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :explicit (:psi-tool/worktree-source parsed)))
        (is (= dir (:psi-tool/worktree-path parsed))))))

  (testing "worktree mode graph refresh surfaces extension reload errors"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :source-path (str worktree-path "/components/agent-session/src/psi/agent_session/tools.clj")}])
                  extension-runtime/reload-extensions-in!
                  (fn [& _] {:loaded [] :errors [{:path "/x" :error "broken"}]})]
      (let [[ctx session-id] (create-session-context {:persist? false
                                                      :session-defaults {:worktree-path (System/getProperty "user.dir")}})
            tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id :cwd (System/getProperty "user.dir")})
            result           ((:execute tool) {"action" "reload-code"})
            parsed           (read-string (:content result))]
        (is (true? (:is-error result)))
        (is (= :error (get-in parsed [:psi-tool/graph-refresh :status])))
        (is (= :error (:psi-tool/overall-status parsed))))))

  (testing "worktree mode graph refresh reports manifest install apply summary"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :source-path (str worktree-path "/components/agent-session/src/psi/agent_session/tools.clj")}])
                  extension-runtime/reload-extensions-in!
                  (fn [& _]
                    {:loaded ["/tmp/ext.clj"]
                     :errors []
                     :install-state
                     {:psi.extensions/effective
                      {:entries-by-lib
                       {'foo/local {:status :loaded}
                        'bar/remote {:status :restart-required}
                        'support/lib {:status :not-applicable}}}
                      :psi.extensions/diagnostics
                      [{:severity :info :category :restart-required :message "restart required"}]
                      :psi.extensions/last-apply
                      {:status :restart-required
                       :restart-required? true
                       :summary "restart required for remote deps"}}})]
      (let [[ctx session-id] (create-session-context {:persist? false
                                                      :session-defaults {:worktree-path (System/getProperty "user.dir")}})
            tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id :cwd (System/getProperty "user.dir")})
            result           ((:execute tool) {"action" "reload-code"})
            parsed           (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :restart-required (get-in parsed [:psi-tool/graph-refresh :steps 3 :install :status])))
        (is (= true (get-in parsed [:psi-tool/graph-refresh :steps 3 :install :restart-required?])))
        (is (= ['bar/remote] (get-in parsed [:psi-tool/graph-refresh :steps 3 :install :restart-required-libs])))
        (is (= {:loaded 1 :restart-required 1 :not-applicable 1}
               (get-in parsed [:psi-tool/graph-refresh :steps 3 :install :status-counts])))
        (is (= 1 (get-in parsed [:psi-tool/graph-refresh :steps 3 :install :diagnostic-count]))))))

  (testing "namespace mode may target loaded project namespaces"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"action" "reload-code"
                                   "namespaces" ["psi.agent-session.tools"]})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= ["psi.agent-session.tools"] (get-in parsed [:psi-tool/code-reload :namespaces]))))))

(deftest make-psi-tool-project-repl-test
  (testing "project-repl status reports absent instance"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "project-repl" "op" "status"})
          parsed           (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :project-repl (:psi-tool/action parsed)))
      (is (= :status (:psi-tool/project-repl-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= :absent (get-in parsed [:psi-tool/project-repl :status])))))

  (testing "project-repl uses explicit worktree when provided"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          dir              (System/getProperty "user.dir")
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "project-repl" "op" "status" "worktree-path" dir})
          parsed           (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= dir (:psi-tool/worktree-path parsed)))))

  (testing "project-repl status errors without resolvable worktree"
    (let [tool   (tools/make-psi-tool (fn [_q] {}) {})
          result ((:execute tool) {"action" "project-repl" "op" "status"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :project-repl (:psi-tool/action parsed)))
      (is (= :error (:psi-tool/overall-status parsed)))))

  (testing "project-repl start returns structured started payload"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/start (fn [_ctx worktree-path]
                                                                {:status :started
                                                                 :instance {:worktree-path worktree-path
                                                                            :acquisition-mode :started
                                                                            :lifecycle-state :ready
                                                                            :readiness true
                                                                            :endpoint {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}}})]
        (let [result ((:execute tool) {"action" "project-repl" "op" "start"})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :started (get-in parsed [:psi-tool/project-repl :status])))
          (is (= :started (get-in parsed [:psi-tool/project-repl :instance :acquisition-mode])))))))

  (testing "project-repl attach returns structured attached payload"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/attach (fn [_ctx worktree-path attach-input]
                                                                 {:status :attached
                                                                  :instance {:worktree-path worktree-path
                                                                             :acquisition-mode :attached
                                                                             :lifecycle-state :ready
                                                                             :readiness true
                                                                             :endpoint {:host (or (:host attach-input) "127.0.0.1")
                                                                                        :port (:port attach-input)
                                                                                        :port-source :explicit}}})]
        (let [result ((:execute tool) {"action" "project-repl" "op" "attach" "host" "127.0.0.1" "port" 7888})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :attached (get-in parsed [:psi-tool/project-repl :status])))
          (is (= 7888 (get-in parsed [:psi-tool/project-repl :instance :endpoint :port])))))))

  (testing "project-repl stop returns prior acquisition mode"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/stop (fn [_ctx _worktree-path]
                                                               {:status :stopped
                                                                :had-instance? true
                                                                :prior-acquisition-mode :started})]
        (let [result ((:execute tool) {"action" "project-repl" "op" "stop"})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :stopped (get-in parsed [:psi-tool/project-repl :status])))
          (is (= :started (get-in parsed [:psi-tool/project-repl :prior-acquisition-mode])))))))

  (testing "project-repl eval returns structured ok payload"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/eval-op (fn [_ctx _worktree-path _code]
                                                                  {:status :ok :value "3" :out "" :err "" :ns "user"})]
        (let [result ((:execute tool) {"action" "project-repl" "op" "eval" "code" "(+ 1 2)"})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :ok (get-in parsed [:psi-tool/project-repl :status])))
          (is (= "3" (get-in parsed [:psi-tool/project-repl :value])))))))

  (testing "project-repl interrupt returns structured reason"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/interrupt (fn [_ctx _worktree-path]
                                                                    {:status :ok :reason :interrupted})]
        (let [result ((:execute tool) {"action" "project-repl" "op" "interrupt"})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :ok (get-in parsed [:psi-tool/project-repl :status])))
          (is (= :interrupted (get-in parsed [:psi-tool/project-repl :reason])))))))

  (testing "project-repl configuration/runtime errors return structured failure output"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (with-redefs [psi.agent-session.project-nrepl-ops/start (fn [_ctx worktree-path]
                                                                (throw (ex-info "Project nREPL start requires a configured start-command"
                                                                                {:phase :config
                                                                                 :worktree-path worktree-path})))]
        (let [result ((:execute tool) {"action" "project-repl" "op" "start"})
              parsed (read-string (:content result))]
          (is (true? (:is-error result)))
          (is (= :error (:psi-tool/overall-status parsed)))
          (is (= :config (get-in parsed [:psi-tool/error :phase]))))))))

(deftest make-psi-tool-query-fn-throws-test
  (testing "query-fn exception is caught and returned as error"
    (let [tool   (tools/make-psi-tool
                  (fn [_q] (throw (ex-info "resolver failed" {}))))
          result ((:execute tool) {"query" "[:foo]"})]
      (is (true? (:is-error result)))
      (is (re-find #"resolver failed" (:content result))))))

(deftest make-psi-tool-truncation-test
  (testing "truncated psi-tool output includes spill path and narrowing guidance"
    (let [tool   (tools/make-psi-tool
                  (fn [_q] {:big (apply str (repeat 500 "x"))})
                  {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 80}}
                   :tool-call-id "test-call-id"})
          result ((:execute tool) {"query" "[:big]"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Use a narrower query" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill)))))

  (testing "truncated eval output preserves visible action metadata"
    (let [tool   (tools/make-psi-tool
                  (fn [_q] {})
                  {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 120}}
                   :tool-call-id "test-eval-trunc"})
          result ((:execute tool) {"action" "eval"
                                   "ns" "clojure.core"
                                   "form" "(apply str (repeat 200 \"x\"))"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Eval action=eval ns=clojure.core" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill)))))

  (testing "truncated reload output preserves visible worktree metadata"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :source-path (str worktree-path "/src/a.clj")}])]
      (let [tool   (tools/make-psi-tool
                    (fn [_q] {})
                    {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 140}}
                     :tool-call-id "test-reload-trunc"})
            dir    (System/getProperty "user.dir")
            result ((:execute tool) {"action" "reload-code"
                                     "worktree-path" dir})
            spill  (get-in result [:details :full-output-path])]
        (is (false? (:is-error result)))
        (is (re-find #"Output truncated" (:content result)))
        (is (re-find #"Reload action=reload-code mode=worktree worktree-path=" (:content result)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote dir)) (:content result)))
        (is (string? spill))
        (is (.exists (io/file spill)))))))

(deftest make-psi-tool-read-eval-disabled-test
  (testing "read-eval is disabled for safety"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"query" "#=(+ 1 2)"})]
      (is (true? (:is-error result))))))

(deftest make-psi-tool-sanitizes-recursive-session-ctx-test
  (testing "result maps containing :psi/agent-session-ctx do not overflow printer"
    (let [cyclic (let [l (java.util.ArrayList.)]
                   (.add l l)
                   l)
          tool   (tools/make-psi-tool
                  (fn [_q]
                    {:psi.agent-session/api-errors
                     [{:psi.api-error/http-status 400
                       :psi/agent-session-ctx     {:cyclic cyclic}}]}))
          result ((:execute tool) {"query" "[:psi.agent-session/api-errors]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result)))
      (is (re-find #"api-errors" (:content result)))
      (is (not (re-find #":psi/agent-session-ctx" (:content result)))))))

(deftest execute-tool-dispatch-test
  (testing "built-in dispatch handles read/bash/edit/write"
    (let [tmp (java.io.File/createTempFile "psi-dispatch-test" "")]
      (.delete tmp)
      (.mkdirs tmp)
      (try
        (spit (io/file tmp "a.txt") "alpha")
        (let [dir          (.getAbsolutePath tmp)
              read-result  (tools/execute-tool "read" {"path" "a.txt"} {:cwd dir})
              bash-result  (tools/execute-tool "bash" {"command" "cat a.txt"} {:cwd dir})
              edit-result  (tools/execute-tool "edit" {"path" "a.txt" "oldText" "alpha" "newText" "beta"} {:cwd dir})
              write-result (tools/execute-tool "write" {"path" "b.txt" "content" "gamma"} {:cwd dir})]
          (is (false? (:is-error read-result)))
          (is (= "alpha" (:content read-result)))
          (is (false? (:is-error bash-result)))
          (is (str/includes? (:content bash-result) "alpha"))
          (is (false? (:is-error edit-result)))
          (is (= "beta" (slurp (io/file dir "a.txt"))))
          (is (false? (:is-error write-result)))
          (is (= "gamma" (slurp (io/file dir "b.txt")))))
        (finally
          (doseq [file (reverse (file-seq tmp))]
            (.delete file))))))

  (testing "built-in dispatch rejects removed search tools"
    (doseq [tool-name ["ls" "find" "grep"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
                            (tools/execute-tool tool-name {})))))

  (testing "built-in dispatch does not handle psi-tool"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
                          (tools/execute-tool "psi-tool" {"query" "[:foo]"})))))

(deftest psi-tool-integration-test
  (testing "psi-tool works with a real session context"
    (let [[ctx _]            (create-session-context {:persist? false})
          sd                 (session/new-session-in! ctx nil {})
          session-id         (:session-id sd)
          tool               (tools/make-psi-tool (fn [q] (session/query-in ctx session-id q)))
          exec               (:execute tool)
          result             (exec {"query" "[:psi.agent-session/phase]"})
          parsed             (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :idle (:psi.agent-session/phase parsed)))))

  (testing "psi-tool returns session-id from live context"
    (let [[ctx _]            (create-session-context {:persist? false})
          sd                 (session/new-session-in! ctx nil {})
          session-id         (:session-id sd)
          tool               (tools/make-psi-tool (fn [q] (session/query-in ctx session-id q)))
          exec               (:execute tool)
          result             (exec {"query" "[:psi.agent-session/session-id]"})
          parsed             (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= session-id (:psi.agent-session/session-id parsed)))))

  (testing "psi-tool supports canonical explicit session targeting via entity seed"
    (let [[ctx active-session-id] (create-session-context {:persist? false})
          child-sd                (session/new-session-in! ctx active-session-id {})
          child-session-id        (:session-id child-sd)
          _                       (swap! (:state* ctx)
                                         assoc-in
                                         [:agent-session :sessions child-session-id :data :session-name]
                                         "helper session")
          _                       (swap! (:state* ctx)
                                         assoc-in
                                         [:agent-session :sessions child-session-id :data :model]
                                         {:provider "local" :id "gemma-3" :reasoning false})
          tool                    (tools/make-psi-tool (fn
                                                         ([q] (session/query-in ctx active-session-id q))
                                                         ([q entity] (session/query-in ctx q entity))))
          exec                    (:execute tool)
          legacy-result           (exec {"query" "[:psi.agent-session/session-id :psi.agent-session/session-name :psi.agent-session/model-id]"
                                         "entity" (str "{:psi.agent-session/session-id \"" child-session-id "\"}")})
          action-result           (exec {"action" "query"
                                         "query" "[:psi.agent-session/session-id :psi.agent-session/session-name :psi.agent-session/model-id]"
                                         "entity" (str "{:psi.agent-session/session-id \"" child-session-id "\"}")})
          legacy-parsed           (read-string (:content legacy-result))
          action-parsed           (read-string (:content action-result))]
      (is (false? (:is-error legacy-result)))
      (is (false? (:is-error action-result)))
      (is (= legacy-parsed action-parsed))
      (is (= child-session-id (:psi.agent-session/session-id action-parsed)))
      (is (= "helper session" (:psi.agent-session/session-name action-parsed)))
      (is (= "gemma-3" (:psi.agent-session/model-id action-parsed)))))

  (testing "psi-tool reports explicit errors for missing session targeting on session-scoped attrs"
    (let [[ctx _active-session-id] (create-session-context {:persist? false})
          tool                     (tools/make-psi-tool (fn [q] (session/query-in ctx q)))
          exec                     (:execute tool)
          result                   (exec {"query" "[:psi.agent-session/model-id]"})]
      (is (true? (:is-error result)))
      (is (re-find #"EQL query error:" (:content result)))))

  (testing "psi-tool rejects non-map entity seeds"
    (let [tool   (tools/make-psi-tool (fn [_q] {}))
          result ((:execute tool) {"query" "[:foo/bar]" "entity" "[:not-a-map]"})]
      (is (true? (:is-error result)))
      (is (re-find #"Entity must be an EDN map" (:content result))))))

