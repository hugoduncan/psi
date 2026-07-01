(ns psi.agent-session.test-support-test
  "Regression coverage for `test-support`'s shutdown-hook cleanup safety net
  (`register-cleanup-shutdown-hook!`, used by `temp-cwd`/`temp-session-root`).

  Exercises the hook's cleanup behaviour directly (start + join the
  registered `Thread`) rather than waiting for real JVM exit, then
  deregisters it so it does not run a second time at actual shutdown."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]))

(defn- start-join-and-deregister!
  "Start `hook`, join it, then deregister it from the real JVM shutdown
  sequence so it never runs a second time at actual process exit."
  [hook]
  (try
    (.start hook)
    (.join hook)
    (finally
      (.removeShutdownHook (Runtime/getRuntime) hook))))

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

(deftest temp-cwd-registers-cleanup-shutdown-hook-test
  (testing "temp-cwd itself (not just register-cleanup-shutdown-hook! in isolation) registers a hook that deletes the directory"
    (let [[dir hook] (test-support/temp-cwd-with-hook)]
      (is (.exists (java.io.File. dir)))
      (start-join-and-deregister! hook)
      (is (not (.exists (java.io.File. dir)))))))

(deftest temp-session-root-registers-cleanup-shutdown-hook-test
  (testing "temp-session-root itself (not just register-cleanup-shutdown-hook! in isolation) registers a hook that deletes the directory"
    (let [[dir hook] (test-support/temp-session-root-with-hook)]
      (is (.exists (java.io.File. dir)))
      (start-join-and-deregister! hook)
      (is (not (.exists (java.io.File. dir)))))))
