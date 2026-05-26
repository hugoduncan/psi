(ns psi.agent-session.tool-authority-handlers-test
  "Tests for tool-ids authority-first behavior in :session/set-active-tools
   and :session/add-tool handlers."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.session-mutations :as mutations]
   [psi.state-kernel.dispatch :as kernel]))

(defn- clean-state [f]
  (kernel/clear-handlers!)
  (kernel/clear-event-log!)
  (kernel/clear-dispatch-trace!)
  (dispatch/set-interceptors! nil)
  (try (f)
       (finally
         (kernel/clear-handlers!)
         (kernel/clear-event-log!)
         (kernel/clear-dispatch-trace!)
         (dispatch/set-interceptors! nil))))

(use-fixtures :each clean-state)

(defn- make-ctx
  "Build a minimal test ctx with session-data atom, agent-ctx with data-atom,
   and effect tracking."
  [session-data-atom]
  (let [seen-effects (atom [])]
    {:ctx {:apply-root-state-update-fn (fn [_ctx f] (swap! session-data-atom f))
           :execute-dispatch-effect-fn (fn [_ctx effect] (swap! seen-effects conj effect))
           :state* session-data-atom}
     :seen-effects seen-effects}))

(defn- session-data-with-agent
  "Build a state atom with session data and a mock agent-ctx holding tool-source."
  [session-id tool-ids tool-source]
  (atom {:agent-session
         {:sessions
          {session-id {:data     {:session-id session-id
                                  :tool-ids   tool-ids}
                       :agent-ctx {:data-atom (atom {:tools tool-source})}}}}}))

(deftest set-active-tools-persists-tool-ids-test
  ;; :session/set-active-tools derives :tool-ids as authority from incoming tool-maps
  (testing "set-active-tools persists :tool-ids derived from normalized tool names"
    (let [sd-atom (session-data-with-agent "s1" [] [])
          {:keys [ctx seen-effects]} (make-ctx sd-atom)]
      (mutations/register! ctx)
      (dispatch/dispatch! ctx :session/set-active-tools
                          {:session-id "s1"
                           :tool-maps [{:name "bash" :description "Run shell commands"}
                                       {:name "read" :description "Read files"}
                                       {:name "edit" :description "Edit files"}]})
      (let [sd (get-in @sd-atom [:agent-session :sessions "s1" :data])]
        (is (= ["bash" "read" "edit"] (:tool-ids sd))
            ":tool-ids is an ordered vector of tool names")
        (is (nil? (:active-tools sd))
            ":active-tools is not stored in session state")
        (is (nil? (:tool-defs sd))
            ":tool-defs is not stored in session state"))
      (let [effect-types (mapv :effect/type @seen-effects)]
        (is (= [:runtime/agent-set-tools :runtime/refresh-system-prompt] effect-types)
            "emits agent-set-tools and refresh-system-prompt effects")
        (is (= ["bash" "read" "edit"]
               (mapv :name (:tool-maps (first @seen-effects))))
            "agent-set-tools carries normalized tool-maps")
        (is (= "s1" (:session-id (second @seen-effects)))
            "refresh-system-prompt carries session-id"))))

  (testing "set-active-tools preserves order from incoming tool-maps"
    (let [sd-atom (session-data-with-agent "s1" [] [])
          {:keys [ctx seen-effects]} (make-ctx sd-atom)]
      (mutations/register! ctx)
      (dispatch/dispatch! ctx :session/set-active-tools
                          {:session-id "s1"
                           :tool-maps [{:name "write" :description "W"}
                                       {:name "bash" :description "B"}
                                       {:name "psi-tool" :description "P"}]})
      (let [sd (get-in @sd-atom [:agent-session :sessions "s1" :data])]
        (is (= ["write" "bash" "psi-tool"] (:tool-ids sd))))
      (is (= [:runtime/agent-set-tools :runtime/refresh-system-prompt]
             (mapv :effect/type @seen-effects))
          "order-preservation path also emits correct effects")))

  (testing "set-active-tools with empty tool-maps clears all tools"
    (let [sd-atom (session-data-with-agent "s1" ["bash" "read"]
                                           [{:name "bash"} {:name "read"}])
          {:keys [ctx]} (make-ctx sd-atom)]
      (mutations/register! ctx)
      (dispatch/dispatch! ctx :session/set-active-tools
                          {:session-id "s1"
                           :tool-maps []})
      (let [sd (get-in @sd-atom [:agent-session :sessions "s1" :data])]
        (is (= [] (:tool-ids sd))
            "empty tool-maps produces empty :tool-ids")))))

(deftest add-tool-persists-tool-ids-test
  ;; :session/add-tool must derive/persist :tool-ids when adding a new tool
  (testing "add-tool appends new tool name to :tool-ids"
    (let [tool-source [{:name "bash" :description "B"}
                       {:name "read" :description "R"}]
          sd-atom     (session-data-with-agent "s1" ["bash" "read"] tool-source)
          {:keys [ctx seen-effects]} (make-ctx sd-atom)]
      (mutations/register! ctx)
      (dispatch/dispatch! ctx :session/add-tool
                          {:session-id "s1"
                           :tool {:name "write" :description "Write files"}})
      (let [sd (get-in @sd-atom [:agent-session :sessions "s1" :data])]
        (is (= ["bash" "read" "write"] (:tool-ids sd))
            ":tool-ids includes the new tool"))
      (let [effect-types (mapv :effect/type @seen-effects)]
        (is (= [:runtime/agent-set-tools] effect-types)
            "add-tool emits agent-set-tools effect")
        (is (= ["bash" "read" "write"]
               (mapv :name (:tool-maps (first @seen-effects))))
            "agent-set-tools carries updated tool-maps"))))

  (testing "add-tool does not modify state when tool already exists"
    (let [tool-source [{:name "bash" :description "B"}]
          sd-atom     (session-data-with-agent "s1" ["bash"] tool-source)
          {:keys [ctx seen-effects]} (make-ctx sd-atom)]
      (mutations/register! ctx)
      (let [result (dispatch/dispatch! ctx :session/add-tool
                                       {:session-id "s1"
                                        :tool {:name "bash" :description "B"}})]
        (is (= {:added? false :count 1} result))
        (is (= [] @seen-effects)
            "no effects dispatched for duplicate tool")))))
