(ns psi.rpc.session.command-pickers-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.test-support :as agent-test-support]
   [psi.ai.model-registry :as model-registry]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.session.command-pickers :as command-pickers]
   [psi.rpc-test-support :as support]
   [psi.session-state.state :as ss]))

(defn- capture-model-picker-items
  "Invoke the /model picker command with a capturing emit! and return the
  picker item id pairs [provider id] offered at the picker boundary."
  []
  (let [captured (atom nil)
        emit!    (fn [_event-type payload] (reset! captured payload))]
    (command-pickers/handle-picker-command! "req-1" emit! "/model")
    (->> (get-in @captured [:ui/action :ui/items])
         (mapv :ui.item/id)
         (into #{}))))

(deftest model-picker-offers-gpt-5-6-codex-variants-test
  ;; Surface-level acceptance coverage: the /model picker (one of the named
  ;; model-selection surfaces) must actually offer each OAuth/Codex GPT-5.6
  ;; variant. The picker derives unconditionally from the shared catalog join
  ;; point, so this also guards against a future picker filter dropping them.
  (let [offered (capture-model-picker-items)]
    (doseq [id ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
      (is (contains? offered ["openai" id])
          (str "/model picker should offer " id)))))

(deftest model-selection-accepts-gpt-5-6-codex-variants-under-oauth-test
  ;; Surface-level *accept*-path coverage: driving each OAuth/Codex GPT-5.6
  ;; variant through the picker-backed selection accept boundary
  ;; (`handle-model-selection!` + `resolve-model`) under an OpenAI OAuth context
  ;; must resolve as *supported* (codex) and persist the selection with a
  ;; success `command-result` — not the `unsupported_model` reject path that
  ;; bare `gpt-5.6` takes. Complements the offer-path picker-enumeration test
  ;; and the `resolve-runtime-model` verbatim-codex resolution test.
  (doseq [id ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
    (let [[ctx sid] (support/create-session-context
                     {:oauth-ctx (agent-test-support/oauth-openai-ctx)})
          emitted   (atom [])
          emit!     (fn [event payload]
                      (swap! emitted conj {:event event :payload payload}))]
      (command-pickers/handle-model-selection!
       ctx sid
       (fn [ctx' provider id']
         (rpc.session/resolve-model ctx' provider id'))
       emit!
       {:provider "openai" :id id})
      (is (= [{:event "command-result"
               :payload {:type "text"
                         :message (model-registry/model-set-message
                                   {:provider "openai" :id id})}}]
             @emitted)
          (str id " should emit a model-set success result under OAuth"))
      (is (= {:provider "openai" :id id :reasoning true}
             (:model (ss/get-session-data-in ctx sid)))
          (str id " should be persisted as the selected model")))))
