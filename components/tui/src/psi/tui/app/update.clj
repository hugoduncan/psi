(ns psi.tui.app.update
  (:require
   [charm.core :as charm]
   [charm.message :as msg]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.persistence :as persist]
   [psi.tui.app.autocomplete :as autocomplete]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]
   [psi.tui.session-selector :as session-selector]))

(defn open-session-selector
  ([state] (open-session-selector state :resume))
  ([state mode]
   (let [sel (case mode
               :tree (session-selector/session-selector-init-from-context
                      (:session-selector-fn state)
                      (:cwd state)
                      (:current-session-file state)
                      (:focus-session-id state))
               (session-selector/session-selector-init (:cwd state) (:current-session-file state)))]
     [(-> state
          (assoc :phase :selecting-session
                 :session-selector sel
                 :session-selector-mode mode)
          (shared/set-input-model (charm/text-input-reset (:input state))))
      nil])))

(defn restored-session-payload
  [restored]
  (let [rehydration (or (:nav/rehydration restored)
                        restored)]
    {:messages   (vec (or (if (map? rehydration) (:messages rehydration) rehydration) []))
     :tool-calls (or (when (map? rehydration) (:tool-calls rehydration)) {})
     :tool-order (vec (or (when (map? rehydration) (:tool-order rehydration)) []))}))

(defn restored-session-id
  [restored]
  (or (:nav/session-id restored)
      (:session-id restored)))

(defn reset-input-model
  [state]
  (shared/set-input-model state (charm/text-input-reset (:input state))))

(defn append-assistant-message
  [state text]
  (update state :messages conj {:role :assistant :text text}))

(defn restore-session-view
  [state {:keys [messages tool-calls tool-order]} extra]
  (-> state
      (assoc :messages messages
             :stream-text nil
             :stream-thinking nil
             :tool-calls tool-calls
             :tool-order tool-order
             :force-clear? true)
      (merge extra)
      reset-input-model))

(defn session-switch-unavailable
  [state]
  [(-> state
       (assoc :session-selector nil
              :session-selector-mode nil
              :phase :idle)
       reset-input-model
       (append-assistant-message "Session switch is unavailable in this runtime."))
   nil])

(defn handle-tree-switch-result
  [state result]
  (let [switch-fn (:switch-session-fn! state)
        sid       (:session-id result)]
    (if (and switch-fn (string? sid) (not (str/blank? sid)))
      [(restore-session-view state
                             (restored-session-payload (switch-fn sid))
                             {:phase :idle
                              :focus-session-id sid
                              :session-selector nil
                              :session-selector-mode nil})
       nil]
      (session-switch-unavailable state))))

(defn query-current-session-id
  [state]
  (when-let [qfn (:query-fn state)]
    (try
      (:psi.agent-session/session-id (qfn [:psi.agent-session/session-id]))
      (catch Exception _ nil))))

(defn handle-new-session-result
  [state result]
  (let [restored (or (:rehydrate result) result)
        payload  (restored-session-payload restored)
        new-sid  (or (restored-session-id restored)
                     (query-current-session-id state))]
    [(-> (restore-session-view state payload {:error nil})
         (cond-> new-sid (assoc :focus-session-id new-sid))
         (append-assistant-message (:message result)))
     nil]))

(defn handle-text-result
  [state text]
  [(-> state
       reset-input-model
       (append-assistant-message text))
   nil])

(defn run-extension-command
  [result]
  (try
    (when-let [handler (:handler result)]
      (let [captured (with-out-str (handler (:args result)))]
        (when-not (str/blank? captured)
          (str/trimr captured))))
    (catch Exception e
      (timbre/warn "Extension command error:" (ex-message e))
      (str "[command error: " (ex-message e) "]"))))

(defn login-start-message
  [result]
  (str "🔑 Login to " (get-in result [:provider :name])
       "\n\nOpen this URL in your browser:\n" (:url result)
       (if (:uses-callback-server result)
         "\n\nWaiting for browser callback…"
         "\n\nPaste the authorization code below ↓")))

