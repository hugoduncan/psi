(ns psi.agent-session.conversation
  "Compatibility wrapper over the lower turn-runtime conversation assembly.

   Authoritative implementation now lives in `psi.turn-runtime.conversation`."
  (:require
   [psi.turn-runtime.conversation :as conversation]))

(defn agent-messages->ai-conversation
  [system-prompt-str messages agent-tools opts]
  (conversation/agent-messages->ai-conversation system-prompt-str messages agent-tools opts))
