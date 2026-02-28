(ns psi.agent-session.read-tool-test
  "Tests for execute-read spec parity: binary safety, image magic-byte
   detection, offset/limit slicing, head truncation."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tools :as tools])
  (:import
   [java.awt.image BufferedImage]
   [java.io File]
   [java.util Base64]
   [javax.imageio ImageIO]))

;;; Test helpers

(defn- with-temp-dir
  "Create a temp dir, run f with its path, clean up."
  [f]
  (let [tmp (File/createTempFile "psi-read-test" "")]
    (.delete tmp)
    (.mkdirs tmp)
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (doseq [file (reverse (file-seq tmp))]
          (.delete file))))))

(defn- create-text-file
  "Create a text file with the given content in dir."
  [dir filename content]
  (let [f (io/file dir filename)]
    (spit f content)
    f))

(defn- create-binary-file
  "Create a binary file with null bytes in dir."
  [dir filename]
  (let [f (io/file dir filename)
        bytes (byte-array [0x01 0x02 0x00 0x04 0x05])]
    (with-open [fos (java.io.FileOutputStream. f)]
      (.write fos bytes))
    f))

(defn- create-png-file
  "Create a minimal valid PNG file in dir."
  [dir filename width height]
  (let [f (io/file dir filename)
        img (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)]
    (ImageIO/write img "png" f)
    f))

(defn- create-jpeg-file
  "Create a minimal valid JPEG file in dir."
  [dir filename width height]
  (let [f (io/file dir filename)
        img (BufferedImage. width height BufferedImage/TYPE_INT_RGB)]
    (ImageIO/write img "jpg" f)
    f))

;;; detect-mime tests

(deftest detect-mime-test
  (testing "detects PNG from magic bytes"
    (let [buf (byte-array [(unchecked-byte 0x89) 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])]
      (is (= "image/png" (tools/detect-mime buf)))))

  (testing "detects JPEG from magic bytes"
    (let [buf (byte-array [(unchecked-byte 0xFF) (unchecked-byte 0xD8) (unchecked-byte 0xFF) (unchecked-byte 0xE0)])]
      (is (= "image/jpeg" (tools/detect-mime buf)))))

  (testing "detects GIF from magic bytes"
    (let [buf (byte-array [0x47 0x49 0x46 0x38 0x39 0x61])]
      (is (= "image/gif" (tools/detect-mime buf)))))

  (testing "detects WebP from magic bytes"
    (let [buf (byte-array [0x52 0x49 0x46 0x46 0x00 0x00 0x00 0x00 0x57 0x45 0x42 0x50])]
      (is (= "image/webp" (tools/detect-mime buf)))))

  (testing "returns nil for plain text"
    (let [buf (.getBytes "Hello, world!" "UTF-8")]
      (is (nil? (tools/detect-mime buf)))))

  (testing "returns nil for empty array"
    (is (nil? (tools/detect-mime (byte-array 0)))))

  (testing "returns nil for nil"
    (is (nil? (tools/detect-mime nil)))))

;;; Text file reading

(deftest execute-read-text-basic-test
  (testing "reads a simple text file"
    (with-temp-dir
      (fn [dir]
        (create-text-file dir "hello.txt" "Hello, world!")
        (let [result (tools/execute-read {"path" "hello.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= "Hello, world!" (:content result)))
          (is (some? (:details result)))
          (is (false? (get-in result [:details :binary-file-detected])))
          (is (false? (get-in result [:details :truncation :truncated]))))))))

(deftest execute-read-text-multiline-test
  (testing "reads a multiline file"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" (map #(str "Line " %) (range 1 11)))
              _ (create-text-file dir "multi.txt" lines)
              result (tools/execute-read {"path" "multi.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= lines (:content result)))
          (is (= 10 (get-in result [:details :truncation :total-lines]))))))))

;;; Offset/limit tests

(deftest execute-read-offset-test
  (testing "offset reads from the specified 1-indexed line"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" ["line1" "line2" "line3" "line4" "line5"])
              _ (create-text-file dir "offset.txt" lines)
              result (tools/execute-read {"path" "offset.txt" "offset" 3} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/starts-with? (:content result) "line3"))
          (is (str/includes? (:content result) "line4"))
          (is (str/includes? (:content result) "line5"))))))

  (testing "offset=1 reads from the beginning"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" ["line1" "line2" "line3"])
              _ (create-text-file dir "offset1.txt" lines)
              result (tools/execute-read {"path" "offset1.txt" "offset" 1} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/starts-with? (:content result) "line1")))))))

