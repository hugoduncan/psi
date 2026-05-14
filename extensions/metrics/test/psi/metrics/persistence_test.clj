(ns psi.metrics.persistence-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [psi.metrics.counters :as counters]
   [psi.metrics.persistence :as persist]))

;;; Helpers

(defn- with-tmp-dir
  "Call f with a path string pointing to a fresh temp directory.
   Cleans up afterwards."
  [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "psi-metrics-test"
             (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (str dir))
      (finally
        ;; Best-effort cleanup.
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (.delete file))))))

(defn- sample-metrics []
  {:tools      {"read" {:invocations 5 :errors 1 :error-reasons {"timeout" 1}}}
   :workflows  {}
   :commands   {}
   :operations {}
   :tokens     {"claude-3" {:input 1000 :output 200 :cache-read 500 :cache-write 50}}
   :updated-at "2026-05-14T10:00:00Z"})

;;; load-metrics

(deftest load-metrics-returns-nil-for-nil-worktree-test
  ;; No worktree → no persistence → nil returned.
  (is (nil? (persist/load-metrics nil))))

(deftest load-metrics-returns-nil-for-missing-file-test
  ;; File does not exist → nil (caller uses empty-metrics).
  (with-tmp-dir
    (fn [dir]
      (is (nil? (persist/load-metrics dir))))))

(deftest load-metrics-round-trips-valid-data-test
  ;; save-metrics! followed by load-metrics returns the original map.
  (with-tmp-dir
    (fn [dir]
      (let [m (sample-metrics)]
        (persist/save-metrics! dir m)
        (is (= m (persist/load-metrics dir)))))))

(deftest load-metrics-returns-nil-for-corrupt-edn-test
  ;; A file with invalid EDN is treated as corrupt → nil returned.
  (with-tmp-dir
    (fn [dir]
      (let [f (persist/metrics-file dir)]
        (io/make-parents f)
        (spit f "{{not valid edn")
        (is (nil? (persist/load-metrics dir)))))))

(deftest load-metrics-returns-nil-for-schema-invalid-data-test
  ;; Valid EDN that does not conform to metrics-schema → nil returned.
  (with-tmp-dir
    (fn [dir]
      (let [f (persist/metrics-file dir)]
        (io/make-parents f)
        (spit f (pr-str {:unexpected "shape"}))
        (is (nil? (persist/load-metrics dir)))))))

;;; save-metrics!

(deftest save-metrics-creates-psi-directory-test
  ;; save-metrics! creates the .psi/ directory if absent.
  (with-tmp-dir
    (fn [dir]
      (let [psi-dir (io/file dir ".psi")]
        (is (not (.exists psi-dir)))
        (persist/save-metrics! dir (sample-metrics))
        (is (.exists psi-dir))))))

(deftest save-metrics-no-op-for-nil-worktree-test
  ;; save-metrics! is a no-op when worktree-path is nil (no exception).
  (is (nil? (persist/save-metrics! nil (sample-metrics)))))

;;; maybe-persist! write-coalescing

(deftest maybe-persist-coalesces-concurrent-mutations-test
  ;; When writing? is true, a second call to maybe-persist! is a no-op (does not
  ;; attempt a concurrent write). After the writer finishes, writing? is reset.
  (with-tmp-dir
    (fn [dir]
      (let [store    (atom (persist/empty-store dir (counters/empty-metrics)))
            writing? (atom false)]
        ;; Mark dirty and persist once.
        (persist/mark-dirty-and-persist! store writing?)
        ;; After completion, writing? is false and the file exists.
        (is (false? @writing?))
        (is (.exists (persist/metrics-file dir)))))))

(deftest mark-dirty-and-persist-writes-current-state-test
  ;; After mark-dirty-and-persist!, the file on disk contains the current metrics.
  (with-tmp-dir
    (fn [dir]
      (let [m        (counters/inc-tool-invocation (counters/empty-metrics) "bash")
            store    (atom (assoc (persist/empty-store dir nil) :metrics m))
            writing? (atom false)]
        (persist/mark-dirty-and-persist! store writing?)
        (let [loaded (persist/load-metrics dir)]
          (is (= 1 (get-in loaded [:tools "bash" :invocations]))))))))

;;; empty-store

(deftest empty-store-shape-test
  ;; empty-store returns a map with the expected keys.
  (let [s (persist/empty-store "/tmp/test" nil)]
    (is (= "/tmp/test" (:worktree-path s)))
    (is (map? (:metrics s)))
    (is (= {} (:session-usage-cache s)))
    (is (false? (:dirty? s)))))
