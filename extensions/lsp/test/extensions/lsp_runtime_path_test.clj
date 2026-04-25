(ns extensions.lsp-runtime-path-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.tool-batch :as tool-batch]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.query.core :as query]))

(def ^:private lsp-fixture-command
  [(or (System/getenv "BB") "bb")
   (str (.getCanonicalPath (java.io.File. "extensions/lsp/test/extensions/lsp_fixture_bb.clj")))])

(defn- create-temp-worktree []
  (let [dir (io/file (test-support/temp-cwd))
        git (io/file dir ".git")]
    (.mkdirs dir)
    (.mkdirs git)
    (.getCanonicalPath dir)))

(defn- write-file! [path text]
  (spit (io/file path) text)
  path)

(defn- create-runtime-session [worktree]
  (let [ctx (session/create-context (test-support/safe-context-opts {:cwd worktree}))
        sd  (session/new-session-in! ctx nil {:worktree-path worktree})]
    [ctx (:session-id sd)]))

(defn- create-ext-api [ctx session-id]
  (let [qctx   (query/create-query-context)
        mutate (fn [op params]
                 (get (query/query-in qctx
                                      {:psi/agent-session-ctx ctx}
                                      [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                      op))
        query* (fn [eql]
                 (session/query-in ctx eql {:session-id session-id}))
        runtime-fns {:query-fn query*
                     :mutate-fn mutate
                     :get-api-key-fn (fn [_] nil)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] nil)
                     :log-fn (fn [_] nil)}]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (ext/create-extension-api (:extension-registry ctx) "/ext/lsp.clj" runtime-fns)))

(defn- await-diagnostic-finding
  [api workspace-root file-path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [remaining-ms (max 1 (- deadline (System/currentTimeMillis)))
            diagnostics-by-path (sut/request-diagnostics! api {:workspace-root workspace-root
                                                               :paths [file-path]
                                                               :diagnostics-timeout-ms (min 1000 remaining-ms)})
            finding (get diagnostics-by-path file-path)]
        (if (or (seq finding)
                (>= (System/currentTimeMillis) deadline))
          finding
          (do
            (Thread/sleep 100)
            (recur)))))))

