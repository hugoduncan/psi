(ns psi.workflow-runtime.delegated-failure-sanitize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(deftest sanitize-component-remaining-contract-matrix-test
  ;; Each scanner family retains its exact delimiter and punctuation contract.
  (testing "redacts all remaining positive families and rejects partial spans"
    (doseq [[input expected]
            [["open /home/alice/private.edn, then retry"
              "open [PATH_REDACTED], then retry"]
             ["open ./private/file.edn: retry"
              "open [PATH_REDACTED]: retry"]
             ["open ../private/file.edn? retry"
              "open [PATH_REDACTED]? retry"]
             ["read config/.ssh/settings"
              "read [PATH_REDACTED]"]
             ["read credentials/id_rsa"
              "read [PATH_REDACTED]"]
             ["read public\\file.edn"
              "read public\\file.edn"]
             ["x-token=abc"
              "[REDACTED]"]
             ["token:abc) retry"
              "[REDACTED]) retry"]
             ["token='abc\\' 123', retry"
              "[REDACTED], retry"]
             ["token=abc; retry"
              "[REDACTED]; retry"]
             ["token=abc\"def denied"
              "[REDACTED] denied"]
             ["token=abc'def denied"
              "[REDACTED] denied"]
             ["Bearer abcdefgh==, retry"
              "[REDACTED_TOKEN], retry"]
             ["Bearer\tabcdefgh retry"
              "[REDACTED_TOKEN] retry"]
             ["sk-abcdefgh. retry"
              "[REDACTED_TOKEN]. retry"]
             ["(at child.core/run(child.clj:42))."
              "([STACKTRACE_REDACTED])."]
             ["at child.core/run(child.clj:x)"
              "at child.core/run(child.clj:x)"]
             ["at child.core/run(child.clj:42 extra)"
              "at child.core/run(child.clj:42 extra)"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input)))

  (testing "normalization is idempotent, including an immediate nested message"
    (let [raw " token=secret at child.core/run(child.clj:42) open /private/file.edn "
          sanitized (delegated-failure/sanitize-component raw)]
      (is (= sanitized (delegated-failure/sanitize-component sanitized))))))
