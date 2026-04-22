(ns psi.rpc.session.prompt
  "Prompt execution workflow for RPC session handling."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as events]
   [psi.rpc.session.commands :as rpc.commands]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.streams :as streams]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [response-frame]]))

(defn run-prompt-async!
  [{:keys [ctx request emit-frame! state session-id session-deps current-ai-model _effective-sync-on-git-head-change? start-daemon-thread! login-handle-start-command! login-pending-state login-complete-pending!]}]
  (let [message      (get-in request [:params :message])
        images       (get-in request [:params :images])
        request-id   (:id request)
        _            (when-not session-id
                       (throw (ex-info "no target session available for prompt"
                                       {:error-code "request/not-found"})))
        on-new-session! (:on-new-session! session-deps)
        progress-q   (java.util.concurrent.LinkedBlockingQueue.)
        worker       (start-daemon-thread!
                      (fn []
                        (binding [*out* (rpc.state/err-writer state)
                                  *err* (rpc.state/err-writer state)]
                          (let [emit! (emit/make-request-emitter emit-frame! state request-id)
                                {:keys [stop? thread]}
                                (streams/start-progress-loop!
                                 {:start-daemon-thread! start-daemon-thread!
                                  :ctx ctx
                                  :state state
                                  :session-id session-id
                                  :emit! emit!
                                  :progress-q progress-q
                                  :thread-name "rpc-progress-loop"})]
                            (try
                              (let [ai-model      (current-ai-model ctx session-deps session-id)
                                    _             (when-not ai-model
                                                    (throw (ex-info "session model is not configured"
                                                                    {:error-code "request/invalid-params"})))
                                    oauth-ctx     (:oauth-ctx ctx)
                                    pending-login (login-pending-state ctx)
                                    cmd-result    (when-not pending-login
                                                    (commands/dispatch-in ctx session-id message {:oauth-ctx oauth-ctx
                                                                                                  :ai-model  ai-model
                                                                                                  :supports-session-tree? false
                                                                                                  :on-new-session! on-new-session!}))]
                                (cond
                                  pending-login
                                  (do
                                    (runtime/journal-user-message-in! ctx session-id message images)
                                    (login-complete-pending! {:ctx ctx :state state :message message :emit! emit!})
                                    (emit/emit-session-snapshots! emit! ctx state session-id))

                                  (some? cmd-result)
                                  (do
                                    (runtime/journal-user-message-in! ctx session-id message images)
                                    (if (= :login-start (:type cmd-result))
                                      (login-handle-start-command! {:ctx ctx :state state :session-id session-id :emit-frame! emit-frame! :request-id request-id :cmd-result cmd-result :emit! emit! :start-daemon-thread! start-daemon-thread!})
                                      (do
                                        (when (= :new-session (:type cmd-result))
                                          (let [rehydrate (:rehydrate cmd-result)
                                                new-sid   (:session-id rehydrate)
                                                _         (when new-sid (events/set-focus-session-id! state new-sid))
                                                sd        (when new-sid (ss/get-session-data-in ctx new-sid))
                                                msgs      (or (:agent-messages rehydrate)
                                                              (when new-sid
                                                                (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid)))))]
                                            (emit/emit-session-rehydration!
                                             emit!
                                             {:session-id (:session-id sd)
                                              :session-file (:session-file sd)
                                              :message-count (count msgs)
                                              :messages msgs
                                              :tool-calls (or (:tool-calls rehydrate) {})
                                              :tool-order (or (:tool-order rehydrate) [])})))
                                        (rpc.commands/handle-prompt-command-result! cmd-result emit!)))
                                    (emit/emit-session-snapshots! emit! ctx state session-id))

                                  :else
                                  (let [session-model {:provider  (some-> (:provider ai-model) name)
                                                       :id        (:id ai-model)
                                                       :reasoning (boolean (:supports-reasoning ai-model))}
                                        _           (emit/emit-session-snapshots! emit! ctx state session-id)
                                        ;; Ensure session has the resolved model before prompt lifecycle
                                        _           (dispatch/dispatch! ctx :session/set-model
                                                                        {:session-id session-id
                                                                         :model session-model
                                                                         :scope :session}
                                                                        {:origin :core})
                                        api-key     (runtime/resolve-api-key-in ctx session-id ai-model)
                                        _           (session/prompt-in! ctx session-id message images
                                                                        {:progress-queue progress-q
                                                                         :runtime-opts (cond-> {}
                                                                                         api-key (assoc :api-key api-key))})
                                        assistant   (session/last-assistant-message-in ctx session-id)]
                                    (streams/stop-progress-loop! {:stop? stop?
                                                                  :thread thread
                                                                  :progress-q progress-q
                                                                  :emit! emit!
                                                                  :session-id session-id})
                                    (when assistant
                                      (emit/emit-assistant-message! emit! session-id assistant))
                                    (emit/emit-session-snapshots! emit! ctx state session-id))))
                              (catch Throwable t
                                (emit! "error" {:error-code "runtime/failed"
                                                :error-message (or (ex-message t) "prompt execution failed")
                                                :id request-id
                                                :op "prompt"})
                                (emit/emit-session-snapshots! emit! ctx state session-id))
                              (finally
                                (reset! stop? true)
                                (.join ^Thread thread 200)))))))]
    (rpc.state/add-inflight-future! state worker)
    (response-frame (:id request) "prompt" true {:accepted true})))
