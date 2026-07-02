(ns psi.turn-runtime.augmentation
  "Pure helpers for pre-turn request augmentation records and rendering."
  (:require
   [clojure.string :as str]))

(def turn-augmentation-capability :psi.capability/turn-augmentation)
(def known-capabilities #{turn-augmentation-capability})

(def preparable-statuses #{:success :no-op :partial :failed :replay-used})

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
       (contains? #{:success
                    :no-op
                    :failed
                    :invalid-operation
                    :unsupported-operation
                    :unauthorized
                    :canceled
                    :stale}
                  (:status provider))
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
