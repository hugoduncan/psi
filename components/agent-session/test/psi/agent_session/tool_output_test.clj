(ns psi.agent-session.tool-output-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tool-output :as tool-output]))

;;; effective-policy tests

(deftest effective-policy-defaults-test
  ;; Verifies that defaults are returned when no overrides are provided
  (testing "returns defaults when no overrides exist"
    (is (= {:max-lines 1000 :max-bytes 25600}
           (tool-output/effective-policy {} "read"))))

  (testing "returns defaults when tool has no override entry"
    (is (= {:max-lines 1000 :max-bytes 25600}
           (tool-output/effective-policy {"bash" {:max-lines 500}} "read")))))

(deftest effective-policy-overrides-test
  ;; Verifies that per-tool overrides are merged correctly
  (testing "overrides both fields"
    (is (= {:max-lines 100 :max-bytes 5000}
           (tool-output/effective-policy {"read" {:max-lines 100 :max-bytes 5000}} "read"))))

  (testing "overrides only max-lines, max-bytes falls through to default"
    (is (= {:max-lines 100 :max-bytes 25600}
           (tool-output/effective-policy {"read" {:max-lines 100}} "read"))))

  (testing "overrides only max-bytes, max-lines falls through to default"
    (is (= {:max-lines 1000 :max-bytes 5000}
           (tool-output/effective-policy {"read" {:max-bytes 5000}} "read"))))

  (testing "nil override values fall through to defaults"
    (is (= {:max-lines 1000 :max-bytes 25600}
           (tool-output/effective-policy {"read" {:max-lines nil :max-bytes nil}} "read")))))

;;; head-truncate tests

(deftest head-truncate-within-limits-test
  ;; Verifies no truncation when content is within limits
  (testing "content within limits returns unchanged"
    (let [text   "line1\nline2\nline3"
          result (tool-output/head-truncate text {:max-lines 10 :max-bytes 1000})]
      (is (= text (:content result)))
      (is (false? (:truncated result)))
      (is (= :none (:truncated-by result)))
      (is (= 3 (:total-lines result)))
      (is (= 3 (:output-lines result)))
      (is (false? (:first-line-exceeds-limit result))))))

(deftest head-truncate-empty-text-test
  ;; Verifies empty text is handled correctly
  (testing "empty text"
    (let [result (tool-output/head-truncate "" {:max-lines 10 :max-bytes 1000})]
      (is (= "" (:content result)))
      (is (false? (:truncated result)))
      (is (= :none (:truncated-by result))))))