(defn handle-dispatch-result
  [state result]
  (when result
    (case (:type result)
      :quit [state charm/quit-cmd]
      :resume (open-session-selector state :resume)
      :tree-open (open-session-selector state :tree)
      :tree-switch (handle-tree-switch-result state result)
      :tree-rename (handle-text-result state (str "Renamed session " (:session-id result) " to " (pr-str (:session-name result))))
      :new-session (handle-new-session-result state result)
      :text (handle-text-result state (:message result))
      (:login-error :logout) (handle-text-result state (:message result))
      :extension-cmd
      (let [output (run-extension-command result)]
        [(cond-> (reset-input-model state)
           output (append-assistant-message output))
         nil])
      :login-start (handle-text-result state (login-start-message result))
      (handle-text-result state (str result)))))

(defn close-session-selector
  [state]
  [(assoc state :phase :idle :session-selector nil :session-selector-mode nil) nil])

(defn toggle-selector-scope
  [sel]
  (let [new-scope (if (= :current (:scope sel)) :all :current)]
    (if (and (= :all new-scope) (nil? (:all-sessions sel)))
      (-> sel
          (assoc :scope :all :all-sessions (persist/list-all-sessions))
          session-selector/selector-clamp)
      (-> sel
          (assoc :scope new-scope)
          session-selector/selector-clamp))))

(defn choose-tree-session
  [state chosen]
  (case (:item-kind chosen :session)
    :fork-point
    (if-let [fork-fn (:fork-session-fn! state)]
      (let [restored (fork-fn (:entry-id chosen))
            sid      (restored-session-id restored)]
        [(restore-session-view state
                               (restored-session-payload restored)
                               {:phase :idle
                                :focus-session-id sid
                                :session-selector nil
                                :session-selector-mode nil})
         nil])
      (session-switch-unavailable state))

    (let [sid      (:session-id chosen)
          restored ((:switch-session-fn! state) sid)]
      [(restore-session-view state
                             (restored-session-payload restored)
                             {:phase :idle
                              :focus-session-id (or (restored-session-id restored) sid)
                              :session-selector nil
                              :session-selector-mode nil})
       nil])))

(defn choose-resume-session
  [state chosen]
  (let [path     (:path chosen)
        restored (when-let [resume-fn (:resume-fn! state)]
                   (resume-fn path))]
    [(restore-session-view state
                           (restored-session-payload restored)
                           {:phase :idle
                            :focus-session-id (or (restored-session-id restored)
                                                  (:focus-session-id state))
                            :session-selector nil
                            :session-selector-mode nil
                            :current-session-file path})
     nil]))

(defn handle-selector-enter
  [state sel]
  (let [chosen (nth (session-selector/selector-filtered sel) (:selected sel) nil)
        mode   (:session-selector-mode state :resume)]
    (cond
      (nil? chosen) (close-session-selector state)
      (= :tree mode) (choose-tree-session state chosen)
      :else (choose-resume-session state chosen))))

(defn handle-selector-key
  [state m]
  (let [sel       (:session-selector state)
        key-token (when (msg/key-press? m) (:key m))]
    (cond
      (msg/key-match? m "escape") (close-session-selector state)
      (msg/key-match? m "ctrl+c") [state charm/quit-cmd]
      (and (msg/key-match? m "tab")
           (not= :tree (:session-selector-mode state)))
      [(assoc state :session-selector (toggle-selector-scope sel)) nil]
      (msg/key-match? m "up") [(update state :session-selector session-selector/selector-move -1) nil]
      (msg/key-match? m "down") [(update state :session-selector session-selector/selector-move 1) nil]
      (msg/key-match? m "enter") (handle-selector-enter state sel)
      (msg/key-press? m) [(update state :session-selector session-selector/selector-type support/key-token->string support/printable-key key-token) nil]
      :else [state nil])))

(declare clear-live-turn)

(defn submit-to-agent
  [state run-agent-fn! text]
  (let [queue (:queue state)]
    (run-agent-fn! text queue)
    [(-> state
         (update :messages conj {:role :user :text text})
         (assoc :phase         :streaming
                :error         nil
                :spinner-frame 0)
         clear-live-turn
         (shared/set-input-model (charm/text-input-reset (:input state))))
     (support/poll-cmd queue)]))

