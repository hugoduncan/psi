(ns psi.agent-session.workflow.artifact-routing
  "Workflow operations that read task artifacts before deterministic routing."
  (:require
   [clojure.string :as str]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.workflow.routing :as routing]))

(defn- scope-question-gate-arg-errors
  [{:keys [task-path artifact marker proceed-route open-route]}]
  (cond-> []
    (not (string? task-path))     (conj {:field :task-path :reason :non-string})
    (not (string? artifact))      (conj {:field :artifact :reason :non-string})
    (not (string? marker))        (conj {:field :marker :reason :non-string})
    (not (string? proceed-route)) (conj {:field :proceed-route :reason :non-string})
    (not (string? open-route))    (conj {:field :open-route :reason :non-string})))

(defn scope-question-gate-routing
  "Deterministic gate handler: scan a task artifact for unchecked SCOPE_QUESTION
   items and route to proceed/open accordingly (DI-3).

   IO is the single resolver read; the routing decision is the pure scanner.
   The owning session id is `(or parent-session-id session-id)`: the production
   `:invoke`-step judge path supplies `:parent-session-id` and no `:session-id`,
   so reading only `:session-id` would resolve a nil worktree and fail the gate
   open. The session id is seeded into `extra-entity` (where `query-in` reads it)
   and `(:ctx invocation)` is passed as the agent-session-ctx."
  [{:keys [args ctx parent-session-id session-id]}]
  (let [errors (scope-question-gate-arg-errors args)]
    (if (seq errors)
      {:status :error
       :reason :invalid-scope-question-gate-args
       :message "workflow/scope-question-gate-routing args are invalid"
       :details {:errors errors}}
      (let [{:keys [task-path artifact marker proceed-route open-route]} args
            owning-session-id (or parent-session-id session-id)
            task-dir (routing/normalize-open-task-path task-path)
            content (when task-dir
                      (:psi.munera/task-artifact-content
                       (resolvers/query-in
                        ctx
                        [:psi.munera/task-artifact-content]
                        {:psi.agent-session/session-id owning-session-id
                         :psi.munera/task-path task-dir
                         :psi.munera/artifact-name artifact})))]
        (routing/parse-scope-question-gate content marker proceed-route open-route)))))

(defn- read-task-artifact-content
  [ctx session-id task-path artifact]
  (if-let [read-fn (:workflow-task-artifact-content-read-fn ctx)]
    (read-fn session-id task-path artifact)
    (when-let [task-dir (routing/normalize-open-task-path task-path)]
      (:psi.munera/task-artifact-content
       (resolvers/query-in
        ctx
        [:psi.munera/task-artifact-content]
        {:psi.agent-session/session-id session-id
         :psi.munera/task-path task-dir
         :psi.munera/artifact-name artifact})))))

(defn- valid-final-complete-block-routing-args?
  [{:keys [task-path artifact start-delimiter field-prefixes end-delimiter valid-route
           output-field-labels]}]
  (and (string? task-path)
       (not (str/blank? task-path))
       (string? artifact)
       (not (str/blank? artifact))
       (string? start-delimiter)
       (not (str/blank? start-delimiter))
       (routing/valid-field-prefixes? field-prefixes)
       (string? end-delimiter)
       (not (str/blank? end-delimiter))
       (routing/valid-route-token? valid-route)
       (or (nil? output-field-labels)
           (and (vector? output-field-labels)
                (= (count field-prefixes) (count output-field-labels))
                (seq output-field-labels)
                (every? routing/valid-route-token? output-field-labels)
                (apply distinct? output-field-labels)))))

(defn- invalid-final-complete-block-routing-args-result
  [args]
  {:status :error
   :reason :invalid-final-complete-block-routing-args
   :message "workflow/final-complete-block-routing args are invalid"
   :details {:args args}})

(defn- final-complete-block-routing-result
  [args content]
  (let [{:keys [task-path artifact start-delimiter field-prefixes end-delimiter valid-route
                output-field-labels]} args]
    (if-not (valid-final-complete-block-routing-args? args)
      (invalid-final-complete-block-routing-args-result args)
      (if-let [record (routing/parse-final-complete-block
                       content start-delimiter field-prefixes end-delimiter)]
        {:status :ok
         :data valid-route
         :summary valid-route
         :details (cond-> {:record record}
                    output-field-labels
                    (assoc :required-fields-text
                           (str/join "\n" (map (fn [label prefix]
                                                 (str label ": " (record prefix)))
                                               output-field-labels field-prefixes))))}
        {:status :error
         :reason :missing-final-complete-block
         :message "Required complete artifact block is missing"
         :details {:task-path task-path :artifact artifact}}))))

(defn final-complete-block-routing
  "Read one task artifact and route only when it contains a complete authored
   block. The caller supplies all syntax and route policy."
  [{:keys [args ctx parent-session-id session-id]}]
  (if-not (valid-final-complete-block-routing-args? args)
    (invalid-final-complete-block-routing-args-result args)
    (let [owning-session-id (or parent-session-id session-id)
          content (read-task-artifact-content ctx owning-session-id
                                              (:task-path args) (:artifact args))]
      (final-complete-block-routing-result args content))))

(defn- valid-task-artifact-content-read-args?
  [{:keys [task-path artifact]}]
  (and (string? task-path)
       (not (str/blank? task-path))
       (string? artifact)
       (not (str/blank? artifact))))

(defn task-artifact-content-read
  "Read a task artifact as an invoke-step value for authored workflow policy."
  [{:keys [args ctx parent-session-id session-id]}]
  (if-not (valid-task-artifact-content-read-args? args)
    {:status :error
     :reason :invalid-task-artifact-content-read-args
     :message "workflow/task-artifact-content-read args are invalid"
     :details {:args args}}
    (let [content (read-task-artifact-content ctx (or parent-session-id session-id)
                                              (:task-path args) (:artifact args))]
      {:status :ok :data content :summary "DONE"})))

(defn- fresh-final-complete-block-routing-result
  [args content]
  (let [{:keys [before-content] :as args} args
        result (final-complete-block-routing-result (dissoc args :before-content)
                                                    content)]
    (cond
      (= :invalid-final-complete-block-routing-args (:reason result))
      result

      (not (string? before-content))
      {:status :error
       :reason :invalid-fresh-final-complete-block-routing-args
       :message "workflow/fresh-final-complete-block-routing args are invalid"
       :details {:args args}}

      (routing/final-complete-block-appended?
       before-content content
       (:start-delimiter args) (:field-prefixes args) (:end-delimiter args))
      result

      :else
      {:status :error
       :reason :missing-fresh-final-complete-block
       :message "Required complete artifact block was not newly appended"
       :details {:task-path (:task-path args) :artifact (:artifact args)}})))

(defn fresh-final-complete-block-routing
  "Require a complete authored block newly appended since the captured artifact."
  [{:keys [args ctx parent-session-id session-id]}]
  (cond
    (not (valid-final-complete-block-routing-args? (dissoc args :before-content)))
    (invalid-final-complete-block-routing-args-result (dissoc args :before-content))

    (not (string? (:before-content args)))
    {:status :error
     :reason :invalid-fresh-final-complete-block-routing-args
     :message "workflow/fresh-final-complete-block-routing args are invalid"
     :details {:args args}}

    :else
    (let [content (read-task-artifact-content ctx (or parent-session-id session-id)
                                              (:task-path args) (:artifact args))]
      (fresh-final-complete-block-routing-result args content))))
