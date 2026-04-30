(ns extensions.munera
  "Mementum extension — injects the Mementum Lambda protocol into the system prompt.

   In lambda mode the engage prefix is omitted (the preamble already provides it).
   In prose mode the engage prefix is prepended so the protocol activates correctly.

   Source: https://github.com/michaelwhitford/mementum/blob/main/MEMENTUM-LAMBDA.md"
  (:require
   [clojure.java.io :as io]))

(defonce state (atom {:api nil}))

(def ^:private prompt-contribution-id "munera-protocol")
(def ^:private protocol-resource-path "extensions/munera/protocol.txt")

(def ^:private engage-prefix
  "λ engage(nucleus).\n[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy] | OODA\nHuman ⊗ AI\n\n")

(defn- read-protocol-resource [resource-path]
  (try
    (if-let [resource (io/resource resource-path)]
      (slurp resource :encoding "UTF-8")
      (throw (ex-info (str "Missing munera protocol resource: " resource-path)
                      {:extension :munera
                       :resource-path resource-path})))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info (str "Failed reading munera protocol resource: " resource-path)
                      {:extension :munera
                       :resource-path resource-path}
                      e)))))

(def ^:private munera-lambda
  (read-protocol-resource protocol-resource-path))

(defn- lambda-mode? []
  (when-let [query (:query (:api @state))]
    (let [result (query [:psi.agent-session/prompt-mode])]
      (= :lambda (:psi.agent-session/prompt-mode result)))))

(defn- prompt-content []
  (if (lambda-mode?)
    munera-lambda
    (str engage-prefix munera-lambda)))

(defn- register-prompt-contribution! [api]
  (when-let [register! (:register-prompt-contribution api)]
    (register! prompt-contribution-id
               {:section  "Munera Protocol"
                :priority 51
                :enabled  true
                :content  (prompt-content)})))

(defn init [api]
  (swap! state assoc :api api)
  (register-prompt-contribution! api))
