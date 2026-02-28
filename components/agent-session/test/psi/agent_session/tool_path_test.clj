(ns psi.agent-session.tool-path-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tool-path :as tool-path])
  (:import
   [java.io File]
   [java.text Normalizer Normalizer$Form]))

;;; expand-path tests

(deftest expand-path-at-prefix-test
  ;; Verifies that leading @ is stripped from paths
  (testing "strips leading @ from path"
    (is (= "/foo/bar" (tool-path/expand-path "@/foo/bar"))))

  (testing "does not strip @ in middle of path"
    (is (= "/foo/@bar" (tool-path/expand-path "/foo/@bar"))))

  (testing "strips @ from relative path"
    (is (= "foo/bar" (tool-path/expand-path "@foo/bar"))))

  (testing "leaves path without @ unchanged"
    (is (= "/foo/bar" (tool-path/expand-path "/foo/bar")))))

(deftest expand-path-unicode-spaces-test
  ;; Verifies that unicode space characters are normalized to ASCII space
  (testing "replaces narrow no-break space (U+202F)"
    (is (= "foo bar" (tool-path/expand-path "foo\u202Fbar"))))

  (testing "replaces no-break space (U+00A0)"
    (is (= "foo bar" (tool-path/expand-path "foo\u00A0bar"))))

  (testing "replaces thin space (U+2009)"
    (is (= "foo bar" (tool-path/expand-path "foo\u2009bar"))))

  (testing "replaces ideographic space (U+3000)"
    (is (= "foo bar" (tool-path/expand-path "foo\u3000bar"))))

  (testing "replaces multiple unicode spaces"
    (is (= "a b c" (tool-path/expand-path "a\u202Fb\u00A0c"))))

  (testing "leaves ASCII spaces unchanged"
    (is (= "foo bar" (tool-path/expand-path "foo bar")))))

(deftest expand-path-tilde-test
  ;; Verifies that ~ and ~/ are expanded to user home directory
  (let [home (System/getProperty "user.home")]
    (testing "expands bare ~"
      (is (= home (tool-path/expand-path "~"))))

    (testing "expands ~/ prefix"
      (is (= (str home "/Documents") (tool-path/expand-path "~/Documents"))))

    (testing "does not expand ~ in middle of path"
      (is (= "/foo/~/bar" (tool-path/expand-path "/foo/~/bar"))))

    (testing "does not expand ~user (no user expansion)"
      (is (= "~user/foo" (tool-path/expand-path "~user/foo"))))))

(deftest expand-path-combined-test
  ;; Verifies that all expansions compose correctly
  (let [home (System/getProperty "user.home")]
    (testing "strips @ then expands ~"
      (is (= (str home "/foo") (tool-path/expand-path "@~/foo"))))

    (testing "strips @ then normalizes unicode space"
      (is (= "foo bar" (tool-path/expand-path "@foo\u202Fbar"))))))

;;; resolve-to-cwd tests

(deftest resolve-to-cwd-absolute-path-test
  ;; Verifies that absolute paths are returned as-is regardless of cwd
  (testing "absolute path ignores cwd"
    (let [f (tool-path/resolve-to-cwd "/tmp/cwd" "/absolute/path")]
      (is (= "/absolute/path" (.getPath f)))))

  (testing "absolute path with nil cwd"
    (let [f (tool-path/resolve-to-cwd nil "/absolute/path")]
      (is (= "/absolute/path" (.getPath f))))))

(deftest resolve-to-cwd-relative-path-test
  ;; Verifies that relative paths are joined with cwd
  (testing "relative path joined with cwd"
    (let [f (tool-path/resolve-to-cwd "/tmp/cwd" "relative/path")]
      (is (= (str "/tmp/cwd" File/separator "relative/path") (.getPath f)))))

  (testing "relative path with nil cwd returns relative"
    (let [f (tool-path/resolve-to-cwd nil "relative/path")]
      (is (= "relative/path" (.getPath f))))))

;;; resolve-read-path tests

(deftest resolve-read-path-existing-file-test
  ;; Verifies that existing files are returned directly
  (let [tmp (File/createTempFile "tool-path-test" ".txt")
        _   (.deleteOnExit tmp)
        cwd (.getParent tmp)
        name (.getName tmp)]
    (testing "returns existing file directly"
      (let [result (tool-path/resolve-read-path name cwd)]
        (is (.exists result))
        (is (= (.getAbsolutePath tmp) (.getAbsolutePath result)))))))