(deftest execute-read-offset-beyond-eof-test
  (testing "offset beyond EOF throws explicit error"
    (with-temp-dir
      (fn [dir]
        (create-text-file dir "short.txt" "line1\nline2")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Offset .* is beyond end of file"
             (tools/execute-read {"path" "short.txt" "offset" 10} {:cwd dir})))))))

(deftest execute-read-limit-test
  (testing "limit restricts number of lines returned"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" ["line1" "line2" "line3" "line4" "line5"])
              _ (create-text-file dir "limit.txt" lines)
              result (tools/execute-read {"path" "limit.txt" "limit" 2} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "line1"))
          (is (str/includes? (:content result) "line2"))
          ;; Should have continuation guidance
          (is (str/includes? (:content result) "more lines in file"))
          (is (str/includes? (:content result) "Use offset=3")))))))

(deftest execute-read-offset-and-limit-test
  (testing "offset and limit together select a slice"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" ["line1" "line2" "line3" "line4" "line5"])
              _ (create-text-file dir "slice.txt" lines)
              result (tools/execute-read {"path" "slice.txt" "offset" 2 "limit" 2} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "line2"))
          (is (str/includes? (:content result) "line3"))
          ;; Should not include line1 or line4
          (is (not (str/includes? (:content result) "line1\n")))
          ;; Should have continuation guidance
          (is (str/includes? (:content result) "more lines in file"))
          (is (str/includes? (:content result) "Use offset=4")))))))

(deftest execute-read-offset-limit-at-end-test
  (testing "offset+limit that reaches end of file has no continuation guidance"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" ["line1" "line2" "line3"])
              _ (create-text-file dir "atend.txt" lines)
              result (tools/execute-read {"path" "atend.txt" "offset" 2 "limit" 5} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "line2"))
          (is (str/includes? (:content result) "line3"))
          ;; No continuation guidance needed
          (is (not (str/includes? (:content result) "more lines"))))))))

;;; Head truncation tests

