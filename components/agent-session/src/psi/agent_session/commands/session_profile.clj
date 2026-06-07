(ns psi.agent-session.commands.session-profile
  "Session profile slash-command handling."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.shared-config.session-profiles :as session-profiles]))

(defn parse-profile-argument
  "Parse the tail of `/session-profile`.

   Returns {:action :show}, {:action :clear}, {:action :select :profile-name k},
   or {:action :error :message s}. Bare and EDN-style unqualified keywords are
   accepted; raw bare `clear` is the clear action before keyword normalization."
  [args]
  (let [trimmed (str/trim (or args ""))]
    (cond
      (str/blank? trimmed)
      {:action :show}

      (not= 1 (count (str/split trimmed #"\s+")))
      {:action :error
       :message "Usage: /session-profile OR /session-profile <profile-name> OR /session-profile clear"}

      (= "clear" trimmed)
      {:action :clear}

      :else
      (let [keyword-token? (str/starts-with? trimmed ":")
            token-text     (if keyword-token? (subs trimmed 1) trimmed)
            valid-token?   (session-profiles/valid-profile-name-token? token-text)
            parsed         (when valid-token?
                             (try
                               (if keyword-token?
                                 (edn/read-string trimmed)
                                 (keyword trimmed))
                               (catch Exception _ ::invalid)))]
        (if (and (keyword? parsed) (nil? (namespace parsed)))
          {:action :select :profile-name parsed}
          {:action :error
           :message (str "Invalid session profile token: " trimmed
                         ". Use a bare name such as planning or an EDN keyword such as :planning.")})))))

(defn- query-profile-state
  [ctx session-id]
  (session/query-in ctx session-id
                    [:psi.agent-session/session-profiles
                     :psi.agent-session/selected-session-profile
                     :psi.agent-session/model
                     :psi.agent-session/thinking-level
                     :psi.agent-session/speed-mode
                     :psi.agent-session/effort-override]))

(defn- profile-name-text
  [profile-name]
  (cond
    (keyword? profile-name) (subs (pr-str profile-name) 1)
    (string? profile-name) profile-name
    :else (pr-str profile-name)))

(defn- profile-line
  [profile]
  (let [name-text (profile-name-text (:name profile))]
    (if (:valid? profile)
      (str "  " name-text " — " (str/join ", " (:readable-settings profile)))
      (str "  " name-text " — unavailable: " (session-profiles/format-diagnostics profile)))))

(defn format-session-profiles
  [ctx session-id]
  (let [profiles (:psi.agent-session/session-profiles (query-profile-state ctx session-id))]
    (str "── Session profiles ──────────────────\n"
         (if (seq profiles)
           (str/join "\n" (map profile-line (vals profiles)))
           "  (none configured)")
         "\n───────────────────────────────────────")))

(defn- current-settings-lines
  [state]
  [(str "  Model   : " (or (some-> state :psi.agent-session/model :id) "none"))
   (str "  Thinking: " (name (or (:psi.agent-session/thinking-level state) :off)))
   (str "  Speed   : " (name (or (:psi.agent-session/speed-mode state) :normal)))
   (str "  Effort  : " (name (or (:psi.agent-session/effort-override state) :none)))])

(defn format-current-session-profile
  [ctx session-id]
  (let [state    (query-profile-state ctx session-id)
        selected (:psi.agent-session/selected-session-profile state)]
    (str "── Current session profile ───────────\n"
         (if selected
           (str "  Selected: " (name (:name selected)) "\n"
                "  Applied : " (str/join ", " (:readable-settings selected)) "\n")
           "  Selected: (none)\n")
         (str/join "\n" (current-settings-lines state))
         "\n───────────────────────────────────────")))

(defn- available-text
  [available]
  (if (seq available)
    (str/join ", " (map name available))
    "(none)"))

(defn- selection-error-message
  [{:keys [error profile profile-name available]}]
  (case error
    :unknown-profile
    (str "Unknown session profile: " (profile-name-text profile-name)
         ". Available: " (available-text available))

    :invalid-profile
    (str "Invalid session profile: " (profile-name-text (:name profile))
         ". " (session-profiles/format-diagnostics profile)
         ". Available: " (available-text available))

    "Session profile selection failed."))

(defn dispatch-command
  [ctx session-id trimmed]
  (let [args   (-> (str/replace trimmed #"^/session-profile\s*" "") str/trim)
        parsed (parse-profile-argument args)]
    (case (:action parsed)
      :show
      {:type :text :message (format-current-session-profile ctx session-id)}

      :clear
      (do
        (session/clear-session-profile-in! ctx session-id)
        {:type :text :message "✓ Session profile metadata cleared"})

      :select
      (let [profiles (:psi.agent-session/session-profiles (query-profile-state ctx session-id))
            result   (session-profiles/find-valid-profile profiles (:profile-name parsed))]
        (if (:ok? result)
          (let [applied (session/apply-session-profile-in! ctx session-id (:profile result))]
            {:type :text
             :message (str "✓ Session profile set to " (name (get-in applied [:selected-session-profile :name]))
                           " — " (str/join ", " (get-in applied [:selected-session-profile :readable-settings])))})
          {:type :text :message (selection-error-message result)}))

      :error
      {:type :text :message (:message parsed)})))
