(ns psi.tui.extension-ui-test
  "Tests for the extension UI state: dialogs, widgets, status, notifications, render registry."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.tui.extension-ui :as ui]))

;; ── UI state creation ───────────────────────────────────────

(deftest create-ui-state-test
  (testing "creates an atom with expected structure"
    (let [s @(ui/create-ui-state)]
      (is (= {:pending [] :active nil} (:dialog-queue s)))
      (is (= {} (:widgets s)))
      (is (= {} (:statuses s)))
      (is (= [] (:notifications s)))
      (is (= {} (:tool-renderers s)))
      (is (= {} (:message-renderers s)))
      (is (false? (:tools-expanded? s))))))

;; ── Widgets ─────────────────────────────────────────────────

(deftest widget-lifecycle-test
  (testing "set-widget! adds a widget"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["Line 1" "Line 2"])
      (is (= 1 (count (ui/all-widgets ui))))
      (is (= "ext-a" (:extension-id (first (ui/all-widgets ui)))))
      (is (= ["Line 1" "Line 2"] (:content (first (ui/all-widgets ui)))))))

  (testing "set-widget! replaces existing widget"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["old"])
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["new"])
      (is (= 1 (count (ui/all-widgets ui))))
      (is (= ["new"] (:content (first (ui/all-widgets ui)))))))

  (testing "widgets-by-placement filters correctly"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "top" :above-editor ["above"])
      (ui/set-widget! ui "ext-a" "bot" :below-editor ["below"])
      (is (= 1 (count (ui/widgets-by-placement ui :above-editor))))
      (is (= 1 (count (ui/widgets-by-placement ui :below-editor))))
      (is (= ["above"] (:content (first (ui/widgets-by-placement ui :above-editor)))))))

  (testing "clear-widget! removes widget"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["x"])
      (ui/clear-widget! ui "ext-a" "w1")
      (is (empty? (ui/all-widgets ui)))))

  (testing "no-op on nil ui-state-atom"
    (ui/set-widget! nil "ext-a" "w1" :above-editor ["x"])
    (is (nil? (ui/all-widgets nil)))))

;; ── Status ──────────────────────────────────────────────────

(deftest status-lifecycle-test
  (testing "set-status! adds a status"
    (let [ui (ui/create-ui-state)]
      (ui/set-status! ui "ext-a" "Processing...")
      (is (= 1 (count (ui/all-statuses ui))))
      (is (= "Processing..." (:text (first (ui/all-statuses ui)))))))

  (testing "set-status! replaces existing status"
    (let [ui (ui/create-ui-state)]
      (ui/set-status! ui "ext-a" "old")
      (ui/set-status! ui "ext-a" "new")
      (is (= 1 (count (ui/all-statuses ui))))
      (is (= "new" (:text (first (ui/all-statuses ui)))))))

  (testing "clear-status! removes status"
    (let [ui (ui/create-ui-state)]
      (ui/set-status! ui "ext-a" "x")
      (ui/clear-status! ui "ext-a")
      (is (empty? (ui/all-statuses ui)))))

  (testing "multiple extensions have independent statuses"
    (let [ui (ui/create-ui-state)]
      (ui/set-status! ui "ext-a" "A status")
      (ui/set-status! ui "ext-b" "B status")
      (is (= 2 (count (ui/all-statuses ui)))))))

;; ── Notifications ───────────────────────────────────────────

(deftest notification-lifecycle-test
  (testing "notify! adds a notification"
    (let [ui (ui/create-ui-state)]
      (ui/notify! ui "ext-a" "Hello!" :info)
      (is (= 1 (count (ui/visible-notifications ui))))
      (is (= "Hello!" (:message (first (ui/visible-notifications ui)))))))

  (testing "visible-notifications caps at max-visible"
    (let [ui (ui/create-ui-state)]
      (dotimes [i 5]
        (ui/notify! ui "ext-a" (str "n" i) :info))
      (is (= 3 (count (ui/visible-notifications ui))))
      (is (= 5 (count (ui/visible-notifications ui 10))))))

  (testing "dismiss-overflow! dismisses oldest"
    (let [ui (ui/create-ui-state)]
      (dotimes [i 5]
        (ui/notify! ui "ext-a" (str "n" i) :info))
      (let [dismissed (ui/dismiss-overflow! ui 3)]
        (is (= 2 dismissed))
        (is (= 3 (count (ui/visible-notifications ui 10)))))))

  (testing "dismiss-expired! dismisses old notifications"
    (let [ui (ui/create-ui-state)]
      ;; Manually insert an old notification
      (swap! ui update :notifications conj
             {:id "old" :extension-id "ext-a" :message "old"
              :level :info :created-at (- (System/currentTimeMillis) 10000)
              :dismissed? false})
      (ui/notify! ui "ext-a" "new" :info)
      (let [dismissed (ui/dismiss-expired! ui 5000)]
        (is (= 1 dismissed))
        (is (= 1 (count (ui/visible-notifications ui 10)))))))

  (testing "no-op on nil"
    (ui/notify! nil "ext-a" "msg" :info)
    (is (nil? (ui/visible-notifications nil)))))