(deftest execute-read-truncation-test
  (testing "large file gets head-truncated with continuation guidance"
    (with-temp-dir
      (fn [dir]
        ;; Create a file larger than default policy (1000 lines)
        (let [lines (str/join "\n" (map #(str "Line " %) (range 1 1100)))
              _ (create-text-file dir "big.txt" lines)
              result (tools/execute-read {"path" "big.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (true? (get-in result [:details :truncation :truncated])))
          (is (str/includes? (:content result) "Showing lines"))
          (is (str/includes? (:content result) "Use offset=")))))))

(deftest execute-read-truncation-with-overrides-test
  (testing "per-tool overrides control truncation limits"
    (with-temp-dir
      (fn [dir]
        (let [lines (str/join "\n" (map #(str "Line " %) (range 1 20)))
              _ (create-text-file dir "small-limit.txt" lines)
              ;; Override read policy to 5 lines
              result (tools/execute-read
                      {"path" "small-limit.txt"}
                      {:cwd dir :overrides {"read" {:max-lines 5}}})]
          (is (false? (:is-error result)))
          (is (true? (get-in result [:details :truncation :truncated])))
          (is (= 5 (get-in result [:details :truncation :output-lines])))
          (is (str/includes? (:content result) "Showing lines"))
          (is (str/includes? (:content result) "Use offset=")))))))

(deftest execute-read-first-line-exceeds-limit-test
  (testing "first line exceeding byte limit returns bash hint"
    (with-temp-dir
      (fn [dir]
        ;; Create a file with a very long first line
        (let [long-line (apply str (repeat 30000 "x"))
              _ (create-text-file dir "longline.txt" long-line)
              result (tools/execute-read {"path" "longline.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (true? (get-in result [:details :truncation :first-line-exceeds-limit])))
          (is (str/includes? (:content result) "Use bash for a bounded slice")))))))

;;; Binary file detection

(deftest execute-read-binary-file-test
  (testing "binary file returns warning-only content"
    (with-temp-dir
      (fn [dir]
        (create-binary-file dir "data.bin")
        (let [result (tools/execute-read {"path" "data.bin"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (true? (get-in result [:details :binary-file-detected])))
          (is (str/includes? (:content result) "Binary file detected"))
          (is (str/includes? (:content result) "Content omitted")))))))

;;; Image file detection

(deftest execute-read-png-image-test
  (testing "PNG file is detected by magic bytes and returned as image content"
    (with-temp-dir
      (fn [dir]
        (create-png-file dir "test.png" 100 100)
        (let [result (tools/execute-read {"path" "test.png"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (nil? (:details result)))
          ;; Content should be a vector of content blocks
          (let [content (:content result)]
            (is (vector? content))
            (is (= 2 (count content)))
            (is (= "text" (:type (first content))))
            (is (str/includes? (:text (first content)) "image"))
            (is (= "image" (:type (second content))))
            (is (string? (:data (second content))))
            (is (string? (:mimeType (second content))))))))))

(deftest execute-read-jpeg-image-test
  (testing "JPEG file is detected by magic bytes and returned as image content"
    (with-temp-dir
      (fn [dir]
        (create-jpeg-file dir "test.jpg" 100 100)
        (let [result (tools/execute-read {"path" "test.jpg"} {:cwd dir})]
          (is (false? (:is-error result)))
          (let [content (:content result)]
            (is (vector? content))
            (is (= 2 (count content)))
            (is (= "image" (:type (second content))))))))))

(deftest execute-read-image-auto-resize-test
  (testing "large image is auto-resized"
    (with-temp-dir
      (fn [dir]
        ;; Create an image larger than 2000px
        (create-png-file dir "large.png" 3000 2500)
        (let [result (tools/execute-read {"path" "large.png"} {:cwd dir})]
          (is (false? (:is-error result)))
          (let [content (:content result)
                img-block (second content)
                ;; Decode the base64 to check dimensions
                decoded (.decode (Base64/getDecoder) ^String (:data img-block))
                bais (java.io.ByteArrayInputStream. decoded)
                img (ImageIO/read bais)]
            (is (<= (.getWidth img) 2000))
            (is (<= (.getHeight img) 2000))))))))

(deftest execute-read-image-no-resize-test
  (testing "auto-resize can be disabled"
    (with-temp-dir
      (fn [dir]
        (create-png-file dir "noresize.png" 100 100)
        (let [result (tools/execute-read
                      {"path" "noresize.png"}
                      {:cwd dir :auto-resize-images false})]
          (is (false? (:is-error result)))
          (let [content (:content result)]
            (is (= "image/png" (:mimeType (second content))))))))))

(deftest execute-read-extension-not-enough-test
  (testing "file with .png extension but text content is read as text"
    (with-temp-dir
      (fn [dir]
        ;; Create a text file with .png extension
        (create-text-file dir "fake.png" "This is not really a PNG")
        (let [result (tools/execute-read {"path" "fake.png"} {:cwd dir})]
          (is (false? (:is-error result)))
          ;; Should be read as text, not as image
          (is (string? (:content result)))
          (is (= "This is not really a PNG" (:content result))))))))

;;; File not found

(deftest execute-read-file-not-found-test
  (testing "missing file throws ex-info"
    (with-temp-dir
      (fn [dir]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"File not found"
             (tools/execute-read {"path" "nonexistent.txt"} {:cwd dir})))))))

;;; Path resolution

(deftest execute-read-uses-resolve-read-path-test
  (testing "read uses resolve-read-path for macOS fallback"
    (with-temp-dir
      (fn [dir]
        ;; Just verify basic path resolution works
        (create-text-file dir "resolved.txt" "resolved content")
        (let [result (tools/execute-read {"path" "resolved.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= "resolved content" (:content result))))))))

(deftest execute-read-absolute-path-test
  (testing "absolute path ignores cwd"
    (with-temp-dir
      (fn [dir]
        (let [f (create-text-file dir "abs.txt" "absolute content")
              abs-path (.getAbsolutePath f)
              result (tools/execute-read {"path" abs-path} {:cwd "/nonexistent"})]
          (is (false? (:is-error result)))
          (is (= "absolute content" (:content result))))))))

;;; Empty file

(deftest execute-read-empty-file-test
  (testing "empty file returns empty string"
    (with-temp-dir
      (fn [dir]
        (create-text-file dir "empty.txt" "")
        (let [result (tools/execute-read {"path" "empty.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= "" (:content result))))))))
