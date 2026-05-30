(ns psi.ai.providers.openai.reasoning)

(def thinking-level->effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(def reasoning-part-types
  #{"reasoning"
    "reasoning_text"
    "reasoning_content"
    "reasoning_summary"
    "summary"
    "summary_text"})

(defn reasoning-effort
  "Return provider reasoning effort string for MODEL/OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (when-let [effort (get thinking-level->effort
                           (:thinking-level options)
                           "medium")]
      (or (get {:low "low" :medium "medium" :high "high" :xhigh "high"}
               (:effort-override options))
          effort))))

(defn chat-template-kwargs
  "Return OpenAI-compatible chat_template_kwargs overrides for MODEL/OPTIONS,
   or nil when no override should be sent.

   Some local OpenAI-compatible servers expose hidden reasoning through a
   nonstandard chat_template_kwargs.enable_thinking flag. Psi projects the
   canonical :thinking-level control onto that local-only transport extension
   when thinking is explicitly off."
  [model options]
  (when (and (= :local (:locality model))
             (= :off (:thinking-level options)))
    {:enable_thinking false}))