;; ── Dialog queue ────────────────────────────────────────────

(deftest dialog-queue-test
  (testing "starts empty"
    (let [ui (ui/create-ui-state)]
      (is (true? (ui/dialog-queue-empty? ui)))
      (is (nil? (ui/active-dialog ui)))
      (is (= 0 (ui/pending-dialog-count ui)))))

  (testing "enqueue promotes first dialog to active"
    (let [ui (ui/create-ui-state)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :title "T" :promise (promise)})]
      (is (= "d1" (:id (ui/active-dialog ui))))
      (is (= 0 (ui/pending-dialog-count ui)))
      (is (false? (ui/dialog-queue-empty? ui)))))

  (testing "second dialog queues while first is active"
    (let [ui (ui/create-ui-state)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise (promise)})
          _  (ui/enqueue-dialog! ui {:id "d2" :kind :confirm :promise (promise)})]
      (is (= "d1" (:id (ui/active-dialog ui))))
      (is (= 1 (ui/pending-dialog-count ui)))))

  (testing "resolve-dialog! advances queue"
    (let [ui (ui/create-ui-state)
          p1 (promise)
          p2 (promise)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise p1})
          _  (ui/enqueue-dialog! ui {:id "d2" :kind :confirm :promise p2})]
      (is (true? (ui/resolve-dialog! ui "d1" true)))
      (is (= true @p1))
      (is (= "d2" (:id (ui/active-dialog ui))))))

  (testing "cancel-dialog! delivers nil and advances"
    (let [ui (ui/create-ui-state)
          p1 (promise)
          p2 (promise)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise p1})
          _  (ui/enqueue-dialog! ui {:id "d2" :kind :confirm :promise p2})]
      (is (true? (ui/cancel-dialog! ui)))
      (is (nil? @p1))
      (is (= "d2" (:id (ui/active-dialog ui))))))

  (testing "resolve wrong dialog-id returns false"
    (let [ui (ui/create-ui-state)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise (promise)})]
      (is (false? (ui/resolve-dialog! ui "wrong-id" true))))))

;; ── Blocking dialog methods ─────────────────────────────────

(defn- wait-for-dialog
  "Spin-wait (max 500ms) until a dialog is active."
  [ui]
  (loop [n 0]
    (when (and (nil? (ui/active-dialog ui)) (< n 50))
      (Thread/sleep 10)
      (recur (inc n)))))

(deftest request-confirm-test
  (testing "confirm blocks and returns result"
    (let [ui     (ui/create-ui-state)
          result (future (ui/request-confirm! ui "ext-a" "Delete?" "Are you sure?"))]
      (wait-for-dialog ui)
      (is (some? (ui/active-dialog ui)))
      (is (= :confirm (:kind (ui/active-dialog ui))))
      (ui/resolve-dialog! ui (:id (ui/active-dialog ui)) true)
      (is (= true (deref result 1000 :timeout)))))

  (testing "confirm returns false when cancelled"
    (let [ui     (ui/create-ui-state)
          result (future (ui/request-confirm! ui "ext-a" "T" "M"))]
      (wait-for-dialog ui)
      (ui/cancel-dialog! ui)
      (is (= false (deref result 1000 :timeout)))))

  (testing "headless confirm returns false"
    (is (= false (ui/request-confirm! nil "ext-a" "T" "M")))))

(deftest request-select-test
  (testing "select blocks and returns selected value"
    (let [ui      (ui/create-ui-state)
          options [{:value "a" :label "A"} {:value "b" :label "B"}]
          result  (future (ui/request-select! ui "ext-a" "Pick:" options))]
      (wait-for-dialog ui)
      (ui/resolve-dialog! ui (:id (ui/active-dialog ui)) "b")
      (is (= "b" (deref result 1000 :timeout)))))

  (testing "headless select returns nil"
    (is (nil? (ui/request-select! nil "ext-a" "Pick:" [])))))

