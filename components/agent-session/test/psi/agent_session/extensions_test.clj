(ns psi.agent-session.extensions-test
  "Tests for the extension registry, dispatch, tool wrapping, and introspection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.agent-session.extensions :as ext]
   [psi.tool-registry.registry :as tool-registry]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.test-support :as test-support]))

(deftest registry-isolation-test
  (testing "two registries are independent"
    (let [reg-a (ext/create-registry)
          reg-b (ext/create-registry)]
      (ext/register-extension-in! reg-a "/ext/a")
      (is (= 1 (ext/extension-count-in reg-a)))
      (is (= 0 (ext/extension-count-in reg-b))))))

(deftest register-extension-test
  (testing "register-extension-in! adds path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/foo")
      (is (= ["/ext/foo"] (ext/extensions-in reg)))))

  (testing "registering same path twice is idempotent"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/foo")
      (ext/register-extension-in! reg "/ext/foo")
      (is (= 1 (ext/extension-count-in reg)))))

  (testing "unregister-extension-in! removes one extension from live registry"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/foo")
      (ext/register-extension-in! reg "/ext/bar")
      (ext/unregister-extension-in! reg "/ext/foo")
      (is (= ["/ext/bar"] (ext/extensions-in reg)))
      (is (= 1 (ext/extension-count-in reg)))))

  (testing "setting allowed events before registration does not hide extension from registration order"
    (let [reg (ext/create-registry)]
      (ext/set-allowed-events-in! reg "/ext/foo" #{:session/ui-notify})
      (ext/register-extension-in! reg "/ext/foo")
      (is (= ["/ext/foo"] (ext/extensions-in reg)))
      (is (= 1 (ext/extension-count-in reg))))))

