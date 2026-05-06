(ns psi.rpc.session.command-resume
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.session-state.state :as ss]
   [psi.app-runtime.messages :as app-messages]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.events :as events]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.session.emit :as emit]))

(defn emit-session-rehydration-from-sid!
  [ctx state emit! sid]
  (let [sd   (ss/get-session-data-in ctx sid)
        msgs (app-messages/session-messages ctx sid)]
    (events/set-focus-session-id! state sid)
    (emit/emit-session-rehydration!
     emit!
     {:session-id   (:session-id sd)
      :session-file (:session-file sd)
      :message-count (count msgs)
      :messages msgs
      :tool-calls {}
      :tool-order []})
    (emit/emit-context-updated! emit! ctx state sid)))

(defn maybe-match-selector-session
  [selector-items arg]
  (let [session-items (filterv #(= :session (:item/kind %)) selector-items)]
    (or (some #(when (= arg (:item/session-id %)) %) session-items)
        (let [matches (filterv #(str/starts-with? (or (:item/session-id %) "") arg) session-items)]
          (when (= 1 (count matches))
            (first matches))))))

(defn handle-resume-command!
  [ctx state request-id emit! trimmed session-id]
  (if (= trimmed "/resume")
    (let [query-result (session/query-in ctx
                                         [{:psi.session/list
                                           [:psi.session-info/path
                                            :psi.session-info/name
                                            :psi.session-info/worktree-path
                                            :psi.session-info/first-message
                                            :psi.session-info/modified]}])
          action       (ui-actions/resume-session-action query-result)]
      (emit/emit-frontend-action-request! emit! request-id action))
    (let [session-path (-> (str/replace trimmed #"^/resume\s+" "") str/trim)]
      (if-not (.exists (io/file session-path))
        (command-results/emit-text-command-result! emit! (str "Session file not found: " session-path))
        (let [sd  (session/resume-session-in! ctx session-id session-path)
              sid (:session-id sd)]
          (emit-session-rehydration-from-sid! ctx state emit! sid))))))
