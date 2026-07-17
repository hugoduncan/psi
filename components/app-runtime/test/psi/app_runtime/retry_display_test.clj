(ns psi.app-runtime.retry-display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.retry-display :as retry-display]))

(deftest format-relative-seconds-locks-zero-second-rendering-test
  (testing "zero (and sub-second non-positive) delay renders as the literal \"0s\" form"
    ;; Authority lock (task 243 follow-up): downstream derivations depend on the
    ;; exact zero-second rendering being an integer-seconds `"Ns"` string, not an
    ;; alternate form (e.g. \"now\"). A change to this rendering must fail here
    ;; rather than silently desyncing consumers such as the rpc-prompt test's
    ;; `active-retry-text-prefix`, which strips a trailing literal `"0s"`.
    (is (= "0s" (retry-display/format-relative-seconds 0)))
    (is (= "0s" (retry-display/format-relative-seconds -500)))))

(deftest retry-status-text-locks-zero-second-active-prefix-test
  (testing "active retry at zero delay with no rate-limit metadata renders exactly \"retry in 0s\""
    ;; Authority lock (task 243 follow-up): `active-retry-text-prefix` in
    ;; `psi.rpc-prompt-test` derives the `"retry in "` prefix by stripping the
    ;; trailing `"0s"` off this exact production string. If production ever
    ;; renders the zero-second case as a non-`"Ns"` form, this authority test
    ;; fails first (rather than the derived prefix silently desyncing).
    (is (= "retry in 0s"
           (retry-display/retry-status-text {:active? true :resume-at 0} 0)))
    ;; An omitted `:resume-at` renders identically to explicit `:resume-at 0`.
    (is (= (retry-display/retry-status-text {:active? true :resume-at 0} 0)
           (retry-display/retry-status-text {:active? true} 0)))))
