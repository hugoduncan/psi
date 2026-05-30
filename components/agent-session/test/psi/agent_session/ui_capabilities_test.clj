(ns psi.agent-session.ui-capabilities-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.ui-capabilities :as ui-capabilities]))

(def ^:private ui-query
  [:psi.ui/type
   :psi.ui/available?
   :psi.ui/capabilities
   :psi.ui/actions
   :psi.ui/make-visible-action
   :psi.ui/diagnostic])

(defn- create-ctx
  [opts]
  (session/create-context (test-support/safe-context-opts (assoc opts :persist? false))))

(defn- valid-provider-result
  [invocation]
  {:psi.ui/type :test-ui
   :psi.ui/available? true
   :psi.ui/capabilities [:psi.ui.capability/make-visible]
   :psi.ui/actions [(ui-capabilities/make-visible-action invocation)]})

(defn- provider-error?
  [result]
  (= :psi.ui.unavailable.reason/provider-error
     (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))

(deftest missing-provider-query-behaviour-test
  ;; Tests that headless/missing-provider UI queries return explicit unavailable data.
  (let [ctx (create-ctx {})
        _ (ui-capabilities/clear-provider! ctx)
        result (session/query-in ctx ui-query)]
    (is (= nil (:psi.ui/type result)))
    (is (= false (:psi.ui/available? result)))
    (is (= [] (:psi.ui/capabilities result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/no-provider
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest emacs-provider-query-behaviour-test
  ;; Tests that the Emacs provider advertises a supported make-visible action.
  (let [ctx (create-ctx {:ui-type :emacs})
        result (session/query-in ctx ui-query)
        action (:psi.ui/make-visible-action result)]
    (is (= :emacs (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [:psi.ui.capability/make-visible]
           (:psi.ui/capabilities result)))
    (is (= [action] (:psi.ui/actions result)))
    (is (= {:psi.ui.invocation/kind :emacs-command
            :psi.ui.invocation/command "psi-emacs-show-active"}
           (:psi.ui.action/invocation action)))
    (is (true? (:psi.ui.action/available? action)))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest emacs-rpc-provider-active-session-lifecycle-test
  ;; Tests that the Emacs RPC provider derives current active-session state at
  ;; query time and downgrades to no-attached when that state is unavailable.
  (let [active-session-id* (atom "s1")
        ctx (create-ctx {})]
    (ui-capabilities/install-provider!
     ctx
     (ui-capabilities/emacs-rpc-provider #(deref active-session-id*)))
    (let [result (session/query-in ctx ui-query)
          action (:psi.ui/make-visible-action result)]
      (is (= :emacs (:psi.ui/type result)))
      (is (= true (:psi.ui/available? result)))
      (is (= [:psi.ui.capability/make-visible]
             (:psi.ui/capabilities result)))
      (is (= "s1"
             (get-in action [:psi.ui.action/invocation :psi.ui.invocation/session-id]))))
    (doseq [unfocused-id [nil "" "   " "\n\t"]]
      (reset! active-session-id* unfocused-id)
      (let [result (session/query-in ctx ui-query)]
        (is (= :emacs (:psi.ui/type result)))
        (is (= false (:psi.ui/available? result)))
        (is (= [] (:psi.ui/capabilities result)))
        (is (= [] (:psi.ui/actions result)))
        (is (= :psi.ui.unavailable.reason/no-attached-ui
               (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))))
    (reset! active-session-id* "  s2  ")
    (let [result (session/query-in ctx ui-query)
          action (:psi.ui/make-visible-action result)]
      (is (= true (:psi.ui/available? result)))
      (is (= "s2"
             (get-in action [:psi.ui.action/invocation :psi.ui.invocation/session-id]))))))

(deftest console-provider-query-behaviour-test
  ;; Tests that attached console UI is available but unsupported for make-visible.
  (let [ctx (create-ctx {:ui-type :console})
        result (session/query-in ctx ui-query)]
    (is (= :console (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [] (:psi.ui/capabilities result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/unsupported-capability
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))))

(deftest provider-normalization-supported-make-visible-test
  ;; Tests that supported make-visible provider data is exposed as capabilities
  ;; plus available-only action descriptors.
  (let [result (ui-capabilities/normalize-provider-result
                (valid-provider-result {:psi.ui.invocation/kind :ui-event
                                        :psi.ui.invocation/event :psi.ui/show-active
                                        :psi.ui.invocation/payload {:source :test}}))
        action (:psi.ui/make-visible-action result)]
    (is (= :test-ui (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [:psi.ui.capability/make-visible] (:psi.ui/capabilities result)))
    (is (= [action] (:psi.ui/actions result)))
    (is (= {:psi.ui.invocation/kind :ui-event
            :psi.ui.invocation/event :psi.ui/show-active
            :psi.ui.invocation/payload {:source :test}}
           (:psi.ui.action/invocation action)))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest provider-normalization-no-attached-ui-test
  ;; Tests that an installed provider with no active UI returns no-attached semantics.
  (let [result (ui-capabilities/normalize-provider-result
                {:psi.ui/type :emacs
                 :psi.ui/available? false
                 :psi.ui/capabilities []
                 :psi.ui/actions []})]
    (is (= :emacs (:psi.ui/type result)))
    (is (= false (:psi.ui/available? result)))
    (is (= [] (:psi.ui/capabilities result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/no-attached-ui
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest provider-normalization-unsupported-make-visible-test
  ;; Tests that attached UI without make-visible support exposes only the
  ;; stable unavailable convenience descriptor.
  (let [result (ui-capabilities/normalize-provider-result
                {:psi.ui/type :tui
                 :psi.ui/available? true
                 :psi.ui/capabilities []
                 :psi.ui/actions []})]
    (is (= :tui (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/unsupported-capability
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))))

(deftest provider-normalization-invalid-data-test
  ;; Tests that invalid provider output maps to provider-error unavailable data.
  (let [ctx (create-ctx {})]
    (ui-capabilities/install-provider!
     ctx
     (fn [_]
       (valid-provider-result {:psi.ui.invocation/kind :unknown})))
    (let [result (session/query-in ctx ui-query)]
      (is (= false (:psi.ui/available? result)))
      (is (= [] (:psi.ui/capabilities result)))
      (is (= [] (:psi.ui/actions result)))
      (is (provider-error? result))
      (is (string? (:psi.ui/diagnostic result))))))

(deftest provider-error-diagnostic-redaction-test
  ;; Tests that provider-error diagnostics are bounded and redact stack frames,
  ;; frontend object forms, token/secret values, secret-bearing paths, and
  ;; arbitrary exception data before exposure through EQL.
  (let [ctx (create-ctx {})
        diagnostic-message (str "token=abc123 password: hunter2 Bearer secret-token "
                                "/tmp/secret-token/path #<buffer *psi*> "
                                "#object[emacs.Buffer 0xabc \"buf\"] "
                                "\n at psi.ui.Secret.invoke(Secret.java:42) "
                                "sk-abcdefghijklmnopqrstuvwxyz")]
    (ui-capabilities/install-provider!
     ctx
     (fn [_]
       (throw (ex-info diagnostic-message
                       {:token "exception-data-token"
                        :secret-path "/home/me/.ssh/id_rsa"}))))
    (let [diagnostic (:psi.ui/diagnostic (session/query-in ctx ui-query))]
      (is (string? diagnostic))
      (is (<= (count diagnostic) 512))
      (is (not (re-find #"abc123|hunter2|secret-token|abcdefghijklmnopqrstuvwxyz" diagnostic)))
      (is (not (re-find #"/tmp/secret-token|\.ssh|id_rsa" diagnostic)))
      (is (not (re-find #"#<buffer|#object|Secret\.java|\s+at\s+" diagnostic)))
      (is (not (re-find #"exception-data-token|secret-path" diagnostic)))
      (is (re-find #"REDACTED|STACKTRACE_REDACTED|OBJECT_REDACTED" diagnostic)))))

(deftest provider-normalization-invocation-kind-test
  ;; Tests the supported invocation-kind schemas without mocks.
  (let [valid-invocations [{:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"}
                           {:psi.ui.invocation/kind :ui-event
                            :psi.ui.invocation/event :psi.ui/show-active
                            :psi.ui.invocation/payload {:visible? true}}
                           {:psi.ui.invocation/kind :bash-command
                            :psi.ui.invocation/argv ["tmux" "switch-client" "-t" "psi"]
                            :psi.ui.invocation/env {"TMUX" "/tmp/tmux"}}
                           {:psi.ui.invocation/kind :mutation
                            :psi.ui.invocation/mutation 'psi.ui/show-active
                            :psi.ui.invocation/params {:visible? true}}]
        invalid-invocations [{:psi.ui.invocation/kind :emacs-command
                              :psi.ui.invocation/command ""}
                             {:psi.ui.invocation/kind :ui-event
                              :psi.ui.invocation/event :unqualified}
                             {:psi.ui.invocation/kind :bash-command
                              :psi.ui.invocation/argv ["tmux" ""]}
                             {:psi.ui.invocation/kind :mutation
                              :psi.ui.invocation/mutation 'show-active}
                             {:psi.ui.invocation/kind :unknown}]]
    (doseq [invocation valid-invocations]
      (is (= true (:psi.ui/available?
                   (ui-capabilities/normalize-provider-result
                    (valid-provider-result invocation))))
          (str "expected valid invocation: " (pr-str invocation))))
    (doseq [invocation invalid-invocations]
      (is (provider-error?
           (ui-capabilities/normalize-provider-result
            (valid-provider-result invocation)))
          (str "expected provider error for malformed invocation: " (pr-str invocation))))))

(deftest provider-normalization-defaulted-invocation-maps-test
  ;; Tests that optional UI event payload and mutation params are defaulted to
  ;; empty maps in exposed descriptors when providers omit them.
  (let [ui-event-result (ui-capabilities/normalize-provider-result
                         (valid-provider-result
                          {:psi.ui.invocation/kind :ui-event
                           :psi.ui.invocation/event :psi.ui/show-active}))
        mutation-result (ui-capabilities/normalize-provider-result
                         (valid-provider-result
                          {:psi.ui.invocation/kind :mutation
                           :psi.ui.invocation/mutation 'psi.ui/show-active}))]
    (is (= {:psi.ui.invocation/kind :ui-event
            :psi.ui.invocation/event :psi.ui/show-active
            :psi.ui.invocation/payload {}}
           (get-in ui-event-result
                   [:psi.ui/make-visible-action :psi.ui.action/invocation])))
    (is (= {:psi.ui.invocation/kind :mutation
            :psi.ui.invocation/mutation 'psi.ui/show-active
            :psi.ui.invocation/params {}}
           (get-in mutation-result
                   [:psi.ui/make-visible-action :psi.ui.action/invocation])))))

(deftest provider-normalization-rejects-non-vector-collections-test
  ;; Tests that provider capabilities/actions collection shape drift fails closed
  ;; instead of being silently vectorized into the EQL contract.
  (let [action (ui-capabilities/make-visible-action
                {:psi.ui.invocation/kind :emacs-command
                 :psi.ui.invocation/command "psi-emacs-show-active"})]
    (doseq [provider-result [{:psi.ui/type :emacs
                              :psi.ui/available? true
                              :psi.ui/capabilities '(:psi.ui.capability/make-visible)
                              :psi.ui/actions [action]}
                             {:psi.ui/type :emacs
                              :psi.ui/available? true
                              :psi.ui/capabilities #{:psi.ui.capability/make-visible}
                              :psi.ui/actions [action]}
                             {:psi.ui/type :emacs
                              :psi.ui/available? true
                              :psi.ui/capabilities [:psi.ui.capability/make-visible]
                              :psi.ui/actions (list action)}
                             {:psi.ui/type :emacs
                              :psi.ui/available? true
                              :psi.ui/capabilities [:psi.ui.capability/make-visible]
                              :psi.ui/actions #{action}}]]
      (let [result (ui-capabilities/normalize-provider-result provider-result)]
        (is (provider-error? result)
            (str "expected provider-error for provider collection shape: "
                 (pr-str provider-result)))
        (is (= [] (:psi.ui/capabilities result)))
        (is (= [] (:psi.ui/actions result)))))))

(deftest provider-normalization-duplicate-action-ids-test
  ;; Tests that duplicate action ids are rejected rather than merged.
  (let [action (ui-capabilities/make-visible-action
                {:psi.ui.invocation/kind :emacs-command
                 :psi.ui.invocation/command "psi-emacs-show-active"})]
    (is (provider-error?
         (ui-capabilities/normalize-provider-result
          {:psi.ui/type :emacs
           :psi.ui/available? true
           :psi.ui/capabilities [:psi.ui.capability/make-visible]
           :psi.ui/actions [action action]})))))

(deftest provider-normalization-foreign-action-keys-test
  ;; Tests that provider action descriptors reject unqualified and foreign keys
  ;; rather than exposing adapter-local data through EQL.
  (let [base-action (ui-capabilities/make-visible-action
                     {:psi.ui.invocation/kind :emacs-command
                      :psi.ui.invocation/command "psi-emacs-show-active"})]
    (doseq [extra-key [:handler :foreign.ui/handler "frontend-object"]]
      (is (provider-error?
           (ui-capabilities/normalize-provider-result
            {:psi.ui/type :emacs
             :psi.ui/available? true
             :psi.ui/capabilities [:psi.ui.capability/make-visible]
             :psi.ui/actions [(assoc base-action extra-key "leaked-adapter-data")]}))
          (str "expected provider-error for extra action key: " (pr-str extra-key))))))

(deftest provider-normalization-foreign-invocation-keys-test
  ;; Tests that nested invocation maps reject unqualified and foreign keys rather
  ;; than exposing adapter-local data through EQL.
  (doseq [extra-key [:handler :foreign.ui/handler "frontend-object"]]
    (is (provider-error?
         (ui-capabilities/normalize-provider-result
          (valid-provider-result
           (assoc {:psi.ui.invocation/kind :emacs-command
                   :psi.ui.invocation/command "psi-emacs-show-active"}
                  extra-key
                  "leaked-adapter-data"))))
        (str "expected provider-error for extra invocation key: " (pr-str extra-key)))))

(deftest provider-normalization-same-namespace-invocation-keys-test
  ;; Tests that per-kind invocation schemas are closed even for same-namespace
  ;; extras, so provider-local data cannot appear under plausible contract keys.
  (doseq [[kind invocation extra-key]
          [[:emacs-command
            {:psi.ui.invocation/kind :emacs-command
             :psi.ui.invocation/command "psi-emacs-show-active"}
            :psi.ui.invocation/payload]
           [:ui-event
            {:psi.ui.invocation/kind :ui-event
             :psi.ui.invocation/event :psi.ui/show-active}
            :psi.ui.invocation/command]
           [:bash-command
            {:psi.ui.invocation/kind :bash-command
             :psi.ui.invocation/argv ["tmux" "switch-client" "-t" "psi"]}
            :psi.ui.invocation/mutation]
           [:mutation
            {:psi.ui.invocation/kind :mutation
             :psi.ui.invocation/mutation 'psi.ui/show-active}
            :psi.ui.invocation/env]]]
    (is (provider-error?
         (ui-capabilities/normalize-provider-result
          (valid-provider-result
           (assoc invocation extra-key "adapter-local-data"))))
        (str "expected provider-error for extra " kind " invocation key: "
             (pr-str extra-key)))))

(deftest provider-normalization-capability-action-coherence-test
  ;; Tests that make-visible capability/action mismatches fail closed.
  (is (provider-error?
       (ui-capabilities/normalize-provider-result
        {:psi.ui/type :emacs
         :psi.ui/available? true
         :psi.ui/capabilities [:psi.ui.capability/make-visible]
         :psi.ui/actions []})))
  (is (provider-error?
       (ui-capabilities/normalize-provider-result
        {:psi.ui/type :emacs
         :psi.ui/available? true
         :psi.ui/capabilities []
         :psi.ui/actions [(ui-capabilities/make-visible-action
                           {:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"})]}))))

(deftest provider-normalization-unavailable-with-advertisements-test
  ;; Tests that unavailable provider results cannot expose contradictory
  ;; capabilities or available action descriptors.
  (let [action (ui-capabilities/make-visible-action
                {:psi.ui.invocation/kind :emacs-command
                 :psi.ui.invocation/command "psi-emacs-show-active"})]
    (doseq [provider-result [{:psi.ui/type :emacs
                              :psi.ui/available? false
                              :psi.ui/capabilities [:psi.ui.capability/make-visible]
                              :psi.ui/actions []}
                             {:psi.ui/type :emacs
                              :psi.ui/available? false
                              :psi.ui/capabilities [:psi.ui.capability/make-visible]
                              :psi.ui/actions [action]}]]
      (let [result (ui-capabilities/normalize-provider-result provider-result)]
        (is (= false (:psi.ui/available? result)))
        (is (= [] (:psi.ui/capabilities result)))
        (is (= [] (:psi.ui/actions result)))
        (is (provider-error? result))))))

(deftest provider-exception-query-behaviour-test
  ;; Tests that provider exceptions do not drop requested UI attrs.
  (let [ctx (create-ctx {})]
    (ui-capabilities/install-provider! ctx (fn [_] (throw (ex-info "boom" {}))))
    (let [result (session/query-in ctx ui-query)]
      (is (contains? result :psi.ui/type))
      (is (contains? result :psi.ui/available?))
      (is (contains? result :psi.ui/capabilities))
      (is (contains? result :psi.ui/actions))
      (is (contains? result :psi.ui/make-visible-action))
      (is (= :psi.ui.unavailable.reason/provider-error
             (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))))))

(deftest ui-capability-query-coexists-with-extension-ui-and-legacy-ui-type-test
  ;; Tests that the new queryable UI attrs compose with existing extension UI
  ;; snapshot attrs and legacy UI-type diagnostics.
  (let [ctx (create-ctx {:ui-type :console})
        session-id (:session-id (session/new-session-in! ctx nil {}))]
    (session/dispatch-in! ctx
                          :session/ui-set-widget
                          {:extension-id "ext-a"
                           :widget-id "w1"
                           :placement :below-editor
                           :content ["hello"]}
                          {:origin :test})
    (session/dispatch-in! ctx
                          :session/ui-set-status
                          {:extension-id "ext-a"
                           :text "ready"}
                          {:origin :test})
    (let [result (session/query-in ctx
                                   session-id
                                   [:psi.agent-session/ui-type
                                    :psi.ui/type
                                    :psi.ui/available?
                                    :psi.ui/capabilities
                                    :psi.ui/actions
                                    :psi.ui/make-visible-action
                                    :psi.ui/widgets
                                    :psi.ui/statuses
                                    :psi.ui/dialog-queue-empty?])]
      (is (= :console (:psi.agent-session/ui-type result)))
      (is (= :console (:psi.ui/type result)))
      (is (= true (:psi.ui/available? result)))
      (is (= [] (:psi.ui/capabilities result)))
      (is (= [] (:psi.ui/actions result)))
      (is (= :psi.ui.unavailable.reason/unsupported-capability
             (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
      (is (= [{:extension-id "ext-a"
               :widget-id "w1"
               :placement :below-editor
               :content ["hello"]
               :content-lines [{:text "hello"}]}]
             (:psi.ui/widgets result)))
      (is (= [{:extension-id "ext-a"
               :text "ready"}]
             (:psi.ui/statuses result)))
      (is (true? (:psi.ui/dialog-queue-empty? result))))))
