(ns psi.rpc.session.streams
  "Shared progress/event stream lifecycle helpers for RPC session workflows."
  (:require
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]))

(defn- footer-refresh-progress-event?
  [evt]
  (contains? #{:tool-result :retry-updated} (:event-kind evt)))

(defn- emit-progress-event!
  [emit! ctx session-id evt]
  (when-let [{:keys [event data]} (events/progress-event->rpc-event evt)]
    (emit! event data))
  (when (footer-refresh-progress-event? evt)
    (emit/emit-footer-updated! emit! ctx session-id)))

(defn start-progress-loop!
  [{:keys [start-daemon-thread! ctx session-id emit! progress-q thread-name]
    :or   {thread-name "rpc-progress-loop"}}]
  (let [stop? (atom false)
        tag-session (fn [evt]
                      (if (contains? evt :session-id)
                        evt
                        (assoc evt :session-id session-id)))
        thread (start-daemon-thread!
                (fn []
                  (loop []
                    (when-not @stop?
                      (when-let [evt (.poll progress-q 10 java.util.concurrent.TimeUnit/MILLISECONDS)]
                        (emit-progress-event! emit! ctx session-id (tag-session evt))
                        (loop []
                          (when-let [more (.poll progress-q)]
                            (emit-progress-event! emit! ctx session-id (tag-session more))
                            (recur))))
                      (recur))))
                thread-name)]
    {:stop? stop?
     :thread thread}))

(defn stop-progress-loop!
  [{:keys [stop? thread progress-q emit! ctx session-id]}]
  (reset! stop? true)
  (.join ^Thread thread 200)
  (loop []
    (when-let [evt (.poll progress-q)]
      (emit-progress-event! emit! ctx session-id (if (contains? evt :session-id)
                                                   evt
                                                   (assoc evt :session-id session-id)))
      (recur))))
