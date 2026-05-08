(ns psi.session-persistence.compat-removed-test
  "Focused migration-surface regressions for the removed compatibility namespace."
  (:require
   [clojure.test :refer [deftest is]]
   [psi.session-persistence.core :as p]))

(deftest canonical-persistence-surface-does-not-re-export-lock-tuning-vars-test
  (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-retry-ms*)))
  (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-max-attempts*))))

(deftest canonical-compatibility-helper-names-still-exist-test
  (is (fn? p/append-entry-in!))
  (is (fn? p/persist-entry-in!))
  (is (fn? p/create-flush-state)))
