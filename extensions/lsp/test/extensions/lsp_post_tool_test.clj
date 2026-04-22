(ns extensions.lsp-post-tool-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.query.core :as query]))

(defn- mkdirs! [path]
  (.mkdirs (io/file path))
  path)

(defn- spit! [path content]
  (mkdirs! (.getParent (io/file path)))
  (spit path content)
  path)

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "psi-lsp-post-tool-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest lsp-post-tool-handler-syncs-workspace-test
  (testing "post-tool handler ensures service, initializes once, syncs files, and appends diagnostics"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          path (spit! (str root "/src/foo/core.clj") "(ns foo.core)")
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/lsp.clj"})
          api' (assoc api
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :service-request (fn [spec]
                                         (let [base ((:mutate api) 'psi.extension/service-request spec)]
                                           (if (= "textDocument/diagnostic" (get (:payload spec) "method"))
                                             (assoc base :psi.extension.service/response
                                                    {"result" {"items" [{"message" "Unused require"
                                                                         "severity" 2}]}})
                                             base)))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec)))
          handler (sut/make-post-tool-handler api')
          input {:tool-name "write"
                 :tool-call-id "call-1"
                 :tool-result {:content "ok"
                               :is-error false
                               :details nil
                               :meta {:tool-name "write" :tool-call-id "call-1"}
                               :effects [{:type "file/write"
                                          :path path
                                          :worktree-path root}]
                               :enrichments []}
                 :worktree-path root
                 :config nil}
          contribution1 (handler input)
          _             (spit path "(ns foo.core)\n(def x 1)")
          contribution2 (handler input)]
      (is (= [{:key [:lsp (.getCanonicalPath (io/file root))]
               :type :subprocess
               :spec {:command ["clojure-lsp"]
                      :cwd (.getCanonicalPath (io/file root))
                      :transport :stdio
                      :protocol :json-rpc
                      :env nil}}
              {:key [:lsp (.getCanonicalPath (io/file root))]
               :type :subprocess
               :spec {:command ["clojure-lsp"]
                      :cwd (.getCanonicalPath (io/file root))
                      :transport :stdio
                      :protocol :json-rpc
                      :env nil}}]
             (:services @state)))
      (is (= 3 (count (:service-requests @state))))
      (is (= 3 (count (:service-notifications @state))))
      (is (= "initialize"
             (get-in @state [:service-requests 0 :payload "method"])))
      (is (= "textDocument/diagnostic"
             (get-in @state [:service-requests 1 :payload "method"])))
      (is (= "textDocument/diagnostic"
             (get-in @state [:service-requests 2 :payload "method"])))
      (is (= ["initialized" "textDocument/didOpen" "textDocument/didChange"]
             (mapv #(get-in % [:payload "method"])
                   (:service-notifications @state))))
      (is (= 1
             (get-in contribution1 [:details/merge :lsp :diagnostic-path-count])))
      (is (= 1
             (get-in contribution2 [:details/merge :lsp :diagnostic-path-count])))
      (is (= 1
             (count (get-in contribution1 [:enrichments 1 :data :diagnostics]))))
      (is (= 1
             (get-in contribution1 [:details/merge :lsp :document-syncs 0 :version])))
      (is (= 2
             (get-in contribution2 [:details/merge :lsp :document-syncs 0 :version])))
      (is (= "LSP workspace synced"
             (get-in contribution1 [:enrichments 0 :label])))
      (is (= (str "LSP diagnostics: " path)
             (get-in contribution1 [:enrichments 1 :label])))
      (is (re-find #"Unused require"
                   (:content/append contribution1))))))

(deftest lsp-post-tool-handler-failure-is-non-fatal-test
  (testing "diagnostics/runtime failure becomes structured enrichment instead of throwing"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          path (spit! (str root "/src/foo/core.clj") "(ns foo.core)")
          {:keys [api]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :ensure-service (fn [_] (throw (ex-info "boom" {})))
                      :service-request (fn [_] nil)
                      :service-notify (fn [_] nil))
          handler (sut/make-post-tool-handler api')
          contribution (handler {:tool-name "write"
                                 :tool-call-id "call-err"
                                 :tool-result {:content "ok"
                                               :is-error false
                                               :details nil
                                               :meta {:tool-name "write" :tool-call-id "call-err"}
                                               :effects [{:type "file/write"
                                                          :path path
                                                          :worktree-path root}]
                                               :enrichments []}
                                 :worktree-path root
                                 :config nil})]
      (is (= "boom"
             (get-in contribution [:details/merge :lsp :error])))
      (is (= "LSP diagnostics unavailable"
             (get-in contribution [:enrichments 0 :label]))))))

(deftest lsp-post-tool-handler-live-runtime-diagnostics-test
  (testing "post-tool handler can use the live runtime path and append real diagnostics"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          path (spit! (str root "/src/foo/core.clj") "(ns foo.core) ;; warn\n")
          qctx  (query/create-query-context)
          ctx   (session/create-context (test-support/safe-context-opts {:cwd root}))
          sd    (session/new-session-in! ctx nil {:worktree-path root})
          session-id (:session-id sd)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                        op))
          query* (fn [eql] (session/query-in ctx session-id eql))
          runtime-fns {:query-fn query*
                       :mutate-fn mutate
                       :get-api-key-fn (fn [_] nil)
                       :ui-type-fn (fn [] :console)
                       :ui-context-fn (fn [_] nil)
                       :log-fn (fn [_] nil)}
          _ (session/register-resolvers-in! qctx false)
          _ (session/register-mutations-in! qctx mutations/all-mutations true)
          api (ext/create-extension-api (:extension-registry ctx) "/ext/lsp.clj" runtime-fns)
          handler (sut/make-post-tool-handler api)
          contribution (handler {:tool-name "write"
                                 :tool-call-id "call-live"
                                 :tool-result {:content "ok"
                                               :is-error false
                                               :details nil
                                               :effects [{:path path}]
                                               :enrichments []}
                                 :worktree-path root
                                 :config {:command [(or (System/getenv "BB") "bb")
                                                    (str (.getCanonicalPath (java.io.File. "extensions/lsp/test/extensions/lsp_fixture_bb.clj")))]
                                          :startup-timeout-ms 2000
                                          :diagnostics-timeout-ms 2000}})
          svc (services/service-in ctx (sut/workspace-key root))]
      (try
        (is (str/includes? (:content/append contribution) "fixture diagnostic for"))
        (is (= "LSP workspace synced"
               (get-in contribution [:enrichments 0 :label])))
        (is (= (str "LSP diagnostics: " path)
               (get-in contribution [:enrichments 1 :label])))
        (is (= 1
               (get-in contribution [:details/merge :lsp :diagnostic-path-count])))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))
