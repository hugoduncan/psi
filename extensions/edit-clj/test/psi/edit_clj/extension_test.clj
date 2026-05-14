(ns psi.edit-clj.extension-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.edit-clj.extension :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- make-temp-file
  "Create a temp file with the given string content; delete on JVM exit."
  ([] (make-temp-file ""))
  ([content]
   (let [f (java.io.File/createTempFile "edit-clj-test-" ".clj")]
     (.deleteOnExit f)
     (spit f content)
     f)))

(defn- execute
  "Call the tool's :execute fn directly (bypasses nullable api but exercises real I/O)."
  ([args]
   (execute args {}))
  ([args opts]
   (let [{:keys [api state]} (nullable/create-nullable-extension-api)
         _                   (sut/init api)
         tool                (get-in @state [:tools "edit-clj"])]
     ((:execute tool) args opts))))

(defn- parse-json
  [s]
  (json/parse-string s true))

;; ── AC 10 — init registers exactly one tool named "edit-clj" ─────────────────

(deftest init-registers-one-tool-test
  (testing "init registers exactly one tool named edit-clj"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api)
          _                   (sut/init api)
          tools               (:tools @state)]
      (is (= 1 (count tools)))
      (is (contains? tools "edit-clj"))
      (is (= "edit-clj" (get-in tools ["edit-clj" :name]))))))

;; ── AC 9 — description ≤ 20 words, one-form contract explicit ────────────────

(deftest tool-description-test
  (testing "description is ≤ 20 words"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api)
          _                   (sut/init api)
          desc                (get-in @state [:tools "edit-clj" :description])
          word-count          (count (str/split desc #"\s+"))]
      (is (<= word-count 20) (str "description has " word-count " words: " desc))))

  (testing "description mentions one-form contract"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api)
          _                   (sut/init api)
          desc                (str/lower-case (get-in @state [:tools "edit-clj" :description]))]
      (is (or (str/includes? desc "one")
              (str/includes? desc "single"))
          "description should mention 'one' or 'single' form"))))

;; ── AC 4 — validation order ───────────────────────────────────────────────────

(deftest validation-order-test
  (testing "both old-string and new-string invalid → old-string error returned"
    (let [result (parse-json (execute {"filename"   "/nonexistent/file.clj"
                                       "old-string" "(["
                                       "new-string" "(["}))]
      (is (= "error" (:status result)))
      (is (= "parse-error" (:code result)))
      (is (= "old-string" (:argument result)))))

  (testing "invalid old-string + missing file → parse-error (not file-not-found)"
    (let [result (parse-json (execute {"filename"   "/nonexistent/no-such-file.clj"
                                       "old-string" "(["
                                       "new-string" ":ok"}))]
      (is (= "error" (:status result)))
      (is (= "parse-error" (:code result)))
      (is (= "old-string" (:argument result)))))

  (testing "R4: valid old-string + invalid new-string → parse-error for new-string"
    (let [result (parse-json (execute {"filename"   "/nonexistent/any-file.clj"
                                       "old-string" ":ok"
                                       "new-string" "(["}))]
      (is (= "error" (:status result)))
      (is (= "parse-error" (:code result)))
      (is (= "new-string" (:argument result))))))

;; ── AC 5 — non-existent file → file-not-found ────────────────────────────────

(deftest file-not-found-test
  (testing "valid strings + non-existent file → file-not-found JSON"
    (let [result (parse-json (execute {"filename"   "/nonexistent/definitely-not-there.clj"
                                       "old-string" ":x"
                                       "new-string" ":y"}))]
      (is (= "error" (:status result)))
      (is (= "file-not-found" (:code result)))
      (is (contains? result :filename)))))

;; ── AC 1 (round-trip) — file written; result is status ok ────────────────────

(deftest round-trip-write-test
  (testing "execute writes the modified file and returns status ok"
    (let [content "(ns example)\n\n(defn add [a b] (+ a b))\n"
          f       (make-temp-file content)
          result  (parse-json (execute {"filename"   (.getAbsolutePath f)
                                        "old-string" "(+ a b)"
                                        "new-string" "(- a b)"}))]
      (is (= "ok" (:status result)))
      (is (= (.getAbsolutePath f) (:filename result)))
      (is (contains? result :location))
      ;; R3: all five ok-result fields must be present in the serialised JSON
      (is (contains? result :old))
      (is (contains? result :new))
      (is (str/includes? (slurp f) "(- a b)"))
      (is (not (str/includes? (slurp f) "(+ a b)")))))

  (testing "execute with :cwd opts resolves relative path"
    (let [content "(defn foo [] :original)\n"
          f       (make-temp-file content)
          dir     (.getParent f)
          fname   (.getName f)
          result  (parse-json (execute {"filename"   fname
                                        "old-string" ":original"
                                        "new-string" ":updated"}
                                       {:cwd dir}))]
      (is (= "ok" (:status result)))
      (is (str/includes? (slurp f) ":updated")))))

;; ── AC 2 (file-unchanged on no-match) ────────────────────────────────────────

(deftest file-unchanged-on-no-match-test
  (testing "no-match → temp file content identical before and after"
    (let [content "(defn foo [] :bar)\n"
          f       (make-temp-file content)
          result  (parse-json (execute {"filename"   (.getAbsolutePath f)
                                        "old-string" ":qux"
                                        "new-string" ":replaced"}))]
      (is (= "error" (:status result)))
      (is (= "no-match" (:code result)))
      (is (= content (slurp f))))))

;; ── AC 3 (file-unchanged on ambiguous-match) ─────────────────────────────────

(deftest file-unchanged-on-ambiguous-match-test
  (testing "ambiguous-match → temp file content identical before and after"
    (let [content "(defn a [] :dup)\n(defn b [] :dup)\n"
          f       (make-temp-file content)
          result  (parse-json (execute {"filename"   (.getAbsolutePath f)
                                        "old-string" ":dup"
                                        "new-string" ":unique"}))]
      (is (= "error" (:status result)))
      (is (= "ambiguous-match" (:code result)))
      (is (= 2 (:match-count result)))
      (is (= content (slurp f))))))