(deftest handler-registration-test
  (testing "register-handler-in! adds handler"
    (let [reg (ext/create-registry)
          h   (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "my_event" h)
      (is (= 1 (ext/handler-count-in reg)))))

  (testing "multiple handlers for same event accumulate"
    (let [reg (ext/create-registry)
          h1  (fn [_] nil)
          h2  (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "my_event" h1)
      (ext/register-handler-in! reg "/ext/a" "my_event" h2)
      (is (= 2 (ext/handler-count-in reg))))))

;; ── Tool and command registration ──────────────────────────────────────────

(deftest tool-command-registration-test
  (testing "command names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "/do-thing"})
      (is (contains? (ext/command-names-in reg) "/do-thing")))))

;; ── Event dispatch ──────────────────────────────────────────────────────────

(deftest dispatch-broadcast-test
  (testing "all handlers fire (broadcast)"
    (let [reg    (ext/create-registry)
          fired  (atom [])
          h1     (fn [ev] (swap! fired conj [:h1 (:value ev)]) nil)
          h2     (fn [ev] (swap! fired conj [:h2 (:value ev)]) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "test_event" h1)
      (ext/register-handler-in! reg "/ext/b" "test_event" h2)
      (ext/dispatch-in reg "test_event" {:value 42})
      (is (= [[:h1 42] [:h2 42]] @fired))))

  (testing "registration order preserved"
    (let [reg   (ext/create-registry)
          order (atom [])
          h1    (fn [_] (swap! order conj 1) nil)
          h2    (fn [_] (swap! order conj 2) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h1)
      (ext/register-handler-in! reg "/ext/b" "e" h2)
      (ext/dispatch-in reg "e" {})
      (is (= [1 2] @order)))))

(deftest dispatch-cancel-test
  (testing "cancel: true in result sets :cancelled?"
    (let [reg (ext/create-registry)
          h   (fn [_] {:cancel true})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "before_switch" h)
      (let [result (ext/dispatch-in reg "before_switch" {})]
        (is (true? (:cancelled? result))))))

  (testing "cancel: true does NOT suppress remaining handlers"
    (let [reg       (ext/create-registry)
          fired     (atom [])
          h-cancel  (fn [_] (swap! fired conj :cancel-handler) {:cancel true})
          h-after   (fn [_] (swap! fired conj :after-handler) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h-cancel)
      (ext/register-handler-in! reg "/ext/b" "e" h-after)
      (ext/dispatch-in reg "e" {})
      (is (= [:cancel-handler :after-handler] @fired))))

  (testing "no cancel when no handler returns cancel"
    (let [reg (ext/create-registry)
          h   (fn [_] {:ok true})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" h)
      (let [result (ext/dispatch-in reg "e" {})]
        (is (false? (:cancelled? result)))))))

(deftest dispatch-override-test
  (testing ":result in handler return is captured as :override"
    (let [reg    (ext/create-registry)
          result {:summary "custom" :first-kept-entry-id "e1" :tokens-before 1000}
          h      (fn [_] {:result result})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" h)
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= result (:override dispatch-result))))))

  (testing ":compaction in handler return is captured as :override"
    (let [reg    (ext/create-registry)
          result {:summary "custom" :first-kept-entry-id "e2" :tokens-before 2000}
          h      (fn [_] {:compaction result})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" h)
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= result (:override dispatch-result))))))

  (testing ":result nil wins over later :compaction fallback"
    (let [reg (ext/create-registry)
          r2  {:summary "second" :first-kept-entry-id "e2" :tokens-before 200}]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" (fn [_] {:result nil}))
      (ext/register-handler-in! reg "/ext/b" "session_before_compact" (fn [_] {:compaction r2}))
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (true? (:override-present? dispatch-result)))
        (is (nil? (:override dispatch-result))))))

  (testing "canonical :result wins over later legacy :compaction"
    (let [reg    (ext/create-registry)
          r1     {:summary "first" :first-kept-entry-id "e1" :tokens-before 100}
          r2     {:summary "second" :first-kept-entry-id "e2" :tokens-before 200}]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" (fn [_] {:result r1}))
      (ext/register-handler-in! reg "/ext/b" "session_before_compact" (fn [_] {:compaction r2}))
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (true? (:override-present? dispatch-result)))
        (is (= r1 (:override dispatch-result))))))

  (testing "last canonical :result wins when multiple handlers return canonical overrides"
    (let [reg    (ext/create-registry)
          r1     {:summary "first" :first-kept-entry-id "e1" :tokens-before 100}
          r2     {:summary "second" :first-kept-entry-id "e2" :tokens-before 200}]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" (fn [_] {:result r1}))
      (ext/register-handler-in! reg "/ext/b" "session_before_compact" (fn [_] {:result r2}))
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= r2 (:override dispatch-result))))))

  (testing "no override when handler returns nil"
    (let [reg (ext/create-registry)
          h   (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" h)
      (let [result (ext/dispatch-in reg "e" {})]
        (is (nil? (:override result)))))))

(deftest dispatch-no-handlers-test
  (testing "dispatch with no handlers returns safe defaults"
    (let [reg    (ext/create-registry)
          result (ext/dispatch-in reg "no_handlers_event" {})]
      (is (false? (:cancelled? result)))
      (is (nil? (:override result)))
      (is (= [] (:results result))))))

(deftest dispatch-non-map-return-test
  (testing "handler returning a String does not throw and produces no override"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_switch"
                                (fn [_] "some-branch-name"))
      (let [result (ext/dispatch-in reg "session_switch" {:reason :new})]
        (is (false? (:cancelled? result)))
        (is (nil? (:override result)))
        (is (false? (:override-present? result))))))

  (testing "handler returning a Long does not throw and produces no override"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_switch" (fn [_] 1))
      (let [result (ext/dispatch-in reg "session_switch" {:reason :new})]
        (is (false? (:cancelled? result)))
        (is (nil? (:override result)))
        (is (false? (:override-present? result))))))

  (testing "non-map return does not suppress a canonical :result override from another handler"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" (fn [_] "plain-string"))
      (ext/register-handler-in! reg "/ext/b" "e" (fn [_] {:result "override-val"}))
      (let [result (ext/dispatch-in reg "e" {})]
        (is (= "override-val" (:override result)))))))