(deftest request-input-test
  (testing "input blocks and returns text"
    (let [ui     (ui/create-ui-state)
          result (future (ui/request-input! ui "ext-a" "Name:" "placeholder"))]
      (wait-for-dialog ui)
      (ui/resolve-dialog! ui (:id (ui/active-dialog ui)) "hello")
      (is (= "hello" (deref result 1000 :timeout)))))

  (testing "headless input returns nil"
    (is (nil? (ui/request-input! nil "ext-a" "Name:" nil)))))

;; ── Render registry ─────────────────────────────────────────

(deftest tool-renderer-test
  (testing "register and retrieve tool renderer"
    (let [ui      (ui/create-ui-state)
          call-fn (fn [_args] "call")
          res-fn  (fn [_result _opts] "result")]
      (ui/register-tool-renderer! ui "my-tool" "/ext/a" call-fn res-fn)
      (let [r (ui/get-tool-renderer ui "my-tool")]
        (is (= "my-tool" (:tool-name r)))
        (is (= "call" ((:render-call-fn r) {})))
        (is (= "result" ((:render-result-fn r) {} {}))))))

  (testing "all-tool-renderers returns all"
    (let [ui (ui/create-ui-state)]
      (ui/register-tool-renderer! ui "t1" "/ext/a" nil nil)
      (ui/register-tool-renderer! ui "t2" "/ext/b" nil nil)
      (is (= 2 (count (ui/all-tool-renderers ui))))))

  (testing "returns nil for unknown tool"
    (let [ui (ui/create-ui-state)]
      (is (nil? (ui/get-tool-renderer ui "nope"))))))

(deftest tools-expanded-api-test
  (testing "headless access uses safe defaults"
    (is (false? (ui/get-tools-expanded nil)))
    (is (nil? (ui/set-tools-expanded! nil true))))

  (testing "get/set tools-expanded toggles state"
    (let [ui (ui/create-ui-state)]
      (is (false? (ui/get-tools-expanded ui)))
      (ui/set-tools-expanded! ui true)
      (is (true? (ui/get-tools-expanded ui)))
      (ui/set-tools-expanded! ui false)
      (is (false? (ui/get-tools-expanded ui)))))

  (testing "clear-all resets tools-expanded to false"
    (let [ui (ui/create-ui-state)]
      (ui/set-tools-expanded! ui true)
      (ui/clear-all! ui)
      (is (false? (ui/get-tools-expanded ui))))))

(deftest message-renderer-test
  (testing "register and retrieve message renderer"
    (let [ui    (ui/create-ui-state)
          r-fn  (fn [_msg _opts] "rendered")]
      (ui/register-message-renderer! ui "my-type" "/ext/a" r-fn)
      (let [r (ui/get-message-renderer ui "my-type")]
        (is (= "my-type" (:custom-type r)))
        (is (= "rendered" ((:render-fn r) {} {}))))))

  (testing "returns nil for unknown type"
    (let [ui (ui/create-ui-state)]
      (is (nil? (ui/get-message-renderer ui "nope"))))))

;; ── Clear all ───────────────────────────────────────────────

(deftest clear-all-test
  (testing "clear-all! resets all state"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["x"])
      (ui/set-status! ui "ext-a" "status")
      (ui/notify! ui "ext-a" "note" :info)
      (ui/register-tool-renderer! ui "t1" "/ext/a" nil nil)
      (ui/register-message-renderer! ui "c1" "/ext/a" nil)
      (ui/clear-all! ui)
      (is (empty? (ui/all-widgets ui)))
      (is (empty? (ui/all-statuses ui)))
      (is (empty? (ui/visible-notifications ui 100)))
      (is (empty? (ui/all-tool-renderers ui)))
      (is (empty? (ui/all-message-renderers ui)))
      (is (true? (ui/dialog-queue-empty? ui)))))

  (testing "clear-all! cancels active dialog"
    (let [ui (ui/create-ui-state)
          p  (promise)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise p})]
      (ui/clear-all! ui)
      (is (nil? (deref p 1000 :timeout)))
      (is (nil? (ui/active-dialog ui)))))

  (testing "clear-all! cancels pending dialogs"
    (let [ui (ui/create-ui-state)
          p1 (promise)
          p2 (promise)
          _  (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :promise p1})
          _  (ui/enqueue-dialog! ui {:id "d2" :kind :confirm :promise p2})]
      (ui/clear-all! ui)
      (is (nil? (deref p1 1000 :timeout)))
      (is (nil? (deref p2 1000 :timeout))))))