(deftest resolve-read-path-at-prefix-test
  ;; Verifies that @ prefix is stripped before resolution
  (let [tmp (File/createTempFile "tool-path-test" ".txt")
        _   (.deleteOnExit tmp)
        path (.getAbsolutePath tmp)]
    (testing "strips @ and resolves to existing file"
      (let [result (tool-path/resolve-read-path (str "@" path) nil)]
        (is (.exists result))
        (is (= (.getAbsolutePath tmp) (.getAbsolutePath result)))))))

(deftest resolve-read-path-nonexistent-test
  ;; Verifies that non-existent files return the original resolved path
  (testing "returns original resolved path when no variant exists"
    (let [result (tool-path/resolve-read-path "/nonexistent/file.txt" nil)]
      (is (= "/nonexistent/file.txt" (.getAbsolutePath result)))
      (is (not (.exists result))))))

(deftest resolve-read-path-nfd-variant-test
  ;; Verifies that NFD-normalized variant is found when it exists on disk
  ;; This simulates macOS HFS+ behavior where filenames are NFD-normalized
  (let [dir     (doto (io/file (System/getProperty "java.io.tmpdir") "tool-path-nfd-test")
                  (.mkdirs))
        ;; Create a file with NFD-normalized name (e.g., é decomposed as e + combining accent)
        nfd-name (Normalizer/normalize "café.txt" Normalizer$Form/NFD)
        nfd-file (io/file dir nfd-name)
        _        (spit nfd-file "test content")
        ;; Query with NFC (composed) name
        nfc-name (Normalizer/normalize "café.txt" Normalizer$Form/NFC)]
    (try
      (testing "finds NFD variant when NFC path does not exist"
        ;; Only test if the filesystem actually distinguishes NFC/NFD
        ;; On macOS HFS+, both may resolve to the same file
        (let [nfc-file (io/file dir nfc-name)]
          (if (.exists nfc-file)
            ;; Filesystem normalizes — both exist, test passes trivially
            (let [result (tool-path/resolve-read-path nfc-name (.getAbsolutePath dir))]
              (is (.exists result)))
            ;; Filesystem distinguishes — should find NFD variant
            (let [result (tool-path/resolve-read-path nfc-name (.getAbsolutePath dir))]
              (is (.exists result))
              (is (= (.getAbsolutePath nfd-file) (.getAbsolutePath result)))))))
      (finally
        (.delete nfd-file)
        (.delete dir)))))

(deftest resolve-read-path-am-pm-variant-test
  ;; Verifies that AM/PM narrow-no-break-space variant is tried
  (let [dir      (doto (io/file (System/getProperty "java.io.tmpdir") "tool-path-ampm-test")
                   (.mkdirs))
        ;; Create file with narrow no-break space before AM
        nnbsp    "\u202F"
        am-file  (io/file dir (str "log 10" nnbsp "AM.txt"))
        _        (spit am-file "test content")]
    (try
      (testing "finds AM/PM variant with narrow no-break space"
        ;; Query with regular space before AM
        (let [result (tool-path/resolve-read-path "log 10 AM.txt" (.getAbsolutePath dir))]
          (is (.exists result))
          (is (= (.getAbsolutePath am-file) (.getAbsolutePath result)))))
      (finally
        (.delete am-file)
        (.delete dir)))))

(deftest resolve-read-path-curly-quote-variant-test
  ;; Verifies that curly quote variant is tried
  (let [dir        (doto (io/file (System/getProperty "java.io.tmpdir") "tool-path-curly-test")
                     (.mkdirs))
        ;; Create file with curly apostrophe
        curly-file (io/file dir "it\u2019s.txt")
        _          (spit curly-file "test content")]
    (try
      (testing "finds curly quote variant"
        ;; Query with straight apostrophe
        (let [result (tool-path/resolve-read-path "it's.txt" (.getAbsolutePath dir))]
          (is (.exists result))
          (is (= (.getAbsolutePath curly-file) (.getAbsolutePath result)))))
      (finally
        (.delete curly-file)
        (.delete dir)))))

(deftest resolve-read-path-tilde-expansion-test
  ;; Verifies that ~ is expanded in read path resolution
  (let [home (System/getProperty "user.home")]
    (testing "expands ~ in read path"
      (let [result (tool-path/resolve-read-path "~" nil)]
        (is (= home (.getAbsolutePath result)))))))
