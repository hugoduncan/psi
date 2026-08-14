(ns psi.workflow-runtime.delegated-failure-control-order-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(deftest sanitize-component-control-removal-order-test
  ;; Span recognition sees the same logical text that remains after removable
  ;; controls are discarded, without constructing that unbounded intermediate.
  (testing "removes controls before recognizing every sensitive span family"
    (doseq [[input expected]
            [["to\u0000ken=secret denied" "[REDACTED] denied"]
             ["token\u0000=secret denied" "[REDACTED] denied"]
             ["token=sec\u0000ret denied" "[REDACTED] denied"]
             ["token=\"sec\u0000ret\" denied" "[REDACTED] denied"]
             ["a\u0000t child.core/run(child.clj:\u000042) denied"
              "[STACKTRACE_REDACTED] denied"]
             ["Bea\u0000rer abcd\u0000efgh denied"
              "[REDACTED_TOKEN] denied"]
             ["s\u0000k-abcd\u0000efgh denied"
              "[REDACTED_TOKEN] denied"]
             ["open /private\u0000/file.edn denied"
              "open [PATH_REDACTED] denied"]
             ["read config/sec\u0000ret-store.edn denied"
              "read [PATH_REDACTED] denied"]
             ["read config/.s\u0000sh/key denied"
              "read [PATH_REDACTED] denied"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input))))
