(ns psi.shared-config.resolution
  "Unified config resolution: system < user < project-shared < project-local.

   Precedence (highest wins):
     session        — runtime overrides held in session state (not persisted here)
     project-local  — <cwd>/.psi/project.local.edn
     project-shared — <cwd>/.psi/project.edn
     user           — ~/.psi/agent/config.edn
     system         — compiled-in defaults

   Callers should use `resolve-config` to obtain a merged view, then
   use the typed accessor functions to extract individual values."
  (:require
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.user :as user-cfg]))

(def ^:private system-defaults
  {:model-provider nil
   :model-id nil
   :thinking-level :off
   :prompt-mode :lambda
   :nucleus-prelude-override nil})

(defn agent-session-map
  "Extract the :agent-session sub-map, nil-safe."
  [cfg]
  (or (:agent-session cfg) {}))

(defn resolve-config
  "Return a merged config map for `cwd`.

   Merges in precedence order (last wins):
     system-defaults < user-config < layered-project-prefs

   Layered project prefs are themselves resolved as:
     shared project.edn < local project.local.edn

   The returned map has flat keys matching `system-defaults`."
  [cwd]
  (let [user    (agent-session-map (user-cfg/read-config))
        project (agent-session-map (project-prefs/read-preferences cwd))]
    (merge system-defaults user project)))

(defn resolved-model
  "Return {:provider p :id id} when both fields are present, else nil."
  [cfg]
  (let [provider (:model-provider cfg)
        id       (:model-id cfg)]
    (when (and (string? provider) (string? id))
      {:provider (keyword provider) :id id})))

(defn resolved-thinking-level
  "Return the resolved thinking-level keyword, or :off."
  [cfg]
  (let [v (:thinking-level cfg)]
    (if (keyword? v) v :off)))

(defn resolved-speed-mode
  "Return {:present? true :value mode} for valid configured speed mode.
   Missing or invalid values return nil. Explicit :normal is preserved so it can
   mask lower-precedence :fast values while still shaping provider-default requests."
  [cfg]
  (when (contains? cfg :speed-mode)
    (let [v (:speed-mode cfg)]
      (when (#{:normal :fast} v)
        {:present? true :value v}))))

(defn resolved-prompt-mode
  "Return :lambda or :prose."
  [cfg]
  (let [v (:prompt-mode cfg)]
    (if (#{:lambda :prose} v) v :lambda)))

(defn resolved-nucleus-prelude-override
  "Return override string or nil."
  [cfg]
  (let [v (:nucleus-prelude-override cfg)]
    (when (string? v) v)))
