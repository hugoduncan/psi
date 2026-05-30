(ns psi.agent-session.commands.speed
  "Speed mode slash-command handling."
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]))

(def ^:private valid-scopes
  #{:session :project :user})

(def ^:private canonical-speed-modes
  [:normal :fast])

(defn- normalize-speed-mode
  [s]
  (some-> s str/trim str/lower-case keyword))

(defn- normalize-scope
  [scope]
  (some-> scope str/trim str/lower-case keyword))

(defn- known-speed-mode?
  [mode]
  (contains? (set canonical-speed-modes) mode))

(defn- usage-message
  []
  "Usage: /speed OR /speed <normal|fast> [session|project|user]")

(defn- unknown-speed-mode-message
  [input]
  (str "Unknown speed mode: " input ". Allowed: normal, fast"))

(defn- unknown-scope-message
  [scope]
  (str "Unknown speed scope: " scope ". Allowed: session, project, user"))

(defn dispatch-command
  [ctx session-id trimmed]
  (let [args (-> (str/replace trimmed #"^/speed\s*" "") str/trim)]
    (if (str/blank? args)
      {:type :text
       :message (str "Current speed mode: "
                     (name (or (:psi.agent-session/speed-mode
                                (session/query-in ctx session-id [:psi.agent-session/speed-mode]))
                               :normal)))}
      (let [tokens (str/split args #"\s+")]
        (if-not (contains? #{1 2} (count tokens))
          {:type :text
           :message (usage-message)}
          (let [[mode-input scope-token] tokens
                mode  (normalize-speed-mode mode-input)
                scope (normalize-scope scope-token)]
            (cond
              (not (known-speed-mode? mode))
              {:type :text
               :message (unknown-speed-mode-message mode-input)}

              (and scope-token (not (contains? valid-scopes scope)))
              {:type :text
               :message (unknown-scope-message scope-token)}

              :else
              (let [result (session/set-speed-mode-in! ctx session-id mode (or scope :session))]
                {:type :text
                 :message (str "✓ Speed mode set to "
                               (name (or (:speed-mode result) :normal))
                               (when scope
                                 (str " [" (name scope) "]")))}))))))))