(deftest service-spec-roundtrips-through-real-runtime-test
  (testing "extension service spec can be ensured through mutation path and gets live runtime fns"
    (let [[ctx session-id] (create-runtime-session (create-temp-worktree))
          api (create-ext-api ctx session-id)
          root (create-temp-worktree)
          spec (sut/service-spec root {:command lsp-fixture-command})]
      (try
        ((:ensure-service api)
         {:key (sut/workspace-key root)
          :type :subprocess
          :spec spec})
        (let [svc (services/service-in ctx (sut/workspace-key root))]
          (is (= :json-rpc (:protocol svc)))
          (is (fn? (:send-fn svc)))
          (is (fn? (:await-response-fn svc))))
        (finally
          (when-let [close-fn (:close-fn (services/service-in ctx (sut/workspace-key root)))]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest sync-tool-result-canonical-trace-observability-test
  (testing "post-tool LSP sync produces one coherent canonical trace chain"
    (dispatch/clear-dispatch-trace!)
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (post-tool/register-processor-in!
             ctx
             {:name "lsp-diagnostics"
              :ext-path "/ext/lsp.clj"
              :match {:tools #{"write"}}
              :timeout-ms 2000
              :handler (fn [input]
                         ((sut/make-post-tool-handler api)
                          (assoc input
                                 :config {:command lsp-fixture-command
                                          :startup-timeout-ms 2000
                                          :diagnostics-timeout-ms 2000
                                          :sync-timeout-ms 2000})))})
          _ (post-tool/run-post-tool-processing-in!
             ctx
             {:session-id session-id
              :tool-name "write"
              :tool-call-id "call-trace"
              :tool-result {:effects [{:path file-path}]}
              :worktree-path worktree
              :config {:command lsp-fixture-command
                       :startup-timeout-ms 2000
                       :diagnostics-timeout-ms 2000
                       :sync-timeout-ms 2000}})
          entries (dispatch/dispatch-trace-entries)
          received (some #(when (and (= :dispatch/received (:trace/kind %))
                                     (= :session/post-tool-run (:event-type %)))
                            %)
                         entries)
          dispatch-id (:dispatch-id received)]
      (try
        (is (= :dispatch/received (:trace/kind received)))
        (is (= :session/post-tool-run (:event-type received)))
        (is (string? dispatch-id))
        (is (some #(and (= :dispatch/service-request (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "initialize" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/service-response (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "initialize" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/service-notify (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "initialized" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/service-notify (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "textDocument/didOpen" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/service-request (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "textDocument/diagnostic" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/service-response (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= "textDocument/diagnostic" (:method %)))
                  entries))
        (is (some #(and (= :dispatch/completed (:trace/kind %))
                        (= dispatch-id (:dispatch-id %))
                        (= :session/post-tool-run (:event-type %)))
                  entries))
        (let [qtrace (:psi.dispatch-trace/recent
                      (session/query-in ctx
                                        [{:psi.dispatch-trace/recent [:psi.dispatch-trace/trace-kind
                                                                      :psi.dispatch-trace/dispatch-id
                                                                      :psi.dispatch-trace/event-type
                                                                      :psi.dispatch-trace/method
                                                                      :psi.dispatch-trace/interceptor-id]}]
                                        {:session-id session-id}))
              qtrace-by-id (:psi.dispatch-trace/by-id
                            (session/query-in ctx
                                              [{:psi.dispatch-trace/by-id [:psi.dispatch-trace/trace-kind
                                                                           :psi.dispatch-trace/dispatch-id
                                                                           :psi.dispatch-trace/event-type
                                                                           :psi.dispatch-trace/method
                                                                           :psi.dispatch-trace/effect-type
                                                                           :psi.dispatch-trace/interceptor-id]}]
                                              {:session-id session-id
                                               :psi.dispatch-trace/dispatch-id dispatch-id}))]
          (is (some #(and (= :dispatch/received (:psi.dispatch-trace/trace-kind %))
                          (= dispatch-id (:psi.dispatch-trace/dispatch-id %))
                          (= :session/post-tool-run (:psi.dispatch-trace/event-type %)))
                    qtrace))
          (is (some #(and (= :dispatch/service-request (:psi.dispatch-trace/trace-kind %))
                          (= dispatch-id (:psi.dispatch-trace/dispatch-id %))
                          (= "initialize" (:psi.dispatch-trace/method %)))
                    qtrace))
          (is (seq qtrace-by-id))
          (is (every? #(= dispatch-id (:psi.dispatch-trace/dispatch-id %)) qtrace-by-id)))
        (finally
          (when-let [svc (services/service-in ctx (sut/workspace-key worktree))]
            (when-let [close-fn (:close-fn svc)]
              (future (close-fn))))
          (future (services/stop-service-in! ctx (sut/workspace-key worktree))))))))

(deftest sync-tool-result-runs-live-initialize-sync-and-diagnostics-test
  (testing "sync-tool-result! uses the live managed-service path and returns diagnostics"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          result (sut/sync-tool-result! api
                                        {:worktree-path worktree
                                         :tool-result {:effects [{:path file-path}]}
                                         :config {:command lsp-fixture-command
                                                  :startup-timeout-ms 2000
                                                  :diagnostics-timeout-ms 2000
                                                  :sync-timeout-ms 2000}})
          root (:workspace-root result)
          svc (services/service-in ctx (sut/workspace-key root))
          debug @(:debug-atom svc)
          notifications @(:notifications-atom svc)]
      (try
        (is (= root worktree))
        (is (sut/workspace-initialized? root))
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen" :sync-kind :incremental}]
               (:document-syncs result)))
        (is (= [{"message" (str "fixture diagnostic for " file-path)
                 "severity" 2}]
               (get (:diagnostics-by-path result) file-path)))
        (is (some #(and (= :send (:event %))
                        (= "initialize" (get-in % [:payload "method"])))
                  debug)
            (str {:debug debug :notifications notifications}))
        (is (some #(and (= :send (:event %))
                        (= "initialized" (get-in % [:payload "method"])))
                  debug))
        (is (some #(and (= :send (:event %))
                        (= "textDocument/didOpen" (get-in % [:payload "method"])))
                  debug))
        (is (some #(and (= :send (:event %))
                        (= "textDocument/diagnostic" (get-in % [:payload "method"])))
                  debug))
        (is (some #(or (= "observed" (get-in % [:payload "method"]))
                       (= "textDocument/publishDiagnostics" (get-in % [:payload "method"])))
                  notifications))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest live-lsp-post-tool-is-recorded-into-final-toolresult-test
  (testing "real LSP post-tool runtime appends diagnostics into the final recorded provider-facing toolResult"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (post-tool/register-processor-in!
             ctx
             {:name "lsp-diagnostics"
              :ext-path "/ext/lsp.clj"
              :match {:tools #{"write"}}
              :timeout-ms 2000
              :handler (fn [input]
                         ((sut/make-post-tool-handler api)
                          (assoc input
                                 :config {:command lsp-fixture-command
                                          :startup-timeout-ms 2000
                                          :diagnostics-timeout-ms 2000
                                          :sync-timeout-ms 2000})))})
          recorded (atom nil)
          tc {:id "call-live-lsp" :name "write" :arguments "{}"}]
      (try
        (with-redefs [tool-plan/execute-tool-runtime-in!
                      (fn [_ _ _ _]
                        {:content (str "Successfully wrote 18 bytes to " file-path)
                         :is-error false
                         :details nil
                         :effects [{:type "file/write"
                                    :path file-path
                                    :worktree-path worktree
                                    :bytes 18}]
                         :enrichments []})
                      agent/emit-tool-start-in! (fn [_ _] nil)
                      agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                      agent/record-tool-result-in! (fn [_ msg] (reset! recorded msg) nil)]
          (#'tool-batch/run-tool-call! ctx session-id tc nil)
          (is (some? @recorded))
          (is (= "call-live-lsp" (:tool-call-id @recorded)))
          (is (str/includes? (:result-text @recorded)
                             (str "fixture diagnostic for " file-path)))
          (is (str/includes? (:result-text @recorded)
                             (str "LSP diagnostics for " file-path ":")))
          (is (= [{:type :text
                   :text (:result-text @recorded)}]
                 (:content @recorded)))
          (is (= 1
                 (get-in @recorded [:details :lsp :diagnostic-path-count]))))
        (finally
          (when-let [svc (services/service-in ctx (sut/workspace-key worktree))]
            (when-let [close-fn (:close-fn svc)]
              (future (close-fn))))
          (future (services/stop-service-in! ctx (sut/workspace-key worktree))))))))

