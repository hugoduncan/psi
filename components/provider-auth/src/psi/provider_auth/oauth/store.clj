(ns psi.provider-auth.oauth.store
  "Credential storage for API keys and OAuth tokens.

   Nullable pattern: `create-store` for production (file-backed with locking),
   `create-null-store` for tests (in-memory, no disk).

   Both return the same map interface — all fns take the store as first arg.

   Credential shapes:
     API key:  {:type :api-key  :key \"sk-...\"}
     OAuth:    {:type :oauth    :refresh \"...\" :access \"...\"
                :expires <epoch-ms> :metadata {...}}

   File locking: production store uses java.nio.channels.FileLock for
   cross-process coordination (e.g. concurrent token refresh)."
  (:require [cheshire.core :as json])
  (:import [java.io File RandomAccessFile]
           [java.nio.channels FileLock OverlappingFileLockException]))

;;; File locking

(defn- with-file-lock
  "Execute `f` while holding an exclusive lock on `lock-path`.
   Creates the lock file if it doesn't exist. `f` receives no args.
   Returns the result of `f`."
  [^String lock-path f]
  (let [lock-file (File. lock-path)
        _         (when-let [parent (.getParentFile lock-file)]
                    (when-not (.exists parent) (.mkdirs parent)))
        raf       (RandomAccessFile. lock-file "rw")]
    (try
      (let [channel (.getChannel raf)
            lock    (loop [attempts 0]
                      (when (< attempts 100)
                        (or (try (.lock channel)
                                 (catch OverlappingFileLockException _
                                   (Thread/sleep 100)
                                   nil))
                            (recur (inc attempts)))))]
        (when-not lock
          (throw (ex-info "Failed to acquire file lock" {:path lock-path})))
        (try
          (f)
          (finally
            (.release ^FileLock lock))))
      (finally
        (.close raf)))))

;;; File helpers

(defn- ensure-parent-dir
  "Create parent directories for `path` with 0700 permissions."
  [^String path]
  (let [dir (.getParentFile (File. path))]
    (when (and dir (not (.exists dir)))
      (.mkdirs dir))))

(defn- coerce-credential
  "Ensure :type is a keyword after JSON round-trip (\"oauth\" → :oauth)."
  [cred]
  (if (and (map? cred) (:type cred))
    (update cred :type keyword)
    cred))

(defn- read-json-file
  "Read and parse a JSON file. Returns {} on missing/invalid.
   Coerces :type fields to keywords."
  [^String path]
  (try
    (if (.exists (File. path))
      (let [raw (json/parse-string (slurp path) true)]
        (into {} (map (fn [[k v]] [k (coerce-credential v)]) raw)))
      {})
    (catch Exception _e {})))

(defn- write-json-file!
  "Write `data` as JSON to `path` with 0600 permissions."
  [^String path data]
  (ensure-parent-dir path)
  (spit path (json/generate-string data {:pretty true}))
  (let [f (File. path)]
    (.setReadable f false false)
    (.setReadable f true true)
    (.setWritable f false false)
    (.setWritable f true true)))

;;; Store operations (work on both file and in-memory stores)

(defn get-credential
  "Get credential for a provider. Returns nil if not found."
  [store provider]
  (get @(:data store) (keyword provider)))

(defn set-credential!
  "Set credential for a provider and persist."
  [store provider credential]
  (let [k (keyword provider)]
    (swap! (:data store) assoc k credential)
    (when-let [persist! (:persist! store)]
      (persist! @(:data store)))))

(defn remove-credential!
  "Remove credential for a provider and persist."
  [store provider]
  (let [k (keyword provider)]
    (swap! (:data store) dissoc k)
    (when-let [persist! (:persist! store)]
      (persist! @(:data store)))))

(defn list-providers
  "List all providers with stored credentials."
  [store]
  (keys @(:data store)))

(defn has-credential?
  "Check if any credential exists for a provider."
  [store provider]
  (contains? @(:data store) (keyword provider)))

(defn reload!
  "Reload credentials from backing storage."
  [store]
  (when-let [load-fn (:load! store)]
    (reset! (:data store) (load-fn))))

;;; Locked refresh — cross-process safe

(defn with-locked-refresh!
  "Execute `refresh-fn` while holding the store's file lock.
   Re-reads credentials from disk before refreshing (another process may
   have already refreshed). Persists the result if refresh-fn returns
   a new credential.

   `refresh-fn` receives the current credential and returns the updated
   credential, or nil to skip the update.

   Returns the result of `refresh-fn`. Only available on file-backed stores."
  [store provider refresh-fn]
  (if-let [lock-path (:lock-path store)]
    (with-file-lock lock-path
      (fn []
        ;; Re-read from disk inside the lock
        (let [fresh-data ((:load! store))
              k          (keyword provider)
              current    (get fresh-data k)
              result     (refresh-fn current)]
          (when result
            (let [updated (assoc fresh-data k result)]
              ((:persist! store) updated)
              (reset! (:data store) updated)))
          result)))
    ;; No lock path (null store) — just run the fn directly
    (let [current (get @(:data store) (keyword provider))
          result  (refresh-fn current)]
      (when result
        (swap! (:data store) assoc (keyword provider) result)
        (when-let [persist! (:persist! store)]
          (persist! @(:data store))))
      result)))

;;; API key resolution

(def ^:private env-var-map
  "Map of provider keyword → environment variable name."
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :groq      "GROQ_API_KEY"
   :xai       "XAI_API_KEY"})