(deftest dispatch-exception-test
  (testing "handler exception is captured and does not abort dispatch"
    (let [reg    (ext/create-registry)
          fired  (atom false)
          h-bad  (fn [_] (throw (Exception. "boom")))
          h-ok   (fn [_] (reset! fired true) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h-bad)
      (ext/register-handler-in! reg "/ext/b" "e" h-ok)
      (ext/dispatch-in reg "e" {})
      (is (true? @fired)))))

;; ── Unregister all ──────────────────────────────────────────────────────────

(deftest unregister-all-test
  (testing "unregister-all-in! clears registry"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" (fn [_] nil))
      (ext/unregister-all-in! reg)
      (is (= 0 (ext/extension-count-in reg)))
      (is (= 0 (ext/handler-count-in reg)))))

  (testing "unregister-all-in! also clears runtime deterministic operations when provided"
    (let [reg    (ext/create-registry)
          op-reg (op-reg/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-operation-in! reg "/ext/a" {:id "github/search"
                                                :handler (fn [_] {:status :ok :data {}})})
      (op-reg/register-operation-in! op-reg {:id "github/search"
                                             :ext-path "/ext/a"
                                             :handler (fn [_] {:status :ok :data {}})})
      (ext/unregister-all-in! reg op-reg)
      (is (= 0 (ext/extension-count-in reg)))
      (is (= [] (op-reg/operation-ids-in op-reg))))))

;; ── Summary ─────────────────────────────────────────────────────────────────

(deftest summary-test
  (testing "summary-in returns expected keys"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" (fn [_] nil))
      (tool-registry/register-tool-in! reg "/ext/a" {:name "t1" :format-request (fn [_] "t1")})
      (let [s (ext/summary-in reg)]
        (is (= 1 (:extension-count s)))
        (is (= 1 (:handler-count s)))
        (is (contains? (:tool-names s) "t1"))
        (is (contains? (:handler-events s) "e"))))))

(deftest extension-introspection-operation-projection-test
  (testing "operation projection surfaces remain coherent while runtime ownership is external"
    (let [reg    (ext/create-registry)
          op-reg (op-reg/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-operation-in! reg "/ext/a" {:id "github/search"
                                                :handler (fn [_] {:status :ok :data {}})})
      (ext/register-operation-in! reg "/ext/b" {:id "jira/search"
                                                :handler (fn [_] {:status :ok :data {}})})
      (op-reg/register-operation-in! op-reg {:id "github/search"
                                             :ext-path "/ext/a"
                                             :handler (fn [_] {:status :ok :data {}})})
      (op-reg/register-operation-in! op-reg {:id "jira/search"
                                             :ext-path "/ext/b"
                                             :handler (fn [_] {:status :ok :data {}})})
      (is (= #{"github/search" "jira/search"}
             (ext/operation-ids-in reg)))
      (is (= {:path "/ext/a"
              :handler-names #{}
              :handler-count 0
              :tool-names #{}
              :tool-count 0
              :operation-ids #{"github/search"}
              :operation-count 1
              :command-names #{}
              :command-count 0
              :flag-names #{}
              :flag-count 0
              :shortcut-count 0
              :allowed-events (:allowed-events (ext/extension-detail-in reg "/ext/a"))}
             (ext/extension-detail-in reg "/ext/a")))
      (is (= [#{"github/search"} #{"jira/search"}]
             (mapv :operation-ids (ext/extension-details-in reg))))
      (is (= #{"github/search" "jira/search"}
             (:operation-ids (ext/summary-in reg))))
      (ext/unregister-extension-in! reg "/ext/a" op-reg)
      (is (= #{"jira/search"}
             (ext/operation-ids-in reg)))
      (is (= #{"jira/search"}
             (:operation-ids (ext/summary-in reg)))))))

;; ── Flag management ─────────────────────────────────────────────────────────

(deftest flag-registration-test
  (testing "register-flag-in! with default sets initial value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default true})
      (is (= true (ext/get-flag-in reg "verbose")))))

  (testing "set-flag-in! updates value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default false})
      (ext/set-flag-in! reg "verbose" true)
      (is (= true (ext/get-flag-in reg "verbose")))))

  (testing "flag without default has nil value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "mode" :type :string})
      (is (nil? (ext/get-flag-in reg "mode")))))

  (testing "flag names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean})
      (is (contains? (ext/flag-names-in reg) "f1"))))

  (testing "all-flag-values-in returns complete map"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean :default true})
      (ext/register-flag-in! reg "/ext/a" {:name "f2" :type :string :default "x"})
      (is (= {"f1" true "f2" "x"} (ext/all-flag-values-in reg))))))

