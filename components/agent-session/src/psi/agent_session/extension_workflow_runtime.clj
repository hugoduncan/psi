(ns psi.agent-session.extension-workflow-runtime
  "Extension workflow runtime.

   Provides a shared, statechart-backed workflow substrate for extensions.
   Workflows are scoped by extension path and workflow id.

   This namespace is the explicit extension-workflow owner. The historical
   `psi.agent-session.workflows` namespace remains only as a compatibility shim
   during the rename transition."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]))

(def ^:private max-event-history 200)

(defrecord WorkflowRegistry [env state])

(declare send-event-in! workflow-in)

(defn create-registry
  "Create an isolated workflow registry + runtime env."
  []
  (->WorkflowRegistry
   (simple/simple-env)
   (atom {:types         {}
          :instances     {}
          :session-index {}
          :order         []
          :pump-thread   nil
          :pump-stop     nil})))

(defn- type-key [ext-path type]
  [ext-path type])

(defn- workflow-key [ext-path id]
  [ext-path id])

(defn- chart-key [ext-path type]
  [:psi.extension.workflow/chart ext-path type])

(defn- now [] (java.time.Instant/now))

(defn- blankish? [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn- normalize-id [id]
  (cond
    (blankish? id) (str (java.util.UUID/randomUUID))
    (keyword? id)  (name id)
    :else          (str id)))

(defn- safe-call
  [f arg]
  (if (fn? f)
    (f arg)
    nil))

(defn- get-working-memory [reg session-id]
  (sp/get-working-memory (::sc/working-memory-store (:env reg))
                         (:env reg)
                         session-id))

(defn- save-working-memory! [reg session-id wm]
  (sp/save-working-memory! (::sc/working-memory-store (:env reg))
                           (:env reg)
                           session-id
                           wm))

(defn- delete-working-memory! [reg session-id]
  (sp/delete-working-memory! (::sc/working-memory-store (:env reg))
                             (:env reg)
                             session-id))

(defn- wm-data [wm]
  (or (::wmdm/data-model wm)
      (::sc/data-model wm)
      {}))

(defn- running? [wm]
  (boolean (::sc/running? wm)))

(defn- phase [wm]
  (first (::sc/configuration wm)))

(defn- error-message-from-data [data]
  (or (:workflow/error-message data)
      (:error-message data)))

(defn- result-from-data [data]
  (if (contains? data :workflow/result)
    (:workflow/result data)
    (:result data)))

(defn- trim-event-history [events]
  (if (> (count events) max-event-history)
    (vec (take-last max-event-history events))
    (vec events)))

(defn- apply-wm-snapshot
  [inst wm]
  (let [engine-running? (running? wm)
        data            (wm-data wm)
        raw-phase       (phase wm)
        error-msg       (error-message-from-data data)
        error?          (or (= :error raw-phase) (some? error-msg))
        done?           (or (= :done raw-phase)
                            (and (not engine-running?) (not error?)))
        running?        (or (= :running raw-phase)
                            (and engine-running?
                                 (not (contains? #{:idle :done :error :aborted} raw-phase))))
        phase'          (or raw-phase
                            (when error? :error)
                            (when done? :done)
                            (:phase inst))
        finished-at     (when (and (or done? error?)
                                   (nil? (:finished-at inst)))
                          (now))]
    (cond-> (assoc inst
                   :phase phase'
                   :configuration (::sc/configuration wm)
                   :running? running?
                   :done? done?
                   :error? error?
                   :error-message error-msg
                   :result (result-from-data data)
                   :data data
                   :updated-at (now))
      finished-at (assoc :finished-at finished-at))))

(defn- apply-event-meta
  [inst event-name event-data]
  (if event-name
    (let [ev {:name event-name
              :data (or event-data {})
              :timestamp (now)}
          events (trim-event-history (conj (or (:events inst) []) ev))
          started-at (when (and (nil? (:started-at inst))
                                (= event-name (:start-event inst)))
                       (now))]
      (cond-> (assoc inst
                     :event-count (inc (or (:event-count inst) 0))
                     :last-event ev
                     :events events)
        started-at (assoc :started-at started-at)))
    inst))

(defn- update-instance-from-wm
  [inst wm event-name event-data]
  (-> inst
      (apply-wm-snapshot wm)
      (apply-event-meta event-name event-data)))

(defn- public-data
  [type-def data]
  (if-let [f (:public-data-fn type-def)]
    (try
      (f data)
      (catch Exception _ data))
    data))

(defn- public-instance
  [state inst]
  (let [tkey     (type-key (:ext-path inst) (:type inst))
        type-def (get-in state [:types tkey])]
    (-> inst
        (assoc :data (public-data type-def (:data inst)))
        (dissoc :session-id :start-event))))

(defn- process-event-for-session!
  [reg session-id event]
  (locking reg
    (let [{:keys [env state]} reg
          s          @state
          wk         (get-in s [:session-index session-id])
          inst       (get-in s [:instances wk])
          wm         (and inst (get-working-memory reg session-id))]
      (when (and wk inst wm)
        (let [wm'      (sp/process-event! (::sc/processor env) env wm event)
              _        (save-working-memory! reg session-id wm')
              updated  (update-instance-from-wm inst wm' (:name event) (:data event))]
          (swap! state assoc-in [:instances wk] updated)
          true)))))

(defn- process-queued-event!
  [reg event]
  (when-let [session-id (:target event)]
    (process-event-for-session! reg session-id event)))

(defn- pump-active?
  [t]
  (and t (.isAlive ^Thread t)))

(defn- daemon-thread
  "Start a daemon thread running f. Returns the Thread."
  [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))

(defn- start-pump!
  [reg]
  (let [stop? (atom false)
        env   (:env reg)
        q     (::sc/event-queue env)
        t     (daemon-thread
               (fn []
                 (while (not @stop?)
                   (try
                     (sp/receive-events! q env (fn [_ event] (process-queued-event! reg event)))
                     (catch Exception _ nil))
                   (Thread/sleep 25))))]
    (swap! (:state reg) assoc :pump-stop stop? :pump-thread t)
    true))

(defn ensure-pump!
  "Ensure the background event pump is running.
   Needed for async invoke completion events (e.g. :future invocations)."
  [reg]
  (locking reg
    (let [{:keys [pump-thread]} @(:state reg)]
      (when-not (pump-active? pump-thread)
        (start-pump! reg)))))

(defn shutdown-in!
  "Stop the background event pump."
  [reg]
  (locking reg
    (let [{:keys [pump-stop pump-thread]} @(:state reg)]
      (when pump-stop
        (reset! pump-stop true))
      (when pump-thread
        (.interrupt ^Thread pump-thread))
      (swap! (:state reg) assoc :pump-stop nil :pump-thread nil)))
  true)

(defn register-type-in!
  "Register or replace a workflow type for `ext-path`.

   opts keys:
   :type             keyword (required)
   :chart            statechart (required)
   :description      string?
   :start-event      keyword? (default :workflow/start)
   :initial-data-fn  (fn [input] map?) optional
   :public-data-fn   (fn [runtime-data] any) optional"
  [reg ext-path {:keys [type chart description start-event initial-data-fn public-data-fn]}]
  (cond
    (blankish? ext-path)
    {:registered? false
     :error       "ext-path is required."}

    (not (keyword? type))
    {:registered? false
     :error       "type must be a keyword."}

    (nil? chart)
    {:registered? false
     :error       "chart is required."}

    :else
    (locking reg
      (let [ckey (chart-key ext-path type)
            tkey (type-key ext-path type)]
        (try
          (simple/register! (:env reg) ckey chart)
          (swap! (:state reg)
                 (fn [s]
                   (assoc-in s [:types tkey]
                             {:ext-path        ext-path
                              :type            type
                              :description     description
                              :chart           chart
                              :chart-key       ckey
                              :start-event     (or start-event :workflow/start)
                              :initial-data-fn initial-data-fn
                              :public-data-fn  public-data-fn})))
          {:registered? true
           :type        type
           :type-names  (->> (:types @(:state reg))
                             vals
                             (map :type)
                             distinct
                             sort
                             vec)}
          (catch Exception e
            {:registered? false
             :error       (ex-message e)}))))))

(defn type-names-in
  "Return sorted distinct workflow type names.
   When ext-path is supplied, returns only types for that extension."
  ([reg]
   (type-names-in reg nil))
  ([reg ext-path]
   (let [types (vals (:types @(:state reg)))]
     (->> types
          (filter (fn [t]
                    (or (nil? ext-path)
                        (= ext-path (:ext-path t)))))
          (map :type)
          distinct
          sort
          vec))))

(defn- create-instance!
  [reg ext-path type-def id input meta]
  (let [session-id    (str (java.util.UUID/randomUUID))
        initial-extra (try
                        (or (safe-call (:initial-data-fn type-def) input) {})
                        (catch Exception e
                          (throw (ex-info "initial-data-fn failed"
                                          {:error (ex-message e)} e))))
        initial-data  (merge {:workflow/id       id
                              :workflow/ext-path ext-path
                              :workflow/type     (:type type-def)
                              :workflow/input    (or input {})
                              :workflow/meta     (or meta {})}
                             initial-extra)
        wm0           (sp/start! (::sc/processor (:env reg))
                                 (:env reg)
                                 (:chart-key type-def)
                                 {::sc/session-id session-id})
        wm            (assoc wm0 ::wmdm/data-model initial-data)
        _             (save-working-memory! reg session-id wm)
        base          {:id            id
                       :ext-path      ext-path
                       :type          (:type type-def)
                       :session-id    session-id
                       :input         (or input {})
                       :meta          (or meta {})
                       :phase         nil
                       :configuration #{}
                       :running?      true
                       :done?         false
                       :error?        false
                       :error-message nil
                       :result        nil
                       :data          {}
                       :created-at    (now)
                       :started-at    nil
                       :updated-at    (now)
                       :finished-at   nil
                       :event-count   0
                       :last-event    nil
                       :events        []
                       :start-event   (or (:start-event type-def) :workflow/start)}]
    (update-instance-from-wm base wm nil nil)))

(defn create-workflow-in!
  "Create a workflow instance for extension `ext-path`.

   opts keys:
   :type         keyword (required)
   :id           string? (default random UUID)
   :input        map?
   :meta         map?
   :auto-start?  boolean? (default true)
   :start-event  keyword? (override type start-event)

   Returns {:created? bool :workflow map? :error string?}."
  [reg ext-path {:keys [type id input meta auto-start? start-event]}]
  (cond
    (blankish? ext-path)
    {:created? false :error "ext-path is required."}

    (not (keyword? type))
    {:created? false :error "type must be a keyword."}

    :else
    (let [id' (normalize-id id)
          tkey (type-key ext-path type)]
      (ensure-pump! reg)
      (locking reg
        (let [s       @(:state reg)
              type-def (get-in s [:types tkey])
              wk      (workflow-key ext-path id')]
          (cond
            (nil? type-def)
            {:created? false
             :error    (str "No workflow type registered: " type)}

            (contains? (:instances s) wk)
            {:created? false
             :error    (str "Workflow already exists: " id')}

            :else
            (try
              (let [type-def' (cond-> type-def
                                start-event (assoc :start-event start-event))
                    inst      (create-instance! reg ext-path type-def' id' input meta)
                    session-id (:session-id inst)]
                (swap! (:state reg)
                       (fn [st]
                         (-> st
                             (assoc-in [:instances wk] inst)
                             (assoc-in [:session-index session-id] wk)
                             (update :order conj wk))))
                (let [start-ev (or start-event (:start-event type-def'))
                      auto?    (not= false auto-start?)
                      _        (when auto?
                                 (send-event-in! reg ext-path id' start-ev nil))
                      final-inst (workflow-in reg ext-path id')]
                  {:created? true
                   :workflow final-inst}))
              (catch Exception e
                {:created? false
                 :error    (or (some-> e ex-data :error)
                               (ex-message e))}))))))))

(defn workflow-in
  "Return a public workflow instance map, or nil."
  [reg ext-path id]
  (let [s   @(:state reg)
        wk  (workflow-key ext-path (normalize-id id))
        inst (get-in s [:instances wk])]
    (when inst
      (public-instance s inst))))

(defn workflows-in
  "Return workflow instances in creation order.
   When ext-path is supplied, returns only that extension's workflows."
  ([reg] (workflows-in reg nil))
  ([reg ext-path]
   (let [s @(:state reg)]
     (->> (:order s)
          (map #(get-in s [:instances %]))
          (remove nil?)
          (filter (fn [inst]
                    (or (nil? ext-path)
                        (= ext-path (:ext-path inst)))))
          (map #(public-instance s %))
          vec))))

(defn workflow-count-in
  ([reg] (workflow-count-in reg nil))
  ([reg ext-path]
   (count (workflows-in reg ext-path))))

(defn running-count-in
  ([reg] (running-count-in reg nil))
  ([reg ext-path]
   (->> (workflows-in reg ext-path)
        (filter :running?)
        count)))

(defn send-event-in!
  "Send an event to a workflow instance.

   Returns {:event-accepted? bool :workflow map? :error string?}."
  [reg ext-path id event data]
  (cond
    (blankish? ext-path)
    {:event-accepted? false :error "ext-path is required."}

    (blankish? id)
    {:event-accepted? false :error "id is required."}

    (not (keyword? event))
    {:event-accepted? false :error "event must be a keyword."}

    :else
    (locking reg
      (let [id'        (normalize-id id)
            wk         (workflow-key ext-path id')
            s          @(:state reg)
            inst       (get-in s [:instances wk])
            session-id (:session-id inst)
            wm         (and session-id (get-working-memory reg session-id))]
        (cond
          (nil? inst)
          {:event-accepted? false
           :error           (str "No workflow found: " id')}

          (nil? wm)
          {:event-accepted? false
           :error           (str "Workflow has no active state: " id')}

          :else
          (let [evt     (evts/new-event {:name event :data (or data {})})
                wm'     (sp/process-event! (::sc/processor (:env reg)) (:env reg) wm evt)
                _       (save-working-memory! reg session-id wm')
                updated (update-instance-from-wm inst wm' event data)]
            (swap! (:state reg) assoc-in [:instances wk] updated)
            {:event-accepted? true
             :workflow        (workflow-in reg ext-path id')}))))))

(defn abort-workflow-in!
  "Abort a workflow instance and cancel active invocations.

   Returns {:aborted? bool :workflow map? :error string?}."
  ([reg ext-path id] (abort-workflow-in! reg ext-path id nil))
  ([reg ext-path id reason]
   (cond
     (blankish? ext-path)
     {:aborted? false :error "ext-path is required."}

     (blankish? id)
     {:aborted? false :error "id is required."}

     :else
     (locking reg
       (let [id'        (normalize-id id)
             wk         (workflow-key ext-path id')
             s          @(:state reg)
             inst       (get-in s [:instances wk])
             session-id (:session-id inst)
             wm         (and session-id (get-working-memory reg session-id))]
         (if-not inst
           {:aborted? false
            :error    (str "No workflow found: " id')}
           (do
             (when wm
               (sp/exit! (::sc/processor (:env reg)) (:env reg) wm true)
               (delete-working-memory! reg session-id))
             (let [updated (-> inst
                               (assoc :phase :aborted
                                      :configuration #{:aborted}
                                      :running? false
                                      :done? false
                                      :error? false
                                      :error-message reason
                                      :finished-at (or (:finished-at inst) (now))
                                      :updated-at (now)))]
               (swap! (:state reg) assoc-in [:instances wk] updated)
               {:aborted? true
                :workflow (workflow-in reg ext-path id')}))))))))

(defn remove-workflow-in!
  "Remove a workflow instance.
   If it is running, it is aborted first.

   Returns {:removed? bool :id string? :error string?}."
  [reg ext-path id]
  (cond
    (blankish? ext-path)
    {:removed? false :error "ext-path is required."}

    (blankish? id)
    {:removed? false :error "id is required."}

    :else
    (locking reg
      (let [id'        (normalize-id id)
            wk         (workflow-key ext-path id')
            s          @(:state reg)
            inst       (get-in s [:instances wk])
            session-id (:session-id inst)
            wm         (and session-id (get-working-memory reg session-id))]
        (if-not inst
          {:removed? false
           :error    (str "No workflow found: " id')}
          (do
            (when wm
              (sp/exit! (::sc/processor (:env reg)) (:env reg) wm true)
              (delete-working-memory! reg session-id))
            (swap! (:state reg)
                   (fn [st]
                     (-> st
                         (update :instances dissoc wk)
                         (update :session-index dissoc session-id)
                         (update :order (fn [order]
                                          (vec (remove #(= % wk) order)))))))
            {:removed? true
             :id id'}))))))

(defn- clear-extension-workflows-in!
  "Abort and remove all workflows for `ext-path`.
   Returns the number removed."
  [reg ext-path]
  (let [ids (->> (workflows-in reg ext-path)
                 (map :id)
                 vec)]
    (doseq [id ids]
      (remove-workflow-in! reg ext-path id))
    (count ids)))

(defn clear-all-in!
  "Abort and remove all workflows across all extensions.
   Returns number removed."
  [reg]
  (let [ext-paths (->> (workflows-in reg)
                       (map :ext-path)
                       distinct
                       vec)]
    (reduce (fn [n ext-path]
              (+ n (clear-extension-workflows-in! reg ext-path)))
            0
            ext-paths)))
