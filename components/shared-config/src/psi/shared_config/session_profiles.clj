(ns psi.shared-config.session-profiles
  "Session profile config resolution and validation.

   Profiles are resolved from the existing user/project config files only at
   explicit call sites. This namespace owns the profile-specific deep merge and
   validation rules without changing ordinary :agent-session config resolution."
  (:require
   [clojure.string :as str]
   [psi.ai.model-registry :as model-registry]
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.user :as user-config]))

(def supported-fields
  "The only profile fields that affect session/profile materialization."
  #{:model-provider :model-id :thinking-level :speed-mode :effort-override})

(def reserved-profile-names
  "Profile names that cannot be selected because they are command actions."
  #{:clear})

(def canonical-thinking-levels
  #{:off :minimal :low :medium :high :xhigh})

(def canonical-speed-modes
  #{:normal :fast})

(def canonical-effort-overrides
  #{:low :medium :high :xhigh})

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (deep-merge a b)
             b))
         maps))

(defn- session-profiles-in
  [cfg]
  (let [profiles (get-in cfg [:agent-session :session-profiles])]
    (if (map? profiles) profiles {})))

(defn effective-profile-definitions
  "Return raw effective profile definitions for `cwd`.

   Only `:agent-session :session-profiles` is deep-merged, with precedence:
   user < project-shared < project-local. This deliberately does not call or
   alter the ordinary `resolve-config` flat merge path."
  [cwd]
  (deep-merge (session-profiles-in (user-config/read-config))
              (session-profiles-in (or (project-prefs/read-shared-preferences cwd) {}))
              (session-profiles-in (or (project-prefs/read-local-preferences cwd) {}))))

(defn- diagnostic
  [field reason message]
  {:field field :reason reason :message message})

(defn- normalize-provider
  [provider]
  (cond
    (keyword? provider) provider
    (string? provider) (keyword provider)
    :else nil))

(defn- resolve-model-setting
  [profile]
  (let [has-provider? (contains? profile :model-provider)
        has-id?       (contains? profile :model-id)
        provider      (:model-provider profile)
        id            (:model-id profile)]
    (cond
      (and (not has-provider?) (not has-id?))
      {:settings {} :diagnostics []}

      (not= has-provider? has-id?)
      {:settings {}
       :diagnostics [(diagnostic :model :incomplete-model-identity
                                 "model-provider and model-id must be supplied together")]}

      (not (and (string? provider) (string? id)))
      {:settings {}
       :diagnostics [(diagnostic :model :invalid-model-identity
                                 "model-provider and model-id must be strings")]}

      :else
      (let [provider-kw (normalize-provider provider)]
        (if-let [model (model-registry/find-model provider-kw id)]
          {:settings {:model (assoc model
                                    :provider (name (:provider model))
                                    :reasoning (boolean (:supports-reasoning model)))}
           :diagnostics []}
          {:settings {}
           :diagnostics [(diagnostic :model :unknown-model
                                     (str "unknown model " provider "/" id))]})))))

(defn- resolve-thinking-setting
  [profile]
  (if-not (contains? profile :thinking-level)
    {:settings {} :diagnostics []}
    (let [level (:thinking-level profile)]
      (if (contains? canonical-thinking-levels level)
        {:settings {:thinking-level level} :diagnostics []}
        {:settings {}
         :diagnostics [(diagnostic :thinking-level :invalid-thinking-level
                                   "thinking-level must be one of :off, :minimal, :low, :medium, :high, or :xhigh")]}))))

(defn- resolve-speed-setting
  [profile]
  (if-not (contains? profile :speed-mode)
    {:settings {} :diagnostics []}
    (let [mode (:speed-mode profile)]
      (if (contains? canonical-speed-modes mode)
        {:settings {:speed-mode mode} :diagnostics []}
        {:settings {}
         :diagnostics [(diagnostic :speed-mode :invalid-speed-mode
                                   "speed-mode must be :normal or :fast")]}))))

(defn- resolve-effort-setting
  [profile]
  (if-not (contains? profile :effort-override)
    {:settings {} :diagnostics []}
    (let [effort (:effort-override profile)]
      (if (or (nil? effort) (contains? canonical-effort-overrides effort))
        {:settings {:effort-override effort} :diagnostics []}
        {:settings {}
         :diagnostics [(diagnostic :effort-override :invalid-effort-override
                                   "effort-override must be nil, :low, :medium, :high, or :xhigh")]}))))

(defn- readable-model
  [model]
  (str (name (:provider model)) "/" (:id model)))

(defn readable-settings
  "Return deterministic user-facing setting fragments for resolved `settings`."
  [settings]
  (cond-> []
    (contains? settings :model)
    (conj (str "model " (readable-model (:model settings))))

    (contains? settings :thinking-level)
    (conj (str "thinking " (name (:thinking-level settings))))

    (contains? settings :speed-mode)
    (conj (str "speed " (name (:speed-mode settings))))

    (contains? settings :effort-override)
    (conj (str "effort " (name (or (:effort-override settings) :none))))))

(defn resolve-profile
  "Resolve and validate one profile definition.

   Returns a map with `:valid?`, `:status`, `:settings`, `:readable-settings`,
   `:diagnostics`, and ignored key metadata. Invalid profiles expose no
   `:settings` so application callers cannot accidentally apply a partial
   profile."
  [profile-name profile]
  (let [profile-map      (if (map? profile) profile {})
        supported        (select-keys profile-map supported-fields)
        ignored-keys     (->> (keys profile-map)
                              (remove supported-fields)
                              sort
                              vec)
        name-diagnostics (cond
                           (not (keyword? profile-name))
                           [(diagnostic :name :invalid-profile-name
                                        "profile name must be a keyword")]

                           (contains? reserved-profile-names profile-name)
                           [(diagnostic :name :reserved-profile-name
                                        "profile name is reserved by /session-profile clear")]

                           :else [])
        resolved-parts   [(resolve-model-setting supported)
                          (resolve-thinking-setting supported)
                          (resolve-speed-setting supported)
                          (resolve-effort-setting supported)]
        candidate        (apply merge (map :settings resolved-parts))
        diagnostics      (vec (concat name-diagnostics
                                      (mapcat :diagnostics resolved-parts)
                                      (when (and (empty? name-diagnostics)
                                                 (empty? (mapcat :diagnostics resolved-parts))
                                                 (empty? candidate))
                                        [(diagnostic :settings :no-concrete-settings
                                                     "profile has no supported concrete settings")])))]
    (if (seq diagnostics)
      {:name profile-name
       :status :invalid
       :valid? false
       :settings {}
       :readable-settings []
       :diagnostics diagnostics
       :ignored-keys ignored-keys}
      {:name profile-name
       :status :valid
       :valid? true
       :settings candidate
       :readable-settings (readable-settings candidate)
       :diagnostics []
       :ignored-keys ignored-keys})))

(defn resolve-profiles
  "Resolve a raw profile definition map into deterministic profile records."
  [profile-definitions]
  (into (sorted-map)
        (map (fn [[profile-name profile]]
               [profile-name (resolve-profile profile-name profile)]))
        profile-definitions))

(defn effective-profiles
  "Read and resolve effective session profiles for `cwd`."
  [cwd]
  (resolve-profiles (effective-profile-definitions cwd)))

(defn- snapshot-profile
  [profile]
  (dissoc profile :ignored-keys))

(defn profile-snapshot
  "Build a self-contained snapshot shape for workflow/run consumers.

   The snapshot contains only resolved settings and diagnostics. Unknown profile
   keys from config are omitted so workflow runs never persist ignored mutable
   config data."
  [cwd]
  (let [profiles (update-vals (effective-profiles cwd) snapshot-profile)]
    {:profiles profiles
     :valid-profile-names (->> profiles
                               (keep (fn [[profile-name profile]]
                                       (when (:valid? profile) profile-name)))
                               vec)
     :invalid-profile-names (->> profiles
                                 (keep (fn [[profile-name profile]]
                                         (when-not (:valid? profile) profile-name)))
                                 vec)}))

(comment
  (resolve-profile :coding {:model-provider "openai" :model-id "gpt-5.5"}))

(defn format-diagnostics
  "Return a terse deterministic diagnostic string for a resolved profile."
  [profile]
  (str/join "; " (map :message (:diagnostics profile))))

(defn find-valid-profile
  "Return the valid resolved profile named `profile-name`, or an error map."
  [profiles profile-name]
  (let [available (->> profiles (keep (fn [[n p]] (when (:valid? p) n))) sort vec)]
    (if-let [profile (get profiles profile-name)]
      (if (:valid? profile)
        {:ok? true :profile profile}
        {:ok? false
         :error :invalid-profile
         :profile profile
         :available available})
      {:ok? false
       :error :unknown-profile
       :profile-name profile-name
       :available available})))

(comment
  (effective-profiles (System/getProperty "user.dir")))