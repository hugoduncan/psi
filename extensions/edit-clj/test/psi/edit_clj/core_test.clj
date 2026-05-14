(ns psi.edit-clj.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.edit-clj.core :as core]))

;; ── Test helpers ─────────────────────────────────────────────────────────────

(defn- edit
  "Run the full core pipeline (parse → find → filter → replace).
   Returns the result map from `replace-in`, or an error map from
   `parse-single-form` on bad input."
  [old-str new-str file-content & {:keys [start-line end-line]}]
  (let [old-result (core/parse-single-form old-str "old-string")]
    (if (:error old-result)
      (:error old-result)
      (let [new-result (core/parse-single-form new-str "new-string")]
        (if (:error new-result)
          (:error new-result)
          (let [candidates (core/find-candidates (:ok old-result) file-content)
                filtered   (core/apply-line-filter candidates
                                                   {:start-line start-line
                                                    :end-line   end-line})]
            (core/replace-in (:ok old-result) (:ok new-result)
                             file-content filtered)))))))

;; ── AC 1 — single match replaced; content outside node unchanged ──────────────

(deftest single-match-replace-test
  (testing "replaces the matched node; all content outside it is byte-for-byte identical"
    (let [content "(ns foo)\n\n(defn bar [x] (+ x 1))\n"
          result  (edit "(+ x 1)" "(* x 2)" content)]
      (is (= "ok" (:status result)))
      (is (= "(ns foo)\n\n(defn bar [x] (* x 2))\n" (:content result)))
      (is (= {:line 3 :column 15} (:location result)))
      (is (= "(+ x 1)" (:old result)))
      (is (= "(* x 2)" (:new result))))))

;; ── AC 2 — no-match result; content string returned unchanged ─────────────────

(deftest no-match-test
  (testing "no-match status when old-string not present in file"
    (let [content "(ns foo)\n(defn bar [] :baz)\n"
          result  (edit ":qux" ":replaced" content)]
      (is (= "error" (:status result)))
      (is (= "no-match" (:code result)))
      (is (string? (:hint result)))))

  (testing "no :content key on no-match (file not modified)"
    (let [result (edit ":qux" ":replaced" "(ns foo)\n")]
      (is (nil? (:content result))))))

;; ── AC 3 — ambiguous-match result with correct locations ─────────────────────