(deftest head-truncate-by-lines-test
  ;; Verifies line-based truncation
  (testing "truncates by line count"
    (let [text   (str/join "\n" (map #(str "line" %) (range 1 11)))
          result (tool-output/head-truncate text {:max-lines 3 :max-bytes 100000})]
      (is (= "line1\nline2\nline3" (:content result)))
      (is (true? (:truncated result)))
      (is (= :lines (:truncated-by result)))
      (is (= 10 (:total-lines result)))
      (is (= 3 (:output-lines result))))))

(deftest head-truncate-by-bytes-test
  ;; Verifies byte-based truncation
  (testing "truncates by byte count"
    (let [text   "short\nmedium line\nthis is a longer line"
          result (tool-output/head-truncate text {:max-lines 1000 :max-bytes 16})]
      ;; "short\nmedium line" = 16 bytes, "short" = 5 bytes
      ;; "short" alone = 5 bytes, fits
      ;; "short\nmedium line" = 5 + 1 + 11 = 17 bytes, doesn't fit
      ;; So only "short" should be kept
      (is (= "short" (:content result)))
      (is (true? (:truncated result)))
      (is (= :bytes (:truncated-by result))))))

(deftest head-truncate-first-line-exceeds-test
  ;; Verifies first-line-exceeds-limit edge case
  (testing "first line exceeds max-bytes"
    (let [text   (apply str (repeat 100 "x"))
          result (tool-output/head-truncate text {:max-lines 1000 :max-bytes 50})]
      (is (= "" (:content result)))
      (is (true? (:truncated result)))
      (is (= :bytes (:truncated-by result)))
      (is (true? (:first-line-exceeds-limit result)))
      (is (= 0 (:output-lines result)))
      (is (= 0 (:output-bytes result))))))

(deftest head-truncate-lines-then-bytes-test
  ;; Verifies that line limit is applied first, then byte limit
  (testing "line limit applied first, then byte limit kicks in"
    (let [lines  (map #(str "line-" % "-" (apply str (repeat 20 "x"))) (range 1 21))
          text   (str/join "\n" lines)
          ;; Each line is ~27 bytes. 5 lines = ~140 bytes
          result (tool-output/head-truncate text {:max-lines 5 :max-bytes 80})]
      (is (true? (:truncated result)))
      ;; Should be truncated by bytes since 5 lines > 80 bytes
      (is (= :bytes (:truncated-by result)))
      (is (<= (count (.getBytes (:content result) "UTF-8")) 80)))))

;;; tail-truncate tests

(deftest tail-truncate-within-limits-test
  ;; Verifies no truncation when content is within limits
  (testing "content within limits returns unchanged"
    (let [text   "line1\nline2\nline3"
          result (tool-output/tail-truncate text {:max-lines 10 :max-bytes 1000})]
      (is (= text (:content result)))
      (is (false? (:truncated result)))
      (is (= :none (:truncated-by result)))
      (is (= 3 (:total-lines result)))
      (is (= 3 (:output-lines result)))
      (is (false? (:last-line-partial result))))))

(deftest tail-truncate-by-lines-test
  ;; Verifies line-based truncation keeps last N lines
  (testing "truncates by line count, keeps last lines"
    (let [text   (str/join "\n" (map #(str "line" %) (range 1 11)))
          result (tool-output/tail-truncate text {:max-lines 3 :max-bytes 100000})]
      (is (= "line8\nline9\nline10" (:content result)))
      (is (true? (:truncated result)))
      (is (= :lines (:truncated-by result)))
      (is (= 10 (:total-lines result)))
      (is (= 3 (:output-lines result))))))

(deftest tail-truncate-by-bytes-test
  ;; Verifies byte-based truncation keeps last bytes worth of lines
  (testing "truncates by byte count, keeps last lines that fit"
    (let [text   "first line\nsecond line\nthird line"
          ;; "third line" = 10 bytes, fits in 15
          ;; "second line\nthird line" = 22 bytes, doesn't fit in 15
          result (tool-output/tail-truncate text {:max-lines 1000 :max-bytes 15})]
      (is (= "third line" (:content result)))
      (is (true? (:truncated result)))
      (is (= :bytes (:truncated-by result))))))

(deftest tail-truncate-empty-text-test
  ;; Verifies empty text handling
  (testing "empty text"
    (let [result (tool-output/tail-truncate "" {:max-lines 10 :max-bytes 1000})]
      (is (= "" (:content result)))
      (is (false? (:truncated result))))))

;;; Temp store tests

(deftest temp-store-init-test
  ;; Verifies temp store initialization creates a directory
  (testing "init creates a temp directory"
    (try
      (let [root-path (tool-output/init-temp-store!)]
        (is (string? root-path))
        (is (.exists (io/file root-path)))
        (is (.isDirectory (io/file root-path)))
        (is (str/includes? root-path "psi-tool-output-")))
      (finally
        (tool-output/cleanup-temp-store!)))))

(deftest temp-store-idempotent-test
  ;; Verifies init is idempotent
  (testing "init returns same root on second call"
    (try
      (let [root1 (tool-output/init-temp-store!)
            root2 (tool-output/init-temp-store!)]
        (is (= root1 root2)))
      (finally
        (tool-output/cleanup-temp-store!)))))

(deftest temp-store-persist-test
  ;; Verifies file persistence in temp store
  (testing "persist writes file to temp store"
    (try
      (let [_    (tool-output/init-temp-store!)
            path (tool-output/persist-truncated-output! "bash" "call-123" "full output text")]
        (is (string? path))
        (is (.exists (io/file path)))
        (is (= "full output text" (slurp path)))
        (is (str/ends-with? path "bash-call-123.log")))
      (finally
        (tool-output/cleanup-temp-store!)))))

(deftest temp-store-persist-auto-init-test
  ;; Verifies persist auto-initializes the store
  (testing "persist initializes store if needed"
    (try
      ;; Ensure clean state
      (tool-output/cleanup-temp-store!)
      (let [path (tool-output/persist-truncated-output! "read" "call-456" "data")]
        (is (.exists (io/file path)))
        (is (= "data" (slurp path))))
      (finally
        (tool-output/cleanup-temp-store!)))))

(deftest temp-store-cleanup-test
  ;; Verifies cleanup removes directory and files
  (testing "cleanup removes temp directory and contents"
    (let [_ (tool-output/init-temp-store!)
          _ (tool-output/persist-truncated-output! "test" "1" "data1")
          _ (tool-output/persist-truncated-output! "test" "2" "data2")
          root (tool-output/temp-store-root)]
      (is (.exists (io/file root)))
      (is (true? (tool-output/cleanup-temp-store!)))
      (is (not (.exists (io/file root))))
      (is (nil? (tool-output/temp-store-root))))))

(deftest temp-store-cleanup-no-store-test
  ;; Verifies cleanup handles no-store case
  (testing "cleanup returns false when no store exists"
    (tool-output/cleanup-temp-store!) ; ensure clean
    (is (false? (tool-output/cleanup-temp-store!)))))

(deftest temp-store-cleanup-failure-test
  ;; Verifies cleanup handles failure gracefully (warning-only)
  (testing "cleanup handles already-deleted directory gracefully"
    (let [_ (tool-output/init-temp-store!)
          root (tool-output/temp-store-root)]
      ;; Manually delete the directory to simulate failure scenario
      (doseq [f (reverse (file-seq (io/file root)))]
        (.delete f))
      ;; Cleanup should not throw — the dir is already gone
      ;; The atom should be reset regardless
      (let [result (tool-output/cleanup-temp-store!)]
        ;; May return true (delete on non-existent is ok) or false
        (is (boolean? result))
        (is (nil? (tool-output/temp-store-root)))))))

(deftest temp-store-root-nil-when-uninit-test
  ;; Verifies temp-store-root returns nil when not initialized
  (testing "temp-store-root returns nil when not initialized"
    (tool-output/cleanup-temp-store!)
    (is (nil? (tool-output/temp-store-root)))))

;;; Edge cases

(deftest head-truncate-single-line-within-limits-test
  ;; Single line within limits
  (testing "single line within limits"
    (let [result (tool-output/head-truncate "hello" {:max-lines 10 :max-bytes 100})]
      (is (= "hello" (:content result)))
      (is (false? (:truncated result))))))

(deftest head-truncate-exact-line-limit-test
  ;; Exactly at line limit
  (testing "exactly at line limit"
    (let [text   "a\nb\nc"
          result (tool-output/head-truncate text {:max-lines 3 :max-bytes 100000})]
      (is (= text (:content result)))
      (is (false? (:truncated result))))))

(deftest head-truncate-exact-byte-limit-test
  ;; Exactly at byte limit
  (testing "exactly at byte limit"
    (let [text   "hello"  ; 5 bytes
          result (tool-output/head-truncate text {:max-lines 100 :max-bytes 5})]
      (is (= "hello" (:content result)))
      (is (false? (:truncated result))))))

(deftest tail-truncate-exact-line-limit-test
  ;; Exactly at line limit
  (testing "exactly at line limit"
    (let [text   "a\nb\nc"
          result (tool-output/tail-truncate text {:max-lines 3 :max-bytes 100000})]
      (is (= text (:content result)))
      (is (false? (:truncated result))))))

(deftest head-truncate-metadata-completeness-test
  ;; Verifies all metadata fields are present
  (testing "all metadata fields present in truncated result"
    (let [text   (str/join "\n" (repeat 100 "line"))
          result (tool-output/head-truncate text {:max-lines 5 :max-bytes 25600})]
      (is (contains? result :content))
      (is (contains? result :truncated))
      (is (contains? result :truncated-by))
      (is (contains? result :total-lines))
      (is (contains? result :total-bytes))
      (is (contains? result :output-lines))
      (is (contains? result :output-bytes))
      (is (contains? result :max-lines))
      (is (contains? result :max-bytes))
      (is (contains? result :first-line-exceeds-limit)))))

(deftest tail-truncate-metadata-completeness-test
  ;; Verifies all metadata fields are present
  (testing "all metadata fields present in truncated result"
    (let [text   (str/join "\n" (repeat 100 "line"))
          result (tool-output/tail-truncate text {:max-lines 5 :max-bytes 25600})]
      (is (contains? result :content))
      (is (contains? result :truncated))
      (is (contains? result :truncated-by))
      (is (contains? result :total-lines))
      (is (contains? result :total-bytes))
      (is (contains? result :output-lines))
      (is (contains? result :output-bytes))
      (is (contains? result :max-lines))
      (is (contains? result :max-bytes))
      (is (contains? result :last-line-partial)))))