(defn submit-input
  [state run-agent-fn!]
  (let [text (str/trim (charm/text-input-value (:input state)))]
    (cond
      (str/blank? text)
      [state nil]

      :else
      (let [state       (shared/record-history-entry state text)
            dispatch-fn (:dispatch-fn state)
            result      (when dispatch-fn (dispatch-fn text))]
        (if result
          (handle-dispatch-result state result)
          (submit-to-agent state run-agent-fn! text))))))

(defn continue-input-line
  [state]
  (let [value       (charm/text-input-value (:input state))
        backslash?  (str/ends-with? value "\\")
        value'      (if backslash?
                      (subs value 0 (dec (count value)))
                      value)
        next-input  (charm/text-input-set-value (:input state) (str value' "\n"))]
    [(shared/set-input-model state next-input) nil]))

(defn delete-prev-word
  [input]
  (let [s   (charm/text-input-value input)
        pos (long (or (:pos input) (count s)))]
    (if (<= pos 0)
      input
      (let [i1 (loop [i (dec pos)]
                 (if (and (>= i 0)
                          (Character/isWhitespace (.charAt s i)))
                   (recur (dec i))
                   i))
            i2 (loop [i i1]
                 (if (and (>= i 0)
                          (not (Character/isWhitespace (.charAt s i))))
                   (recur (dec i))
                   i))
            start (inc i2)
            s'    (str (subs s 0 start) (subs s pos))]
        (-> input
            (assoc :value (vec s'))
            (assoc :pos start))))))

(defn tool-result-text
  [event]
  (or (:result-text event)
      (some->> (:content event)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               (str/join "\n"))))

(defn clear-live-turn
  [state]
  (assoc state
         :stream-text nil
         :stream-thinking nil
         :active-turn-order []
         :active-turn-items {}
         :active-turn-events []
         :active-turn-next-seq 0
         :tool-ui-id-by-tool-id {}
         :tool-ui-id-by-content-index {}))

(defn append-active-turn-event
  [state event]
  (let [seq-no (:active-turn-next-seq state 0)]
    (-> state
        (update :active-turn-events conj (assoc event :seq seq-no))
        (update :active-turn-next-seq (fnil inc 0)))))

(defn tool-event-snapshot
  [tool-name arguments status parsed-args content details result-text is-error]
  {:name      tool-name
   :args      (or arguments "")
   :status    status
   :parsed-args parsed-args
   :content   content
   :details   details
   :result    result-text
   :is-error  (boolean is-error)})

(defn ensure-active-turn-item
  [state item-id base-item]
  (let [created? (not (contains? (set (:active-turn-order state)) item-id))]
    [(cond-> state
       item-id (assoc-in [:active-turn-items item-id]
                         (merge (get-in state [:active-turn-items item-id]) base-item))
       (and item-id created?) (update :active-turn-order conj item-id))
     item-id]))

(defn thinking-item-id
  [content-index]
  (str "thinking/" (or content-index 0)))

(defn text-item-id
  [content-index]
  (str "text/" (or content-index 0)))

(defn upsert-thinking-item
  [state content-index text]
  (-> state
      (append-active-turn-event {:item-kind     :thinking
                                 :content-index (or content-index 0)
                                 :text          (or text "")})
      ((fn [state*]
         (first
          (ensure-active-turn-item
           state*
           (thinking-item-id content-index)
           {:item-kind     :thinking
            :content-index (or content-index 0)
            :text          (or text "")}))))))

(defn upsert-text-item
  [state content-index text]
  (-> state
      (append-active-turn-event {:item-kind     :text
                                 :content-index (or content-index 0)
                                 :text          (or text "")})
      ((fn [state*]
         (first
          (ensure-active-turn-item
           state*
           (text-item-id content-index)
           {:item-kind     :text
            :content-index (or content-index 0)
            :text          (or text "")}))))))

(defn ensure-tool-row
  [state {:keys [tool-id content-index tool-name arguments]}]
  (let [existing-id (or (get-in state [:tool-ui-id-by-tool-id tool-id])
                        (get-in state [:tool-ui-id-by-content-index content-index])
                        (when (and tool-id (contains? (:tool-calls state) tool-id)) tool-id))
        ui-id       (or existing-id
                        (when tool-id (str "tool/" tool-id))
                        (when (some? content-index) (str "tool/content-" content-index)))
        created?    (not (contains? (set (:tool-order state)) ui-id))
        tc          (merge {:name      tool-name
                            :args      (or arguments "")
                            :status    :pending
                            :result    nil
                            :is-error  false
                            :expanded? (boolean (:tools-expanded? state))}
                           (get-in state [:tool-calls ui-id]))]
    [(cond-> state
       ui-id (assoc-in [:tool-calls ui-id] tc)
       (and ui-id created?) (update :tool-order conj ui-id)
       (and ui-id tool-id) (assoc-in [:tool-ui-id-by-tool-id tool-id] ui-id)
       (and ui-id (some? content-index)) (assoc-in [:tool-ui-id-by-content-index content-index] ui-id)
       ui-id (assoc-in [:active-turn-items ui-id]
                       (merge (get-in state [:active-turn-items ui-id])
                              {:item-kind :tool
                               :tool-id ui-id}))
       (and ui-id (not (contains? (set (:active-turn-order state)) ui-id)))
       (update :active-turn-order conj ui-id))
     ui-id]))

(defn handle-agent-event
  [state event]
  (let [kind (:event-kind event)]
    (case kind
      :text-delta
      [(let [state' (assoc state :stream-text (:text event))]
         (upsert-text-item state' (:content-index event) (:text event)))
       nil]

      :thinking-delta
      [(let [state' (assoc state :stream-thinking (or (:text event) ""))]
         (upsert-thinking-item state' (:content-index event) (:text event)))
       nil]

      :tool-call-assembly
      (let [state0         (append-active-turn-event state {:item-kind     :tool
                                                            :content-index (:content-index event)
                                                            :tool-id       (:tool-id event)
                                                            :tool-name     (:tool-name event)
                                                            :arguments     (:arguments event)
                                                            :phase         (:phase event)
                                                            :snapshot      (tool-event-snapshot
                                                                            (:tool-name event)
                                                                            (:arguments event)
                                                                            (if (= :end (:phase event)) :pending :assembling)
                                                                            nil nil nil nil false)})
            [state' ui-id] (ensure-tool-row state0 {:tool-id (:tool-id event)
                                                    :content-index (:content-index event)
                                                    :tool-name (:tool-name event)
                                                    :arguments (:arguments event)})]
        [(-> state'
             (assoc-in [:tool-calls ui-id :name] (:tool-name event))
             (assoc-in [:tool-calls ui-id :args] (or (:arguments event) ""))
             (assoc-in [:tool-calls ui-id :status]
                       (if (= :end (:phase event)) :pending :assembling))
             (assoc-in [:active-turn-items ui-id :item-kind] :tool)
             (assoc-in [:active-turn-items ui-id :tool-id] ui-id)
             (assoc-in [:active-turn-items ui-id :status]
                       (if (= :end (:phase event)) :pending :assembling)))
         nil])

      :tool-start
      (let [state0         (append-active-turn-event state {:item-kind :tool-lifecycle
                                                            :tool-id   (:tool-id event)
                                                            :tool-name (:tool-name event)
                                                            :status    :pending
                                                            :snapshot  (tool-event-snapshot
                                                                        (:tool-name event)
                                                                        nil
                                                                        :pending
                                                                        nil nil nil nil false)})
            [state' ui-id] (ensure-tool-row state0 {:tool-id (:tool-id event)
                                                    :tool-name (:tool-name event)})]
        [(-> state'
             (assoc-in [:tool-calls ui-id :status] :pending)
             (assoc-in [:active-turn-items ui-id :item-kind] :tool)
             (assoc-in [:active-turn-items ui-id :tool-id] ui-id)
             (assoc-in [:active-turn-items ui-id :status] :pending))
         nil])

      :tool-delta
      (let [[state' ui-id] (ensure-tool-row state {:tool-id (:tool-id event)
                                                   :arguments (:arguments event)})]
        [(assoc-in state' [:tool-calls ui-id :args]
                   (:arguments event))
         nil])

      :tool-executing
      (let [state0         (append-active-turn-event state {:item-kind   :tool-lifecycle
                                                            :tool-id     (:tool-id event)
                                                            :tool-name   (:tool-name event)
                                                            :status      :running
                                                            :parsed-args (:parsed-args event)
                                                            :snapshot    (tool-event-snapshot
                                                                          (:tool-name event)
                                                                          nil
                                                                          :running
                                                                          (:parsed-args event)
                                                                          nil nil nil false)})
            [state' ui-id] (ensure-tool-row state0 {:tool-id (:tool-id event)
                                                    :tool-name (:tool-name event)})]
        [(-> state'
             (assoc-in [:tool-calls ui-id :status] :running)
             (assoc-in [:tool-calls ui-id :parsed-args]
                       (:parsed-args event))
             (assoc-in [:active-turn-items ui-id :item-kind] :tool)
             (assoc-in [:active-turn-items ui-id :tool-id] ui-id)
             (assoc-in [:active-turn-items ui-id :status] :running))
         nil])

      :tool-execution-update
      (let [result-text    (tool-result-text event)
            state0         (append-active-turn-event state {:item-kind   :tool-lifecycle
                                                            :tool-id     (:tool-id event)
                                                            :tool-name   (:tool-name event)
                                                            :status      :running
                                                            :content     (:content event)
                                                            :result-text result-text
                                                            :details     (:details event)
                                                            :is-error    (boolean (:is-error event))
                                                            :snapshot    (tool-event-snapshot
                                                                          (:tool-name event)
                                                                          nil
                                                                          :running
                                                                          nil
                                                                          (:content event)
                                                                          (:details event)
                                                                          result-text
                                                                          (boolean (:is-error event)))})
            [state' ui-id] (ensure-tool-row state0 {:tool-id (:tool-id event)
                                                    :tool-name (:tool-name event)})]
        [(-> state'
             (assoc-in [:tool-calls ui-id :status] :running)
             (assoc-in [:tool-calls ui-id :content] (:content event))
             (assoc-in [:tool-calls ui-id :details] (:details event))
             (assoc-in [:tool-calls ui-id :result] (tool-result-text event))
             (assoc-in [:tool-calls ui-id :is-error]
                       (boolean (:is-error event)))
             (assoc-in [:active-turn-items ui-id :item-kind] :tool)
             (assoc-in [:active-turn-items ui-id :tool-id] ui-id)
             (assoc-in [:active-turn-items ui-id :status] :running))
         nil])

      :tool-result
      (let [final-status   (if (:is-error event) :error :success)
            result-text    (tool-result-text event)
            snapshot       (tool-event-snapshot
                            (:tool-name event)
                            nil
                            final-status
                            nil
                            (:content event)
                            (:details event)
                            result-text
                            (boolean (:is-error event)))
            state0         (append-active-turn-event
                            state
                            {:item-kind   :tool-lifecycle
                             :tool-id     (:tool-id event)
                             :tool-name   (:tool-name event)
                             :status      final-status
                             :content     (:content event)
                             :result-text result-text
                             :details     (:details event)
                             :is-error    (boolean (:is-error event))
                             :snapshot    snapshot})
            [state' ui-id] (ensure-tool-row state0 {:tool-id (:tool-id event)
                                                    :tool-name (:tool-name event)})]
        [(-> state'
             (assoc-in [:tool-calls ui-id :status] final-status)
             (assoc-in [:tool-calls ui-id :content] (:content event))
             (assoc-in [:tool-calls ui-id :details] (:details event))
             (assoc-in [:tool-calls ui-id :result] (tool-result-text event))
             (assoc-in [:tool-calls ui-id :is-error]
                       (boolean (:is-error event)))
             (assoc-in [:tool-calls ui-id :expanded?]
                       (boolean (:tools-expanded? state)))
             (assoc-in [:active-turn-items ui-id :item-kind] :tool)
             (assoc-in [:active-turn-items ui-id :tool-id] ui-id)
             (assoc-in [:active-turn-items ui-id :status] final-status))
         nil])

      [state nil])))

(defn handle-agent-result
  [state result]
  (let [content (:content result)
        text    (message-text/content-text content)
        errors  (message-text/content-error-parts content)
        error   (first errors)
        display (if (seq (or text "")) text "(no response)")]
    [(-> state
         (update :messages conj {:role :assistant :text display})
         (assoc :phase :idle
                :error error)
         clear-live-turn)
     (support/poll-cmd (:queue state))]))

(defn handle-agent-poll
  [state]
  (let [n (count shared/spinner-frames)]
    [(update state :spinner-frame #(mod (inc %) n))
     (support/poll-cmd (:queue state) 300)]))

(defn handle-ctrl-c
  [state]
  (let [now        (shared/now-ms)
        window-ms  (shared/double-press-window-ms state)
        last-clear (get-in state [:prompt-input-state :timing :last-ctrl-c-ms])]
    (if (shared/within-double-press-window? last-clear now window-ms)
      [(assoc-in state [:prompt-input-state :timing :last-ctrl-c-ms] nil)
       charm/quit-cmd]
      [(-> state
           (shared/set-input-value "")
           (assoc-in [:prompt-input-state :timing :last-ctrl-c-ms] now))
       nil])))

(defn handle-ctrl-d
  [state]
  (if (str/blank? (shared/input-value state))
    [state charm/quit-cmd]
    [state nil]))

(defn handle-idle-escape
  [state]
  (let [current-text   (shared/input-value state)
        now            (shared/now-ms)
        window-ms      (shared/double-press-window-ms state)
        action         (:double-escape-action state :none)
        last-escape    (get-in state [:prompt-input-state :timing :last-escape-ms])
        second-escape? (shared/within-double-press-window? last-escape now window-ms)]
    (cond
      (not (str/blank? current-text))
      [state nil]

      (= action :none)
      [state nil]

      second-escape?
      (case action
        :tree
        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (shared/append-assistant-status "Double Escape action '/tree' is not available in this runtime."))
         nil]

        :fork
        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (shared/append-assistant-status "Double Escape action '/fork' is not available in this runtime."))
         nil]

        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (shared/append-assistant-status (str "Unsupported double Escape action: " (pr-str action))))
         nil])

      :else
      [(assoc-in state [:prompt-input-state :timing :last-escape-ms] now) nil])))

