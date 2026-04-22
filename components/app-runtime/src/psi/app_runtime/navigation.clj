(ns psi.app-runtime.navigation
  "Canonical adapter-neutral navigation result shaping."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.context :as app-context]
   [psi.app-runtime.messages :as app-messages]))

(defn rehydration-package
  [ctx sid & [{:keys [tool-calls tool-order]}]]
  (let [sd   (ss/get-session-data-in ctx sid)
        msgs (app-messages/session-messages ctx sid)]
    {:session-id    (:session-id sd)
     :session-file  (:session-file sd)
     :message-count (count msgs)
     :messages      msgs
     :tool-calls    (or tool-calls {})
     :tool-order    (or tool-order [])}))

(defn navigation-result
  [ctx _state nav-op sid & [{:keys [tool-calls tool-order follow-up-ui-actions active-session-id]}]]
  {:nav/op                nav-op
   :nav/session-id        sid
   :nav/session-file      (:session-file (ss/get-session-data-in ctx sid))
   :nav/rehydration       (rehydration-package ctx sid {:tool-calls tool-calls
                                                        :tool-order tool-order})
   :nav/context-snapshot  (app-context/context-snapshot ctx (or active-session-id sid) sid)
   :nav/follow-up-ui-actions (vec (or follow-up-ui-actions []))})

(defn switch-session-result
  [ctx state source-session-id sid]
  (session/ensure-session-loaded-in! ctx source-session-id sid)
  (navigation-result ctx state :switch-session sid {:active-session-id sid}))

(defn resume-session-result
  [ctx state source-session-id session-path]
  (when-not (and (string? session-path) (not (str/blank? session-path)))
    (throw (ex-info "invalid request parameter :session-path: non-empty path string"
                    {:error-code "request/invalid-params"})))
  (when-not (.exists (io/file session-path))
    (throw (ex-info "session file not found"
                    {:error-code "request/not-found"})))
  (let [sd (session/resume-session-in! ctx source-session-id session-path)]
    (navigation-result ctx state :resume-session (:session-id sd) {:active-session-id (:session-id sd)})))

(defn fork-session-result
  [ctx state source-session-id entry-id]
  (when-not (and (string? entry-id) (not (str/blank? entry-id)))
    (throw (ex-info "invalid request parameter :entry-id: non-empty entry id"
                    {:error-code "request/invalid-params"})))
  (let [sd (session/fork-session-in! ctx source-session-id entry-id)]
    (navigation-result ctx state :fork-session (:session-id sd) {:active-session-id (:session-id sd)})))

(defn new-session-result
  [ctx state source-session-id {:keys [on-new-session!]}]
  (if on-new-session!
    (let [rehydrate     (on-new-session! source-session-id)
          sid           (or (:session-id rehydrate)
                            (:session-id (ss/get-session-data-in ctx source-session-id)))
          session-data  (ss/get-session-data-in ctx sid)
          session-file  (:session-file session-data)
          messages      (or (:agent-messages rehydrate)
                            (app-messages/session-messages ctx sid))]
      {:nav/op                   :new-session
       :nav/session-id           sid
       :nav/session-file         session-file
       :nav/rehydration          {:session-id    sid
                                  :session-file  session-file
                                  :message-count (count messages)
                                  :messages      messages
                                  :tool-calls    (or (:tool-calls rehydrate) {})
                                  :tool-order    (or (:tool-order rehydrate) [])}
       :nav/context-snapshot     (app-context/context-snapshot ctx sid sid)
       :nav/follow-up-ui-actions []})
    (let [sd (session/new-session-in! ctx source-session-id {})]
      (navigation-result ctx state :new-session (:session-id sd) {:active-session-id (:session-id sd)}))))