;; ── Event bus ───────────────────────────────────────────────────────────────

(deftest event-bus-test
  (testing "bus-emit fires subscribers"
    (let [reg     (ext/create-registry)
          received (atom nil)]
      (ext/bus-on-in! reg "test-channel" (fn [data] (reset! received data)))
      (ext/bus-emit-in! reg "test-channel" {:value 42})
      (is (= {:value 42} @received))))

  (testing "bus-on-in! returns unsubscribe fn"
    (let [reg     (ext/create-registry)
          count-a (atom 0)
          unsub   (ext/bus-on-in! reg "ch" (fn [_] (swap! count-a inc)))]
      (ext/bus-emit-in! reg "ch" {})
      (is (= 1 @count-a))
      (unsub)
      (ext/bus-emit-in! reg "ch" {})
      (is (= 1 @count-a))))

  (testing "multiple subscribers receive same event"
    (let [reg    (ext/create-registry)
          fired  (atom [])]
      (ext/bus-on-in! reg "ch" (fn [d] (swap! fired conj [:a d])))
      (ext/bus-on-in! reg "ch" (fn [d] (swap! fired conj [:b d])))
      (ext/bus-emit-in! reg "ch" :x)
      (is (= [[:a :x] [:b :x]] @fired)))))

;; ── Tool wrapping ───────────────────────────────────────────────────────────

(deftest tool-wrapping-test
  (testing "wrap-tool-executor passes through when no handlers"
    (let [reg        (ext/create-registry)
          execute-fn (fn [tool-name _args] {:content (str tool-name " ok") :is-error false})
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "read ok" :is-error false}
             (wrapped "read" {})))))

  (testing "wrap-tool-executor remains an extension-local compatibility wrapper"
    (let [reg        (ext/create-registry)
          calls      (atom [])
          execute-fn (fn [tool-name args]
                       (swap! calls conj {:tool-name tool-name :args args})
                       {:content "ok" :is-error false})
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "ok" :is-error false}
             (wrapped "echo" {"x" 1})))
      (is (= [{:tool-name "echo" :args {"x" 1}}] @calls))))

  (testing "tool_call handler can block execution"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args]
                       (throw (Exception. "should not be called")))
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_call"
                                               (fn [_] {:block true :reason "blocked!"}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "blocked!" :is-error true}
             (wrapped "bash" {"command" "rm -rf /"})))))

  (testing "tool_result handler can modify result"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args] {:content "original" :is-error false})
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_result"
                                               (fn [_] {:content "modified"}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "modified" :is-error false}
             (wrapped "read" {"path" "test.txt"})))))

  (testing "tool_result handler can modify is-error"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args] {:content "result" :is-error false})
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_result"
                                               (fn [_] {:is-error true}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "result" :is-error true}
             (wrapped "read" {"path" "test.txt"})))))

  (testing "tool_result handler returning non-map is silently ignored"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args] {:content "original" :is-error false})
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_result"
                                               (fn [_] "not-a-map"))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "original" :is-error false}
             (wrapped "read" {"path" "test.txt"}))))))

;; ── Introspection: handler event names ──────────────────────────────────────

(deftest handler-event-names-test
  (testing "handler-event-names-in returns sorted set of event names"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_switch" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "model_select" (fn [_] nil))
      (is (= #{"model_select" "session_switch" "tool_call"}
             (ext/handler-event-names-in reg))))))

;; ── Introspection: all tools/commands/flags ─────────────────────────────────

(deftest all-commands-in-test
  (testing "all-commands-in returns commands with extension-path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "hello" :description "say hi"})
      (let [cmds (ext/all-commands-in reg)]
        (is (= 1 (count cmds)))
        (is (= "hello" (:name (first cmds))))
        (is (= "/ext/a" (:extension-path (first cmds))))))))