;; ── UI context map ──────────────────────────────────────────

(deftest ui-context-test
  (testing "create-ui-context returns nil for nil atom"
    (is (nil? (ui/create-ui-context nil "ext-a"))))

  (testing "create-ui-context returns a map with all expected keys"
    (let [ui  (ui/create-ui-state)
          ctx (ui/create-ui-context ui "ext-a")]
      (is (fn? (:confirm ctx)))
      (is (fn? (:select ctx)))
      (is (fn? (:input ctx)))
      (is (fn? (:set-widget ctx)))
      (is (fn? (:clear-widget ctx)))
      (is (fn? (:set-status ctx)))
      (is (fn? (:clear-status ctx)))
      (is (fn? (:notify ctx)))
      (is (fn? (:register-tool-renderer ctx)))
      (is (fn? (:register-message-renderer ctx)))
      (is (fn? (:get-tools-expanded ctx)))
      (is (fn? (:set-tools-expanded ctx)))))

  (testing "context methods delegate to ui-state"
    (let [ui  (ui/create-ui-state)
          ctx (ui/create-ui-context ui "ext-a")]
      ;; Widget
      ((:set-widget ctx) "w1" :above-editor ["hello"])
      (is (= 1 (count (ui/all-widgets ui))))
      (is (= "ext-a" (:extension-id (first (ui/all-widgets ui)))))

      ;; Status
      ((:set-status ctx) "busy")
      (is (= 1 (count (ui/all-statuses ui))))

      ;; Notify
      ((:notify ctx) "done" :info)
      (is (= 1 (count (ui/visible-notifications ui))))

      ;; Register renderers
      ((:register-tool-renderer ctx) "my-tool" identity identity)
      (is (some? (ui/get-tool-renderer ui "my-tool")))

      ((:register-message-renderer ctx) "my-type" identity)
      (is (some? (ui/get-message-renderer ui "my-type")))

      ;; Tools-expanded API
      (is (false? ((:get-tools-expanded ctx))))
      ((:set-tools-expanded ctx) true)
      (is (true? ((:get-tools-expanded ctx))))
      ((:set-tools-expanded ctx) false)
      (is (false? ((:get-tools-expanded ctx)))))))

;; ── Snapshot ────────────────────────────────────────────────

(deftest snapshot-test
  (testing "snapshot returns serialisable data"
    (let [ui (ui/create-ui-state)]
      (ui/set-widget! ui "ext-a" "w1" :above-editor ["line"])
      (ui/set-status! ui "ext-a" "status")
      (ui/notify! ui "ext-a" "note" :info)
      (ui/register-tool-renderer! ui "t1" "/ext/a" identity identity)
      (ui/register-message-renderer! ui "c1" "/ext/a" identity)
      (let [snap (ui/snapshot ui)]
        (is (true? (:dialog-queue-empty? snap)))
        (is (nil? (:active-dialog snap)))
        (is (= 0 (:pending-dialog-count snap)))
        (is (= 1 (count (:widgets snap))))
        (is (= 1 (count (:statuses snap))))
        (is (= 1 (count (:visible-notifications snap))))
        ;; Renderers in snapshot have no fns
        (is (= 1 (count (:tool-renderers snap))))
        (is (nil? (:render-call-fn (first (:tool-renderers snap)))))
        (is (= 1 (count (:message-renderers snap))))
        (is (nil? (:render-fn (first (:message-renderers snap))))))))

  (testing "snapshot with active dialog excludes promise"
    (let [ui   (ui/create-ui-state)
          _    (ui/enqueue-dialog! ui {:id "d1" :kind :confirm :title "T" :promise (promise)})
          snap (ui/snapshot ui)]
      (is (false? (:dialog-queue-empty? snap)))
      (is (= "d1" (:id (:active-dialog snap))))
      (is (nil? (:promise (:active-dialog snap))))))

  (testing "snapshot returns nil for nil atom"
    (is (nil? (ui/snapshot nil)))))
