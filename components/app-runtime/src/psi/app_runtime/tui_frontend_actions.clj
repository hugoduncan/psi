(ns psi.app-runtime.tui-frontend-actions
  (:require
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.runtime :as runtime]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.ai.model-registry :as model-registry]))

(defn handle-action-result
  [{:keys [ctx sid action-result resolve-model-by-provider+id
           switch-session-fn! fork-session-fn! set-focus!]}]
  (let [{:ui.result/keys [action-key status value message]} action-result]
    (case action-key
      :select-model
      (case status
        :submitted
        (if-let [resolved (and (map? value)
                               (resolve-model-by-provider+id (:provider value) (:id value)))]
          (let [provider-str (name (:provider resolved))
                model {:provider provider-str
                       :id (:id resolved)
                       :reasoning (boolean (:supports-reasoning resolved))}]
            (session/set-model-in! ctx sid model)
            {:type :text
             :message (str "✓ Model set to " provider-str " " (:id resolved))})
          {:type :text
           :message (or message
                        (str "Unknown model: " (:provider value) " " (:id value)))})

        (:cancelled :failed)
        {:type :text :message message}

        nil)

      :select-thinking-level
      (case status
        :submitted
        (when-let [level-str value]
          (let [result (session/set-thinking-level-in! ctx sid (keyword level-str))]
            {:type :text
             :message (str "✓ Thinking level set to " (name (:thinking-level result)))}))

        (:cancelled :failed)
        {:type :text :message message}

        nil)

      :select-resume-session
      (case status
        :submitted
        (when (string? value)
          (let [sd (session/resume-session-in! ctx sid value)]
            (set-focus! (:session-id sd))
            {:type :session-resume-restored
             :restored {:messages (vec (or (:messages sd) []))
                        :tool-calls {}
                        :tool-order []}
             :session-id (:session-id sd)
             :path value}))

        (:cancelled :failed)
        {:type :text :message message}

        nil)

      :select-session
      (case status
        :submitted
        (when (map? value)
          (case (:action/kind value)
            :switch-session
            (when-let [selected-session-id (:action/session-id value)]
              (let [restored    (switch-session-fn! selected-session-id)
                    restored-id (or (:nav/session-id restored)
                                    (:session-id restored)
                                    selected-session-id)]
                (set-focus! restored-id)
                {:type :session-switch-restored
                 :restored restored
                 :session-id restored-id}))

            :fork-session
            (when-let [entry-id (:action/entry-id value)]
              (let [restored    (fork-session-fn! entry-id)
                    restored-id (or (:nav/session-id restored)
                                    (:session-id restored))]
                (when restored-id
                  (set-focus! restored-id))
                {:type :session-switch-restored
                 :restored restored
                 :session-id restored-id}))

            nil))

        (:cancelled :failed)
        {:type :text :message message}

        nil)

      nil)))

(defn command-result
  [{:keys [ctx sid text cmd-opts]}]
  (let [trimmed (str/trim text)]
    (cond
      (= trimmed "/model")
      {:type :frontend-action
       :ui/action
       (ui-actions/model-picker-action
        (->> (model-registry/all-models-seq)
             (sort-by (juxt :provider :id))
             (mapv (fn [m]
                     {:provider  (name (:provider m))
                      :id        (:id m)
                      :reasoning (boolean (:supports-reasoning m))}))))}

      (= trimmed "/thinking")
      {:type :frontend-action
       :ui/action (ui-actions/thinking-picker-action)}

      (= trimmed "/resume")
      {:type :frontend-action
       :ui/action
       (ui-actions/resume-session-action
        (session/query-in ctx sid
                          [{:psi.session/list
                            [:psi.session-info/path
                             :psi.session-info/name
                             :psi.session-info/worktree-path
                             :psi.session-info/first-message
                             :psi.session-info/modified]}]))}

      :else
      (commands/dispatch-in ctx sid text cmd-opts))))

(defn journal-command-result!
  [{:keys [ctx sid text result]}]
  (when result
    (runtime/journal-user-message-in! ctx sid text nil))
  result)
