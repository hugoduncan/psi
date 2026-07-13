(ns psi.rpc.state
  "Explicit helpers for canonical RPC connection-local mutable state.

   State shape:
   {:transport  {:ready? :negotiated-protocol-version :pending :max-pending-requests :err}
    :connection {:focus-session-id :default-session-id :subscribed-topics :event-seq}
    :workers    {:inflight-futures :rpc-run-fn-registered? :external-event-loop :projection-listener-id :projection-listener-stop-fn}}")

(def default-max-pending-requests 64)

(defn make-rpc-state
  [{:keys [session-id err max-pending-requests]
    :or   {max-pending-requests default-max-pending-requests}}]
  (atom {:transport
         {:ready? false
          :negotiated-protocol-version nil
          :pending {}
          :max-pending-requests max-pending-requests
          :err err}
         :connection
         {:focus-session-id session-id
          :default-session-id session-id
          :subscribed-topics #{}
          :event-seq 0}
         :workers
         {:inflight-futures []
          :rpc-run-fn-registered? false
          :external-event-loop nil
          :projection-listener-id nil
          :projection-listener-stop-fn nil}}))

(defn initialize-transport-state!
  [state err]
  (swap! state
         (fn [s]
           (-> s
               (update :transport
                       (fn [transport]
                         (merge {:ready? false
                                 :negotiated-protocol-version nil
                                 :pending {}
                                 :max-pending-requests default-max-pending-requests
                                 :err err}
                                (or transport {})
                                {:err err})))
               (update :connection
                       (fn [connection]
                         (merge {:focus-session-id nil
                                 :default-session-id nil
                                 :subscribed-topics #{}
                                 :event-seq 0}
                                (or connection {}))))
               (update :workers
                       (fn [workers]
                         (merge {:inflight-futures []
                                 :rpc-run-fn-registered? false
                                 :external-event-loop nil
                                 :projection-listener-id nil
                                 :projection-listener-stop-fn nil}
                                (or workers {})))))))
  state)

(defn err-writer [state]
  (get-in @state [:transport :err]))

(defn ready? [state]
  (boolean (get-in @state [:transport :ready?])))

(defn mark-ready!
  [state protocol-version]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:transport :ready?] true)
                     (assoc-in [:transport :negotiated-protocol-version] protocol-version))))
  state)

(defn pending [state]
  (or (get-in @state [:transport :pending])
      {}))

(defn max-pending-requests [state]
  (or (get-in @state [:transport :max-pending-requests])
      default-max-pending-requests))

(defn add-pending!
  [state id op]
  (swap! state assoc-in [:transport :pending id] op)
  state)

(defn clear-pending!
  [state id]
  (swap! state update-in [:transport :pending] (fnil dissoc {}) id)
  state)

(defn focus-session-id
  [state]
  (get-in @state [:connection :focus-session-id]))

(defn set-focus-session-id!
  [state session-id]
  (swap! state assoc-in [:connection :focus-session-id] session-id)
  state)

(defn default-session-id
  [state]
  (get-in @state [:connection :default-session-id]))

(defn subscribed-topics
  [state]
  (or (get-in @state [:connection :subscribed-topics])
      #{}))

(defn subscribe-topics!
  [state topics]
  (swap! state update-in [:connection :subscribed-topics] (fnil into #{}) topics)
  state)

(defn unsubscribe-topics!
  [state topics]
  (swap! state update-in [:connection :subscribed-topics]
         (fn [current]
           (apply disj (or current #{}) topics)))
  state)

(defn clear-subscriptions!
  [state]
  (swap! state assoc-in [:connection :subscribed-topics] #{})
  state)

(defn next-event-seq!
  [state]
  (let [next-seq (inc (or (get-in @state [:connection :event-seq]) 0))]
    (swap! state assoc-in [:connection :event-seq] next-seq)
    next-seq))

(defn rpc-run-fn-registered?
  [state]
  (boolean (get-in @state [:workers :rpc-run-fn-registered?])))

(defn mark-rpc-run-fn-registered!
  [state]
  (swap! state assoc-in [:workers :rpc-run-fn-registered?] true)
  state)

(defn external-event-loop
  [state]
  (get-in @state [:workers :external-event-loop]))

(defn set-external-event-loop!
  [state x]
  (swap! state assoc-in [:workers :external-event-loop] x)
  state)

(defn projection-listener-id
  [state]
  (get-in @state [:workers :projection-listener-id]))

(defn set-projection-listener-id!
  [state listener-id]
  (swap! state assoc-in [:workers :projection-listener-id] listener-id)
  state)

(defn projection-listener-stop-fn
  [state]
  (get-in @state [:workers :projection-listener-stop-fn]))

(defn set-projection-listener-stop-fn!
  [state stop-fn]
  (swap! state assoc-in [:workers :projection-listener-stop-fn] stop-fn)
  state)

(defn add-inflight-future!
  [state x]
  (swap! state update-in [:workers :inflight-futures] (fnil conj []) x)
  state)

(defn worker-handle
  [state worker-k]
  (get-in @state [:workers worker-k]))

(defn clear-worker!
  [state worker-k]
  (swap! state assoc-in [:workers worker-k] nil)
  state)

(defn stop-worker!
  [state worker-k]
  (when-let [x (worker-handle state worker-k)]
    (cond
      (instance? java.util.concurrent.Future x)
      (try (future-cancel x) (catch Throwable _ nil))

      (instance? Thread x)
      (try (.interrupt ^Thread x) (catch Throwable _ nil))

      :else nil)
    (clear-worker! state worker-k)
    true))

(defn stop-managed-workers!
  [state]
  (when-let [stop-fn (projection-listener-stop-fn state)]
    (try (stop-fn) (catch Throwable _ nil))
    (set-projection-listener-stop-fn! state nil)
    (set-projection-listener-id! state nil))
  (doseq [k [:external-event-loop]]
    (stop-worker! state k)))