(defn- env-api-key
  "Get API key from environment variable for a provider."
  [provider]
  (when-let [var-name (get env-var-map (keyword provider))]
    (System/getenv var-name)))

(defn get-api-key
  "Resolve API key for a provider using the priority chain:
     1. Runtime override
     2. Stored API key credential
     3. Stored OAuth credential (access token)
     4. Environment variable
     5. Fallback resolver

   Does NOT auto-refresh expired OAuth tokens — caller should check
   `oauth-expired?` and refresh before calling this."
  [store provider]
  (let [k (keyword provider)]
    (or
     ;; 1. Runtime override
     (get @(:runtime-overrides store) k)
     ;; 2–3. Stored credential
     (let [cred (get @(:data store) k)]
       (case (:type cred)
         :api-key (:key cred)
         :oauth   (:access cred)
         nil))
     ;; 4. Environment variable
     (env-api-key k)
     ;; 5. Fallback resolver
     (when-let [fallback (:fallback-resolver store)]
       (fallback k)))))

(defn has-auth?
  "Quick check: is any form of auth configured for a provider?
   Does NOT refresh tokens."
  [store provider]
  (boolean (get-api-key store provider)))

(defn oauth-expired?
  "Check if an OAuth credential's access token is expired."
  [store provider]
  (let [cred (get-credential store provider)]
    (and (= :oauth (:type cred))
         (>= (System/currentTimeMillis) (:expires cred 0)))))

(defn set-runtime-override!
  "Set a runtime API key override (not persisted). Used for --api-key flag."
  [store provider api-key]
  (swap! (:runtime-overrides store) assoc (keyword provider) api-key))

(defn remove-runtime-override!
  "Remove a runtime API key override."
  [store provider]
  (swap! (:runtime-overrides store) dissoc (keyword provider)))

;;; Factory: file-backed (production)

(defn create-store
  "Create a file-backed credential store with cross-process locking.

   Options:
     :path — path to auth.json (default ~/.psi/agent/auth.json)"
  ([] (create-store {}))
  ([{:keys [path]}]
   (let [auth-path (or path
                       (str (System/getProperty "user.home")
                            "/.psi/agent/auth.json"))
         lock-path (str auth-path ".lock")
         load-fn   #(read-json-file auth-path)
         data      (atom (load-fn))]
     {:data              data
      :runtime-overrides (atom {})
      :fallback-resolver nil
      :path              auth-path
      :lock-path         lock-path
      :load!             load-fn
      :persist!          (fn [d] (write-json-file! auth-path d))})))

;;; Factory: in-memory (tests — Nullable pattern)

(defn create-null-store
  "Create an in-memory credential store for testing. No disk I/O, no locking.

   Optionally seed with initial credentials:
     (create-null-store {:anthropic {:type :api-key :key \"sk-test\"}})"
  ([] (create-null-store {}))
  ([initial-data]
   (let [persisted (atom nil)]
     {:data              (atom initial-data)
      :runtime-overrides (atom {})
      :fallback-resolver nil
      :path              nil
      :lock-path         nil
      :load!             nil
      :persist!          (fn [d] (reset! persisted d))
      ;; Test-only: inspect what was persisted
      :persisted         persisted})))
