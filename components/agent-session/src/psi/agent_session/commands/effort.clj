(ns psi.agent-session.commands.effort
  "Effort override slash-command handling."
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]))

(def ^:private valid-scopes
  #{:session :project :user})

(def ^:private canonical-effort-values
  [:low :medium :high :xhigh :none])

(defn- normalize-effort
  [s]
  (some-> s str/trim str/lower-case keyword))

(defn- normalize-scope
  [scope]
  (some-> scope str/trim str/lower-case keyword))

(defn- known-effort?
  [effort]
  (contains? (set canonical-effort-values) effort))

(defn- usage-message
  []
  "Usage: /effort OR /effort <low|medium|high|xhigh|none> [session|project|user]")

(defn- unknown-effort-message
  [input]
  (str "Unknown effort override: " input ". Allowed: low, medium, high, xhigh, none"))

(defn- unknown-scope-message
  [scope]
  (str "Unknown effort scope: " scope ". Allowed: session, project, user"))

(defn dispatch-command
  [ctx session-id trimmed]
  (let [args (-> (str/replace trimmed #"^/effort\s*" "") str/trim)]
    (if (str/blank? args)
      {:type :text
       :message (str "Current effort override: "
                     (name (or (:psi.agent-session/effort-override
                                (session/query-in ctx session-id [:psi.agent-session/effort-override]))
                               :none)))}
      (let [tokens (str/split args #"\s+")]
        (if-not (contains? #{1 2} (count tokens))
          {:type :text
           :message (usage-message)}
          (let [[effort-input scope-token] tokens
                effort (normalize-effort effort-input)
                scope  (normalize-scope scope-token)]
            (cond
              (not (known-effort? effort))
              {:type :text
               :message (unknown-effort-message effort-input)}

              (and scope-token (not (contains? valid-scopes scope)))
              {:type :text
               :message (unknown-scope-message scope-token)}

              :else
              (let [override (when-not (= :none effort) effort)
                    result   (session/set-effort-override-in! ctx session-id override (or scope :session))]
                {:type :text
                 :message (str "✓ Effort override set to "
                               (name (or (:effort-override result) :none))
                               (when scope
                                 (str " [" (name scope) "]")))}))))))))
