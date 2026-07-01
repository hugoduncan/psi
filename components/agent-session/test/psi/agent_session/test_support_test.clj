(ns psi.agent-session.test-support-test
  "Regression coverage for `test-support`'s shutdown-hook cleanup safety net
  (`register-cleanup-shutdown-hook!`, used by `temp-cwd`/`temp-session-root`).

  Exercises the hook's cleanup behaviour directly (start + join the
  registered `Thread`) rather than waiting for real JVM exit, then
  deregisters it so it does not run a second time at actual shutdown."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]))

(deftest register-cleanup-shutdown-hook-deletes-directory-test
  (testing "invoking the registered shutdown-hook thread directly deletes the directory, without waiting for JVM exit"
    (let [dir  (str (java.nio.file.Files/createTempDirectory
                     "test-support-shutdown-hook-test-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
          hook (#'test-support/register-cleanup-shutdown-hook! dir)]
      (try
        (is (.exists (java.io.File. dir)))
        (.start hook)
        (.join hook)
        (is (not (.exists (java.io.File. dir))))
        (finally
          (.removeShutdownHook (Runtime/getRuntime) hook))))))
