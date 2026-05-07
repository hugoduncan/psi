(ns psi.shared-config.user
  "User-global AgentSession configuration.

   Stored at: ~/.psi/agent/config.edn

   Shape:
   {:version 1
    :agent-session {:model-provider string?
                    :model-id string?
                    :thinking-level keyword?
                    :prompt-mode keyword?
                    :nucleus-prelude-override string?}}"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private current-version 1)

(def ^:private default-config
  {:version current-version
   :agent-session {}})

(defn user-config-file
  "Return the File for the user-global config."
  []
  (io/file (System/getProperty "user.home") ".psi" "agent" "config.edn"))

(defn read-config
  "Best-effort read. Returns default map on missing/invalid file."
  []
  (let [f (user-config-file)]
    (try
      (if (and (.exists f) (.isFile f))
        (let [v (edn/read-string (slurp f))]
          (if (map? v)
            (merge default-config v)
            default-config))
        default-config)
      (catch Exception _
        default-config))))

(defn- write-config!
  [cfg]
  (let [f      (user-config-file)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))
    (spit f (pr-str cfg))
    cfg))

(defn update-agent-session!
  "Merge `m` into :agent-session and write to disk."
  [m]
  (let [cfg (read-config)]
    (write-config! (update cfg :agent-session merge m))))
