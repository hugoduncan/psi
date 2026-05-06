(ns psi.agent-session.dispatch-handlers.prompt-lifecycle
  "Registration/adaptation layer for the canonical turn lifecycle.

   Turn lifecycle orchestration lives under `psi.turn` and
   `psi.turn.handlers`; this namespace only registers dispatch handlers."
  (:require
   [psi.state-kernel.dispatch :as kernel]
   [psi.turn.handlers :as turn.handlers]))

(defn- register-core-handler! [event handler]
  (kernel/register-handler! event handler))

(defn register!
  "Register prompt lifecycle handlers. Called once during context creation."
  [_ctx]
  ;; Intentional narrow synchronous boundary — callers require a direct return
  ;; value and the surrounding statechart transitions already own the state
  ;; transition. execute-compaction-fn is injected by core.clj to avoid a
  ;; circular dependency. Keep the boundary explicit via :return; do not
  ;; generalise this pattern.
  (register-core-handler!
   :session/manual-compaction-execute
   (fn [ctx {:keys [session-id custom-instructions]}]
     {:return ((:execute-compaction-fn ctx) ctx session-id custom-instructions)}))

  (register-core-handler!
   :session/prompt-submit
   (fn [_ctx {:keys [user-msg]}]
     {:effects [{:effect/type :persist/journal-append-message-entry
                 :message user-msg}]
      :return {:submitted? true
               :turn-id (str (java.util.UUID/randomUUID))
               :user-msg user-msg}}))

  (register-core-handler!
   :session/submit-synthetic-user-prompt
   (fn [_ctx {:keys [session-id user-msg]}]
     {:effects (turn.handlers/synthetic-user-prompt-effects session-id user-msg)
      :return {:submitted? true
               :user-msg user-msg}}))

  (register-core-handler!
   :session/append-journal-entry
   (fn [_ctx {:keys [entry]}]
     {:effects [{:effect/type :persist/journal-append-entry
                 :entry entry}]
      :return entry}))

  (register-core-handler! :session/prompt-prepare-request turn.handlers/prompt-prepare-request-handler)
  (register-core-handler! :session/prompt-record-response turn.handlers/prompt-record-response-handler)
  (register-core-handler! :session/prompt-continue turn.handlers/prompt-continue-handler)
  (register-core-handler! :session/prompt-finish turn.handlers/prompt-finish-handler)
  (register-core-handler! :session/prompt-execute turn.handlers/prompt-execute-handler))