(defn handle-streaming-escape
  [state]
  (if-let [interrupt-fn (:on-interrupt-fn! state)]
    (let [{:keys [queued-text message]} (or (interrupt-fn state) {})
          merged-text (shared/merge-queued-and-draft queued-text (shared/input-value state))
          next-state  (-> state
                          (shared/set-input-value merged-text)
                          (assoc :phase :idle)
                          clear-live-turn
                          (autocomplete/clear-autocomplete)
                          (assoc-in [:prompt-input-state :timing :last-escape-ms] (shared/now-ms))
                          (shared/append-assistant-status (or message "Interrupted.")))]
      [next-state (support/poll-cmd (:queue state))])
    [(shared/append-assistant-status state "Interrupt unavailable in this runtime.") nil]))

(defn handle-streaming-submit
  [state]
  (let [text     (str/trim (shared/input-value state))
        queue-fn (:on-queue-input-fn! state)]
    (cond
      (str/blank? text)
      [state (support/poll-cmd (:queue state))]

      queue-fn
      (let [result  (try
                      (queue-fn text state)
                      (catch Exception e
                        {:message (str "Failed to queue input: " (ex-message e))}))
            message (or (:message result)
                        "Queued for next turn.")]
        [(-> state
             (shared/set-input-value "")
             (autocomplete/clear-autocomplete)
             (shared/append-assistant-status message))
         (support/poll-cmd (:queue state))])

      :else
      [(shared/append-assistant-status state "Queueing input is unavailable in this runtime.")
       (support/poll-cmd (:queue state))])))