(deftest all-flags-in-test
  (testing "all-flags-in includes current values"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default true})
      (let [flags (ext/all-flags-in reg)]
        (is (= 1 (count flags)))
        (is (= "verbose" (:name (first flags))))
        (is (= true (:current-value (first flags))))))))

;; ── Introspection: extension details ────────────────────────────────────────

(deftest extension-detail-test
  (testing "extension-detail-in returns detail map"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "tool_result" (fn [_] nil))
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :format-request (fn [_] "my-tool")})
      (ext/register-command-in! reg "/ext/a" {:name "cmd"})
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean})
      (let [d (ext/extension-detail-in reg "/ext/a")]
        (is (= "/ext/a" (:path d)))
        (is (= #{"tool_call" "tool_result"} (:handler-names d)))
        (is (= 2 (:handler-count d)))
        (is (= #{"my-tool"} (:tool-names d)))
        (is (= 1 (:tool-count d)))
        (is (= #{"cmd"} (:command-names d)))
        (is (= 1 (:command-count d)))
        (is (= #{"f1"} (:flag-names d)))
        (is (= 1 (:flag-count d))))))

  (testing "extension-detail-in returns nil for unknown path"
    (let [reg (ext/create-registry)]
      (is (nil? (ext/extension-detail-in reg "/ext/unknown")))))

  (testing "extension-details-in returns all extensions"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (let [details (ext/extension-details-in reg)]
        (is (= 2 (count details)))
        (is (= ["/ext/a" "/ext/b"] (mapv :path details)))))))

;; ── Get command/tool by name ────────────────────────────────────────────────

(deftest get-command-in-test
  (testing "returns command by name"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "hello" :handler identity})
      (is (= "hello" (:name (ext/get-command-in reg "hello"))))))

  (testing "returns nil for unknown command"
    (let [reg (ext/create-registry)]
      (is (nil? (ext/get-command-in reg "nope"))))))

;; ── Extension API (factory invocation) ──────────────────────────────────────

(deftest unregister-extension-removes-runtime-deterministic-operations-test
  (let [reg    (ext/create-registry)
        op-reg (op-reg/create-registry)]
    (ext/register-extension-in! reg "/ext/a")
    (ext/register-extension-in! reg "/ext/b")
    (ext/register-operation-in! reg "/ext/a" {:id "github/search"
                                              :handler (fn [_] {:status :ok :data {}})})
    (ext/register-operation-in! reg "/ext/b" {:id "jira/search"
                                              :handler (fn [_] {:status :ok :data {}})})
    (op-reg/register-operation-in! op-reg {:id "github/search"
                                           :ext-path "/ext/a"
                                           :handler (fn [_] {:status :ok :data {}})})
    (op-reg/register-operation-in! op-reg {:id "jira/search"
                                           :ext-path "/ext/b"
                                           :handler (fn [_] {:status :ok :data {}})})
    (ext/unregister-extension-in! reg "/ext/a" op-reg)
    (is (= #{"/ext/b"} (set (ext/extensions-in reg))))
    (is (= #{"jira/search"} (set (op-reg/operation-ids-in op-reg))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not found"
         (op-reg/invoke-operation-in op-reg "github/search" {:args {}} deterministic-op-runtime/invoke-operation)))))

(deftest reload-extensions-removes-stale-runtime-deterministic-operation-ids-test
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})
        runtime-fns      (runtime-fns/make-extension-runtime-fns ctx session-id nil)
        reg              (:extension-registry ctx)
        op-registry      (:deterministic-operation-registry ctx)
        ext-path         "/ext/test"]
    (ext/register-extension-in! reg ext-path)
    ((:register-deterministic-operation-fn runtime-fns)
     ext-path
     {:id "github/search-issues-by-label"
      :handler (fn [_] {:status :ok :data {:issues []}})})
    (is (= #{"github/search-issues-by-label"}
           (set (op-reg/operation-ids-in op-registry))))
    (ext/reload-extensions-in! reg runtime-fns [])
    (is (= [] (ext/extensions-in reg)))
    (is (= [] (op-reg/operation-ids-in op-registry)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not found"
         (op-reg/invoke-operation-in op-registry "github/search-issues-by-label" {:args {}} deterministic-op-runtime/invoke-operation)))))

