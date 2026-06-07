(ns psi.session-profile.selection
  "Pure selection helpers for already-resolved session-profile maps.

   This namespace operates only on resolved profile records or persisted
   session-profile snapshots. It deliberately does not read config or consult
   the model registry, so deterministic workflow step resolution can select
   profiles from an immutable run snapshot without depending on config IO."
  (:require
   [psi.session-profile.names :as profile-names]))

(defn- diagnostic
  [field reason message]
  {:field field :reason reason :message message})

(defn- reserved-profile-record
  [profile-name]
  {:name profile-name
   :status :invalid
   :valid? false
   :settings {}
   :readable-settings []
   :diagnostics [(diagnostic :name :reserved-profile-name
                             "profile name is reserved by /session-profile clear")]})

(defn- available-profile-names
  [profiles]
  (->> profiles
       (keep (fn [[profile-name profile]]
               (when (:valid? profile) profile-name)))
       (sort-by pr-str)
       vec))

(defn find-valid-profile
  "Return the valid resolved profile named `profile-name`, or an error map.

   `profiles` must be an already-resolved profile map, such as the `:profiles`
   field stored in a workflow run's `:session-profile-snapshot` or exposed by
   session-profile resolvers. The helper never reads mutable config."
  [profiles profile-name]
  (let [available (available-profile-names profiles)]
    (cond
      (profile-names/reserved-profile-name? profile-name)
      {:ok? false
       :error :invalid-profile
       :profile (or (get profiles profile-name)
                    (reserved-profile-record profile-name))
       :available available}

      (contains? profiles profile-name)
      (let [profile (get profiles profile-name)]
        (if (:valid? profile)
          {:ok? true :profile profile}
          {:ok? false
           :error :invalid-profile
           :profile profile
           :available available}))

      :else
      {:ok? false
       :error :unknown-profile
       :profile-name profile-name
       :available available})))