(deftest ambiguous-match-test
  (testing "two matching nodes → ambiguous-match with match-count and matches"
    (let [content "(defn foo [] :dup)\n(defn bar [] :dup)\n"
          result  (edit ":dup" ":unique" content)]
      (is (= "error" (:status result)))
      (is (= "ambiguous-match" (:code result)))
      (is (= 2 (:match-count result)))
      (is (= 2 (count (:matches result))))
      (is (every? #(contains? % :line) (:matches result)))
      (is (every? #(contains? % :column) (:matches result)))
      (is (every? #(contains? % :text) (:matches result)))
      (is (string? (:hint result))))))

;; ── AC 4 — parse-single-form errors ──────────────────────────────────────────

(deftest parse-single-form-test
  (testing "invalid Clojure → parse-error"
    (let [result (core/parse-single-form "(defn [" "old-string")]
      (is (= :parse-error (:code (:error result))))
      (is (= "old-string" (:argument (:error result))))))

  (testing "blank input → parse-error"
    (let [result (core/parse-single-form "   " "new-string")]
      (is (= :parse-error (:code (:error result))))
      (is (= "new-string" (:argument (:error result))))))

  (testing "nil input → parse-error"
    (let [result (core/parse-single-form nil "old-string")]
      (is (= :parse-error (:code (:error result))))))

  (testing "valid single form → {:ok node}"
    (let [result (core/parse-single-form "(+ 1 2)" "old-string")]
      (is (contains? result :ok))
      (is (nil? (:error result)))))

  (testing "argument name is preserved in error map"
    (let [result (core/parse-single-form "   " "new-string")]
      (is (= "new-string" (:argument (:error result)))))))

;; ── AC 8 — multi-form input → parse-error ────────────────────────────────────

(deftest multi-form-parse-error-test
  (testing "multi-form old-string → parse-error for old-string"
    (let [result (edit "foo bar" ":ok" "(foo bar)")]
      (is (= :parse-error (:code result)))
      (is (= "old-string" (:argument result)))))

  (testing "multi-form new-string → parse-error for new-string"
    (let [result (edit "foo" "bar baz" "(foo)")]
      (is (= :parse-error (:code result)))
      (is (= "new-string" (:argument result))))))

;; ── AC 6a — two identical forms; one in range, one outside ───────────────────

(deftest line-range-disambiguation-test
  (testing "6a: two identical forms, one in range → single match"
    (let [content "(defn foo [] :dup)\n\n(defn bar [] :dup)\n"
          ;; :dup on line 1 and line 3
          result  (edit ":dup" ":replaced" content :start-line 1 :end-line 1)]
      (is (= "ok" (:status result)))
      (is (= 1 (:line (:location result))))))

  (testing "6a: pick the second occurrence by narrowing start-line"
    (let [content "(defn foo [] :dup)\n\n(defn bar [] :dup)\n"
          result  (edit ":dup" ":replaced" content :start-line 3 :end-line 3)]
      (is (= "ok" (:status result)))
      (is (= 3 (:line (:location result)))))))

;; ── AC 6b — form starts in range, ends past end-line → matched ───────────────

(deftest form-straddles-end-line-test
  (testing "6b: form starts in range, ends past end-line → matched by start-row rule"
    (let [content "(defn foo []\n  :bar)\n"
          ;; (defn foo [] :bar) starts at row 1, ends at row 2; end-line=1
          result  (edit "(defn foo []\n  :bar)" "(defn foo [] :replaced)" content
                        :start-line 1 :end-line 1)]
      (is (= "ok" (:status result))))))

;; ── AC 6c — form ends in range, starts before start-line → not matched ───────

(deftest form-starts-before-range-test
  (testing "6c: form end-row in range but start-row before start-line → not matched"
    (let [content "(defn foo []\n  :bar)\n"
          ;; (defn foo [] :bar) starts at row 1; start-line=2
          result  (edit "(defn foo []\n  :bar)" "(defn foo [] :replaced)" content
                        :start-line 2 :end-line 2)]
      (is (= "error" (:status result)))
      (is (= "no-match" (:code result))))))

;; ── AC 6d — two identical forms both in range → ambiguous-match ──────────────

(deftest both-forms-in-range-test
  (testing "6d: two matching forms both within range → ambiguous-match"
    (let [content "(defn a [] :x)\n(defn b [] :x)\n"
          ;; both :x at rows 1 and 2
          result  (edit ":x" ":y" content :start-line 1 :end-line 2)]
      (is (= "error" (:status result)))
      (is (= "ambiguous-match" (:code result))))))

;; ── AC 6e — valid range with no form starts → no-match ───────────────────────

(deftest range-no-form-starts-test
  (testing "6e: range contains no node starts matching old-string → no-match"
    (let [content "(defn foo [] :target)\n\n(defn bar [] :other)\n"
          ;; :target is on row 1; search in rows 2-3 only
          result  (edit ":target" ":replaced" content :start-line 2 :end-line 3)]
      (is (= "error" (:status result)))
      (is (= "no-match" (:code result))))))

;; ── AC 6f — nested symbol in straddling parent ────────────────────────────────

(deftest nested-symbol-in-straddling-parent-test
  (testing "6f: symbol on row 2, parent starts on row 1; start-line=2 → matched"
    (let [content "(defn foo []\n  :nested)\n"
          ;; :nested is on row 2; start-line=2
          result  (edit ":nested" ":replaced" content :start-line 2 :end-line 2)]
      (is (= "ok" (:status result)))
      (is (= 2 (:line (:location result)))))))

;; ── AC 7 — comment in new-string preserved verbatim ──────────────────────────

(deftest comment-in-new-string-preserved-test
  (testing "7: inline comment in new-string present in output; not dropped by coerce"
    (let [content "(foo :original)\n(bar)\n"
          new-str "(foo ;; preserved comment\n  :replaced)"
          result  (edit "(foo :original)" new-str content)]
      (is (= "ok" (:status result)))
      (is (str/includes? (:content result) ";; preserved comment"))))

  (testing "7: comment-only prefix in new-string is treated as whitespace; form still parses"
    (let [new-str ";; comment\n:y"
          ;; rewrite-clj treats line comments as whitespace-equivalent;
          ;; so ";; comment\n:y" is one form (:y) — parse must succeed
          result  (core/parse-single-form new-str "new-string")]
      (is (nil? (:error result))))))
