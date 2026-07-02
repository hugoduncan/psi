(ns psi.turn-runtime.augmentation
  "Pure helpers for pre-turn request augmentation records and rendering."
  (:require
   [clojure.string :as str]))

(def turn-augmentation-capability :psi.capability/turn-augmentation)
(def known-capabilities #{turn-augmentation-capability})

(def preparable-statuses #{:success :no-op :partial :failed :replay-used})

(def provider-statuses
  #{:success
    :no-op
    :failed
    :invalid-operation
    :unsupported-operation
    :unauthorized
    :canceled
    :stale})

(def reason-order
  [:unauthorized
   :handler-exception
   :provider-canceled
   :late-stale-result
   :invalid-envelope
   :invalid-status
   :invalid-operations
   :invalid-child-session-ids
   :invalid-diagnostic
   :invalid-operation-shape
   :invalid-append-context-block
   :provenance-mismatch
   :unsupported-operation
   :missing-record
   :malformed-record
   :wrong-turn-id])

(def reason-rank (zipmap reason-order (range)))

(defn ordered-reasons
  [reasons]
  (->> reasons
       distinct
       (sort-by #(get reason-rank % Integer/MAX_VALUE))
       vec))

(defn non-blank-string?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn append-context-block-operation?
  [operation]
  (and (map? operation)
       (= :append-context-block (:op operation))
       (non-blank-string? (:id operation))
       (non-blank-string? (:title operation))
       (non-blank-string? (:content operation))
       (let [source (:source operation)]
         (and (map? source)
              (= :extension (:type source))
              (non-blank-string? (:extension-id source))
              (non-blank-string? (:augmenter-id source))
              (vector? (:child-session-ids source))
              (every? non-blank-string? (:child-session-ids source))))))

(defn provider-diagnostic?
  [provider]
  (and (map? provider)
       (non-blank-string? (:extension-id provider))
       (non-blank-string? (:augmenter-id provider))
       (contains? provider-statuses (:status provider))
       (integer? (:operation-count provider))
       (not (neg? (:operation-count provider)))
       (integer? (:accepted-operation-count provider))
       (not (neg? (:accepted-operation-count provider)))
       (integer? (:rejected-operation-count provider))
       (not (neg? (:rejected-operation-count provider)))
       (vector? (:reasons provider))
       (every? keyword? (:reasons provider))
       (vector? (:child-session-ids provider))
       (every? non-blank-string? (:child-session-ids provider))
       (or (nil? (:diagnostic provider))
           (string? (:diagnostic provider)))))

(defn well-formed-record?
  "Return true when `record` is a terminal augmentation record that request
   preparation may consume. Failed/partial/no-op terminal records are valid;
   canceled and replay-failed records are diagnostics-only and not preparable."
  [session-id turn-id record]
  (let [operations (:operations record)
        providers  (:providers record)]
    (and (map? record)
         (= session-id (:session-id record))
         (= turn-id (:turn-id record))
         (contains? preparable-statuses (:status record))
         (boolean? (:replay? record))
         (vector? operations)
         (= (count operations) (:accepted-operation-count record))
         (every? append-context-block-operation? operations)
         (vector? providers)
         (every? provider-diagnostic? providers)
         (not (:accepting? record)))))

(defn provider-source
  [provider child-session-ids]
  {:type :extension
   :extension-id (:extension-id provider)
   :augmenter-id (:augmenter-id provider)
   :child-session-ids (vec child-session-ids)})

(defn- source-matches?
  [provider child-session-ids source]
  (= (provider-source provider child-session-ids) source))

(defn- valid-child-session-ids?
  [child-session-ids]
  (and (vector? child-session-ids)
       (every? non-blank-string? child-session-ids)))

(defn- valid-diagnostic?
  [diagnostic]
  (or (nil? diagnostic) (string? diagnostic)))

(defn- operation-reasons
  [provider child-session-ids operation]
  (cond
    (not (map? operation))
    [:invalid-operation-shape]

    (= :append-context-block (:op operation))
    (cond-> []
      (not (and (non-blank-string? (:id operation))
                (non-blank-string? (:title operation))
                (non-blank-string? (:content operation))))
      (conj :invalid-append-context-block)

      (and (contains? operation :source)
           (not (source-matches? provider child-session-ids (:source operation))))
      (conj :provenance-mismatch))

    :else
    [:unsupported-operation]))

(defn- normalize-operation
  [provider child-session-ids operation]
  (-> operation
      (select-keys [:op :id :title :content])
      (assoc :source (provider-source provider child-session-ids))))

(defn provider-result->accepted
  "Validate one provider envelope and return provider diagnostics plus accepted
   normalized operations. This is pure; handler execution and liveness checks
   happen at the dispatch effect/runtime boundary."
  [provider envelope]
  (let [envelope*         (when (map? envelope) envelope)
        status            (:turn-augmentation/status envelope*)
        operations        (:turn-augmentation/operations envelope*)
        child-session-ids (if (contains? envelope* :turn-augmentation/child-session-ids)
                            (:turn-augmentation/child-session-ids envelope*)
                            [])
        diagnostic        (:turn-augmentation/diagnostic envelope*)
        envelope-reasons  (cond-> []
                            (not (map? envelope))
                            (conj :invalid-envelope)

                            (not (contains? #{:success :no-op} status))
                            (conj :invalid-status)

                            (not (vector? operations))
                            (conj :invalid-operations)

                            (not (valid-child-session-ids? child-session-ids))
                            (conj :invalid-child-session-ids)

                            (not (valid-diagnostic? diagnostic))
                            (conj :invalid-diagnostic))
        operations*       (if (vector? operations) operations [])
        operation-reasons* (mapcat #(operation-reasons provider child-session-ids %) operations*)
        status-shape-reasons (cond-> []
                               (and (= :success status) (empty? operations*))
                               (conj :invalid-operations)

                               (and (= :no-op status) (seq operations*))
                               (conj :invalid-operations))
        reasons           (ordered-reasons (concat envelope-reasons operation-reasons* status-shape-reasons))
        invalid?          (some #{:invalid-envelope
                                  :invalid-status
                                  :invalid-operations
                                  :invalid-child-session-ids
                                  :invalid-diagnostic
                                  :invalid-operation-shape
                                  :invalid-append-context-block
                                  :provenance-mismatch}
                                reasons)
        unsupported?      (some #{:unsupported-operation} reasons)
        accepted?         (and (empty? reasons)
                               (= :success status)
                               (seq operations*))
        accepted          (if accepted?
                            (mapv #(normalize-operation provider child-session-ids %) operations*)
                            [])
        provider-status   (cond
                            invalid? :invalid-operation
                            unsupported? :unsupported-operation
                            (= :no-op status) :no-op
                            accepted? :success
                            :else :invalid-operation)]
    {:provider (cond-> {:extension-id (:extension-id provider)
                        :augmenter-id (:augmenter-id provider)
                        :status provider-status
                        :operation-count (count operations*)
                        :accepted-operation-count (count accepted)
                        :rejected-operation-count (- (count operations*) (count accepted))
                        :child-session-ids (if (valid-child-session-ids? child-session-ids)
                                             (vec child-session-ids)
                                             [])
                        :reasons reasons}
                 (string? diagnostic) (assoc :diagnostic diagnostic))
     :operations accepted}))

(defn provider-failed
  [provider reason]
  {:provider {:extension-id (:extension-id provider)
              :augmenter-id (:augmenter-id provider)
              :status :failed
              :operation-count 0
              :accepted-operation-count 0
              :rejected-operation-count 0
              :child-session-ids []
              :reasons (ordered-reasons [reason])}
   :operations []})

(defn provider-unauthorized
  [provider]
  {:provider {:extension-id (:extension-id provider)
              :augmenter-id (:augmenter-id provider)
              :status :unauthorized
              :operation-count 0
              :accepted-operation-count 0
              :rejected-operation-count 0
              :child-session-ids []
              :reasons [:unauthorized]}
   :operations []})

(defn provider-stale
  [provider]
  {:provider {:extension-id (:extension-id provider)
              :augmenter-id (:augmenter-id provider)
              :status :stale
              :operation-count 0
              :accepted-operation-count 0
              :rejected-operation-count 0
              :child-session-ids []
              :reasons [:late-stale-result]}
   :operations []})

(defn aggregate-status
  [providers accepted-operation-count]
  (cond
    (empty? providers)
    :no-op

    (pos? accepted-operation-count)
    (if (every? #(contains? #{:success :no-op} (:status %)) providers)
      :success
      :partial)

    (every? #(= :no-op (:status %)) providers)
    :no-op

    :else
    :failed))

(defn terminal-record
  [session-id turn-id workflow-run-id provider-results]
  (let [providers  (mapv :provider provider-results)
        operations (into [] (mapcat :operations) provider-results)]
    {:session-id session-id
     :turn-id turn-id
     :workflow-run-id workflow-run-id
     :status (aggregate-status providers (count operations))
     :replay? false
     :accepted-operation-count (count operations)
     :operations operations
     :providers providers}))

(defn render-append-context-blocks
  [operations]
  (->> operations
       (filter #(= :append-context-block (:op %)))
       (map (fn [{:keys [title content]}]
              (str "[" title "]\n" content)))
       (str/join "\n\n")))

(defn augmentation-context-message
  [turn-id operations]
  (let [content (render-append-context-blocks operations)]
    (when (non-blank-string? content)
      {:id :turn/augmentation-context
       :kind :turn-context
       :role "user"
       :turn-id turn-id
       :content [{:type :text :text content}]})))

(defn augmentation-prompt-layer
  [record]
  (when-let [message (augmentation-context-message (:turn-id record) (:operations record))]
    {:id :turn/augmentation-context
     :kind :turn-context
     :role "user"
     :stable? false
     :turn-id (:turn-id record)
     :position :after-history-and-repairs-before-current-user
     :status (:status record)
     :operation-count (count (:operations record))
     :provider-count (count (:providers record))
     :operation-ids (mapv :id (:operations record))
     :content (get-in message [:content 0 :text])}))

(defn prepared-request-summary
  [record message-inserted?]
  {:turn-id (:turn-id record)
   :workflow-run-id (:workflow-run-id record)
   :status (:status record)
   :accepted-operation-count (:accepted-operation-count record)
   :message-inserted? (boolean message-inserted?)})

(defn insert-augmentation-message
  "Insert an augmentation context message immediately before the current user
   message. If the current user message is absent, append the context message."
  [messages record]
  (if-let [context-message (augmentation-context-message (:turn-id record) (:operations record))]
    (let [messages* (vec messages)]
      (if (and (seq messages*) (= "user" (:role (peek messages*))))
        (conj (pop messages*) context-message (peek messages*))
        (conj messages* context-message)))
    messages))

(defn summarize-record
  [record]
  (when record
    (let [operations (:operations record)]
      {:psi.turn-augmentation/session-id (:session-id record)
       :psi.turn-augmentation/turn-id (:turn-id record)
       :psi.turn-augmentation/workflow-run-id (:workflow-run-id record)
       :psi.turn-augmentation/status (:status record)
       :psi.turn-augmentation/replay? (:replay? record)
       :psi.turn-augmentation/replay-status (when (:replay? record) (:status record))
       :psi.turn-augmentation/accepted-operation-count (:accepted-operation-count record)
       :psi.turn-augmentation/message-inserted? (boolean (seq operations))
       :psi.turn-augmentation/operation-ids (mapv :id operations)
       :psi.turn-augmentation/providers
       (mapv (fn [provider]
               {:psi.turn-augmentation.provider/extension-id (:extension-id provider)
                :psi.turn-augmentation.provider/augmenter-id (:augmenter-id provider)
                :psi.turn-augmentation.provider/status (:status provider)
                :psi.turn-augmentation.provider/reasons (:reasons provider)
                :psi.turn-augmentation.provider/operation-count (:operation-count provider)
                :psi.turn-augmentation.provider/accepted-operation-count (:accepted-operation-count provider)
                :psi.turn-augmentation.provider/rejected-operation-count (:rejected-operation-count provider)
                :psi.turn-augmentation.provider/child-session-ids (:child-session-ids provider)
                :psi.turn-augmentation.provider/diagnostic (:diagnostic provider)})
             (:providers record))})))
