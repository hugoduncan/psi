(ns psi.session-persistence.compat-removed-test
  "Focused migration-surface regressions for the removed compatibility namespace."
  (:require
   [clojure.test :refer [deftest is]]))

(deftest canonical-persistence-surface-does-not-re-export-lock-tuning-vars-test
  (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-retry-ms*)))
  (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-max-attempts*))))

(deftest obsolete-compatibility-helper-names-are-removed-test
  (is (nil? (ns-resolve 'psi.session-persistence.core 'append-entry-in!)))
  (is (nil? (ns-resolve 'psi.session-persistence.core 'persist-entry-in!)))
  (is (nil? (ns-resolve 'psi.session-persistence.core 'persist-journal-in!)))
  (is (nil? (ns-resolve 'psi.session-persistence.core 'persist-entry!)))
  (is (nil? (ns-resolve 'psi.session-persistence.core 'persist-state-entry!))))
