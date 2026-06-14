(ns psi.workflow-step-materialization.core
  "Workflow step materialization helpers for canonical deterministic workflow runs.

   Owns lower workflow-domain shaping/materialization behavior:
   step input materialization, child-session conversation materialization,
   and prompt derivation from materialized conversation."
  (:require
   [psi.workflow-step-materialization.semantics :as semantics]
   [psi.workflow-step-materialization.source-resolution :as source-resolution]))

(def binding-source-value source-resolution/resolve-binding-ref)

(defn materialize-step-inputs
  [workflow-run step-id]
  (let [step-def (semantics/effective-step-def workflow-run step-id)
        ir-template-vars (some->> (get-in step-def [:session :contributions])
                                  (filter #(= :template (:type %)))
                                  last
                                  :vars)]
    (into {}
          (map (fn [[var-name source-spec]]
                 [(keyword var-name) (source-resolution/apply-source-spec workflow-run source-spec)]))
          ir-template-vars)))

(defn- text-message
  [text]
  {:role "user"
   :content (str text)})

(defn- conversation-message?
  [x]
  (and (map? x)
       (string? (:role x))
       (contains? x :content)))

(defn- contribution-value->messages
  [value]
  (cond
    (nil? value)
    []

    (conversation-message? value)
    [value]

    (and (sequential? value)
         (every? conversation-message? value))
    (vec value)

    :else
    [(text-message value)]))

(defn- materialize-session-contribution
  [workflow-run contribution]
  (case (:type contribution)
    :source (contribution-value->messages
             (source-resolution/apply-source-spec workflow-run contribution))
    :template [(text-message
                (source-resolution/render-template-contribution workflow-run contribution))]
    []))

(defn materialize-contributions-conversation
  "Materialize an ordered `contributions` vector into ordered child-session
   conversation messages against `workflow-run`.

   Semantics:
   - `:template` contributions become synthetic user text messages
   - `:source` contributions preserve canonical conversation messages when the
     resolved value is already message-shaped, otherwise they become synthetic
     user text messages via deterministic stringification
   - author order is preserved exactly across contributions

   This is the shared single-turn materialization primitive: a single-prompt
   step's whole `:contributions` and one prompt-group's `:contributions`
   (task 226) both materialize through here."
  [workflow-run contributions]
  (some->> contributions
           (mapcat #(materialize-session-contribution workflow-run %))
           vec
           not-empty))

(defn materialize-step-session-conversation
  "Materialize canonical IR `:session :contributions` into ordered child-session
   conversation messages."
  [workflow-run step-id]
  (let [contributions (get-in (semantics/effective-step-def workflow-run step-id)
                              [:session :contributions])]
    (materialize-contributions-conversation workflow-run contributions)))

(defn- prompt-text-from-message
  [message]
  (when (= "user" (:role message))
    (let [content (:content message)]
      (cond
        (string? content)
        content

        (and (vector? content)
             (seq content)
             (every? #(= :text (:type %)) content))
        (apply str (map :text content))

        :else nil))))

(defn split-step-session-conversation
  "Split a materialized child-session conversation into canonical preloaded
   messages plus the final prompt text submitted through the normal prompt path.

   When the last materialized message is a user text message, it becomes the
   actual prompt submission and all prior messages preload the child session.
   Otherwise the whole conversation is preloaded and the execution prompt is the
   empty string so execution still routes through the canonical prompt path."
  [messages]
  (let [messages' (vec (or messages []))
        last-msg (peek messages')
        prompt (prompt-text-from-message last-msg)]
    (if (some? prompt)
      {:preloaded-messages (not-empty (pop messages'))
       :prompt prompt}
      {:preloaded-messages (not-empty messages')
       :prompt ""})))

(defn materialize-prompt-group-conversation
  "Materialize one normalized prompt-group's `:contributions` (task 226) into
   ordered child-session conversation messages.

   This is the per-group materialization entry point: the runtime derives the
   ordered prompt-queue from canonical IR (`ir/session-step-prompt-queue`) and
   materializes each group through here, reusing the same single-turn primitive.
   A length-1 unnamed-group queue reproduces today's single-prompt submission."
  [workflow-run prompt-group]
  (materialize-contributions-conversation workflow-run (:contributions prompt-group)))

(defn step-prompt
  [workflow-run step-id]
  (let [step-inputs (materialize-step-inputs workflow-run step-id)
        session-conversation (materialize-step-session-conversation workflow-run step-id)]
    {:step-inputs step-inputs
     :prompt (:prompt (split-step-session-conversation session-conversation))}))