(deftest sync-tool-result-subsequent-sync-uses-didchange-test
  (testing "subsequent syncs for an open document use didChange"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (write-file! file-path "(ns demo)\n")
          first-result (sut/sync-tool-result! api
                                              {:worktree-path worktree
                                               :tool-result {:effects [{:path file-path}]}
                                               :config {:command lsp-fixture-command
                                                        :startup-timeout-ms 2000
                                                        :diagnostics-timeout-ms 5000}})
          _ (write-file! file-path "(ns demo) ;; warn\n")
          second-result (sut/sync-tool-result! api
                                               {:worktree-path worktree
                                                :tool-result {:effects [{:path file-path}]}
                                                :config {:command lsp-fixture-command
                                                         :startup-timeout-ms 2000
                                                         :diagnostics-timeout-ms 10000}})
          root (:workspace-root second-result)
          finding (await-diagnostic-finding api root file-path 10000)
          svc (services/service-in ctx (sut/workspace-key root))
          debug @(:debug-atom svc)]
      (try
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen" :sync-kind :incremental}]
               (:document-syncs first-result)))
        (is (= [{:path file-path :version 2 :first-open? false :method "textDocument/didChange" :sync-kind :incremental}]
               (:document-syncs second-result)))
        (is (some #(= 2 (get-in % [:payload "params" "textDocument" "version"])) debug))
        (is (some #(= "textDocument/didChange" (get-in % [:payload "method"])) debug))
        (is (some #(get-in % [:payload "params" "contentChanges" 0 "range"]) debug))
        (is (= [{"message" (str "fixture diagnostic for " file-path)
                 "severity" 2}]
               finding))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest restart-workspace-closes-documents-and-restarts-service-test
  (testing "restart-workspace! sends didClose and clears tracked document state"
    (dispatch/clear-dispatch-trace!)
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          first-result (sut/sync-tool-result! api
                                              {:worktree-path worktree
                                               :tool-result {:effects [{:path file-path}]}
                                               :config {:command lsp-fixture-command
                                                        :startup-timeout-ms 2000
                                                        :diagnostics-timeout-ms 5000}})
          root worktree
          svc-before (services/service-in ctx (sut/workspace-key root))
          restarted-root (sut/restart-workspace! api {:worktree-path worktree
                                                      :config {:command lsp-fixture-command}})
          debug-before @(:debug-atom svc-before)
          cleared-document-state (sut/document-state file-path)
          cleared-initialized? (sut/workspace-initialized? root)
          _ (write-file! file-path "(ns demo) ;; warn again\n")
          second-result (sut/sync-tool-result! api
                                               {:worktree-path worktree
                                                :tool-result {:effects [{:path file-path}]}
                                                :config {:command lsp-fixture-command
                                                         :startup-timeout-ms 2000
                                                         :diagnostics-timeout-ms 10000
                                                         :sync-timeout-ms 2000}})
          finding (or (get (:diagnostics-by-path second-result) file-path)
                      (await-diagnostic-finding api root file-path 10000))
          svc-after (services/service-in ctx (sut/workspace-key root))
          entries (dispatch/dispatch-trace-entries)]
      (try
        (is (= root restarted-root))
        (is (some #(= "textDocument/didClose" (get-in % [:payload "method"])) debug-before))
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen" :sync-kind :incremental}]
               (:document-syncs first-result)))
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen" :sync-kind :incremental}]
               (:document-syncs second-result)))
        (is (= [{"message" (str "fixture diagnostic for " file-path)
                 "severity" 2}]
               finding))
        (is (nil? cleared-document-state))
        (is (false? cleared-initialized?))
        (is (= :running (:status svc-after)))
        (is (not= (:pid svc-before) (:pid svc-after)))
        (is (>= (count (filter #(and (= :dispatch/service-request (:trace/kind %))
                                     (= "initialize" (:method %)))
                               entries))
                2))
        (is (>= (count (filter #(and (= :dispatch/service-request (:trace/kind %))
                                     (= "textDocument/diagnostic" (:method %)))
                               entries))
                2))
        (finally
          (when-let [close-fn (:close-fn svc-after)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest workspace-status-lines-reflect-live-service-state-test
  (testing "workspace-status-lines reports initialized service and tracked documents"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo)\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (sut/sync-tool-result! api
                                   {:worktree-path worktree
                                    :tool-result {:effects [{:path file-path}]}
                                    :config {:command lsp-fixture-command
                                             :startup-timeout-ms 2000
                                             :diagnostics-timeout-ms 2000}})
          lines (sut/workspace-status-lines api {:worktree-path worktree})]
      (try
        (is (= (str "LSP workspace: " worktree) (first lines)))
        (is (some #(= "Initialized: true" %) lines))
        (is (some #(= "Service status: :running" %) lines))
        (is (some #(str/includes? % file-path) lines))
        (finally
          (when-let [svc (services/service-in ctx (sut/workspace-key worktree))]
            (when-let [close-fn (:close-fn svc)]
              (future (close-fn))))
          (future (services/stop-service-in! ctx (sut/workspace-key worktree))))))))
