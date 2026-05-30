(ns psi.ai.streaming
  "Streaming response management and event handling.

   Streams are callback-based: callers supply a `consume-fn` that is
   invoked for every event on a background thread. No core.async
   channels are used.

   Alternatively, `stream-response-seq` returns a lazy sequence of events
   via a `java.util.concurrent.LinkedBlockingQueue`; the background thread
   drains into the queue while the caller drains it from the seq side."
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent LinkedBlockingQueue]))

;; ───────────────────────────────────────────────────────────────────────────
;; Stream session helpers
;; ───────────────────────────────────────────────────────────────────────────

(defn create-stream-session
  "Create new streaming session."
  [conversation model options]
  {:id              (str (UUID/randomUUID))
   :conversation-id (:id conversation)
   :model           model
   :status          :streaming
   :started-at      (Instant/now)
   :completed-at    nil
   :temperature     (:temperature options)
   :max-tokens      (:max-tokens options)
   :cache-retention (get options :cache-retention :short)})

(defn- finish-session
  [session status & [error-message]]
  (cond-> (assoc session
                 :status status
                 :completed-at (Instant/now))
    error-message (assoc :error-message error-message)))

(defn complete-session
  "Mark session as completed."
  [session]
  (finish-session session :completed))

(defn fail-session
  "Mark session as failed."
  [session error-msg]
  (finish-session session :failed error-msg))

(defn- handle-event!
  [session consume-fn event]
  (case (:type event)
    :done (swap! session complete-session)
    :error (swap! session fail-session (:error-message event))
    nil)
  (consume-fn event))

(defn- exception->error-event
  [e]
  (let [data       (ex-data e)
        status     (:status data)
        http-status (or (:http-status data) status)
        headers    (or (:provider-error/headers data)
                       (:headers data))]
    (cond-> {:type          :error
             :error-message (str e)}
      status (assoc :status status)
      http-status (assoc :http-status http-status)
      (:headers data) (assoc :headers (:headers data))
      headers (assoc :provider-error/headers headers))))

;; ───────────────────────────────────────────────────────────────────────────
;; Callback-based streaming (preferred)
;; ───────────────────────────────────────────────────────────────────────────

(defn stream-response
  "Stream assistant response, calling `consume-fn` for every event.

   Runs the provider on a background thread (via `future`). Returns a
   map of:
     :future  — the java.util.concurrent.Future for the background work
     :session — atom holding the evolving StreamSession

   The `consume-fn` receives event maps matching the provider's schema.
   When streaming is done (`:done` or `:error` event), or when the
   provider returns, the future completes.

   Example:
     (stream-response provider-impl conversation model options
       (fn [event]
         (case (:type event)
           :text-delta (print (:delta event))
           :done       (println \"[done]\")
           nil)))"
  [provider-impl conversation model options consume-fn]
  (let [session (atom (create-stream-session conversation model options))]
    {:future
     (future
       (try
         ((:stream provider-impl) conversation model options
                                  (partial handle-event! session consume-fn))
         (when (= :streaming (:status @session))
           (swap! session complete-session))
         (catch Exception e
           (let [event (exception->error-event e)]
             (swap! session fail-session (:error-message event))
             (consume-fn event)))))
     :session session}))

;; ───────────────────────────────────────────────────────────────────────────
;; Lazy-sequence streaming (convenience wrapper)
;; ───────────────────────────────────────────────────────────────────────────

(def ^:private sentinel ::end-of-stream)

(defn- queue->lazy-seq
  [queue]
  (letfn [(drain []
            (lazy-seq
             (let [item (.take queue)]
               (when-not (= item sentinel)
                 (cons item (drain))))))]
    (drain)))

(defn- stream-response-queue
  [provider-impl conversation model options]
  (let [queue                       (LinkedBlockingQueue. 100)
        {stream-future :future
         session       :session} (stream-response provider-impl conversation model
                                                  options #(.put queue %))]
    (future
      @stream-future
      (.put queue sentinel))
    {:queue queue
     :session session}))

(defn stream-response-seq
  "Stream assistant response as a lazy sequence of event maps.

   A `LinkedBlockingQueue` bridges the background thread (producer) and the
   lazy seq (consumer). The sequence terminates after a `:done` or `:error`
   event, or when the background thread completes without emitting either.

   Returns a map of:
     :events  — lazy sequence of event maps
     :session — atom holding the evolving StreamSession

   Example:
     (let [{:keys [events]} (stream-response-seq provider conversation model opts)]
       (doseq [ev events]
         (when (= :text-delta (:type ev))
           (print (:delta ev)))))"
  [provider-impl conversation model options]
  (let [{:keys [queue session]}
        (stream-response-queue provider-impl conversation model options)]
    {:events  (queue->lazy-seq queue)
     :session session}))
