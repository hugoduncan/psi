(ns psi.metrics.persistence
  "Load/save metrics EDN with atomic writes and write-coalescing.

   Atomic writes: spit to a .tmp file then java.nio.file.Files/move with
   ATOMIC_MOVE + REPLACE_EXISTING to prevent partial-read corruption.

   Write-coalescing: a dirty-flag + CAS gate ensures at most one thread
   writes at a time, and re-dirtied state triggers a follow-up write."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [psi.metrics.counters :as counters]
   [psi.metrics.schema :as schema])
  (:import
   (java.nio.file Files StandardCopyOption)))

;;; File paths

(defn metrics-file
  "Return a java.io.File for the metrics EDN file under worktree-path."
  [worktree-path]
  (io/file worktree-path ".psi" "metrics.edn"))

(defn- tmp-file
  "Return the temp file used for atomic writes."
  [worktree-path]
  (io/file worktree-path ".psi" ".metrics.edn.tmp"))

;;; Load

(defn load-metrics
  "Load metrics from worktree-path/.psi/metrics.edn.
   Returns empty metrics when:
   - worktree-path is nil (no worktree)
   - the file does not exist
   - the file contains invalid EDN
   - the file does not conform to metrics-schema (logs warning)"
  [worktree-path]
  (when worktree-path
    (let [f (metrics-file worktree-path)]
      (if (.exists f)
        (try
          (let [data (edn/read-string (slurp f))]
            (if (schema/valid? data)
              data
              (do
                (println (str "WARN [psi/metrics] metrics.edn failed schema validation — "
                              "starting with empty counters. File preserved at: " (.getAbsolutePath f)))
                nil)))
          (catch Exception e
            (println (str "WARN [psi/metrics] failed to read metrics.edn: "
                          (ex-message e) " — starting with empty counters"))
            nil))
        nil))))

;;; Save

(defn save-metrics!
  "Atomically write metrics-map to worktree-path/.psi/metrics.edn.
   Creates the .psi/ directory if absent.
   No-ops when worktree-path is nil."
  [worktree-path metrics-map]
  (when worktree-path
    (let [f   (metrics-file worktree-path)
          tmp (tmp-file worktree-path)]
      (io/make-parents f)
      (spit tmp (pr-str metrics-map))
      (Files/move
       (.toPath ^java.io.File tmp)
       (.toPath ^java.io.File f)
       (into-array StandardCopyOption
                   [StandardCopyOption/ATOMIC_MOVE
                    StandardCopyOption/REPLACE_EXISTING])))))

;;; Write-coalescing gate

(defn maybe-persist!
  "Persist the current metrics snapshot if dirty, using a CAS gate to
   ensure at most one thread writes at a time.

   store-atom shape:
     {:metrics {...} :worktree-path \"...\" :dirty? true/false ...}

   writing?-atom is a separate boolean atom used as the CAS gate.

   After each write the writer re-checks :dirty? and loops if re-dirtied
   by concurrent events, coalescing rapid-fire mutations into minimal I/O."
  [store-atom writing?-atom]
  (when (compare-and-set! writing?-atom false true)
    (try
      (loop []
        (let [{:keys [metrics worktree-path dirty?]} @store-atom]
          (when dirty?
            ;; Clear dirty flag atomically before writing — any concurrent
            ;; mutation after this swap! will re-set :dirty? to true.
            (swap! store-atom assoc :dirty? false)
            (save-metrics! worktree-path metrics)
            (recur))))
      (finally
        (reset! writing?-atom false)))))

(defn mark-dirty-and-persist!
  "Mark the store as dirty and trigger a coalesced persist."
  [store-atom writing?-atom]
  (swap! store-atom assoc :dirty? true)
  (maybe-persist! store-atom writing?-atom))

(defn empty-store
  "Return the initial store map for a given worktree-path."
  [worktree-path initial-metrics]
  {:metrics          (or initial-metrics (counters/empty-metrics))
   :worktree-path    worktree-path
   :session-usage-cache {}
   :dirty?           false})
