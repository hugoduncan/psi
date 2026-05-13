(ns psi.agent-session.tools-truncation-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.tools :as tools]))

(deftest make-psi-tool-truncation-test
  (testing "truncated psi-tool output includes spill path and narrowing guidance"
    (let [tool   (tools/make-psi-tool
                  (fn [_q] {:big (apply str (repeat 500 "x"))})
                  {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 80}}
                   :tool-call-id "test-call-id"})
          result ((:execute tool) {"query" "[:big]"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Use a narrower query" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill)))))

  (testing "truncated eval output preserves visible action metadata"
    (let [tool   (tools/make-psi-tool
                  (fn [_q] {})
                  {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 120}}
                   :tool-call-id "test-eval-trunc"})
          result ((:execute tool) {"action" "eval"
                                   "ns" "clojure.core"
                                   "form" "(apply str (repeat 200 \"x\"))"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Eval action=eval ns=clojure.core" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill)))))

  (testing "truncated reload output preserves visible worktree metadata"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :loaded-source-path (str worktree-path "/loaded/a.clj")
                                                          :target-source-path (str worktree-path "/src/a.clj")
                                                          :warning nil}])]
      (let [tool   (tools/make-psi-tool
                    (fn [_q] {})
                    {:overrides {"psi-tool" {:max-lines 1000 :max-bytes 140}}
                     :tool-call-id "test-reload-trunc"})
            dir    (System/getProperty "user.dir")
            result (with-redefs [clojure.core/load-file (fn [_] :loaded)]
                     ((:execute tool) {"action" "reload-code"
                                       "worktree-path" dir}))
            spill  (get-in result [:details :full-output-path])]
        (is (false? (:is-error result)))
        (is (re-find #"Output truncated" (:content result)))
        (is (re-find #"Reload action=reload-code mode=worktree worktree-path=" (:content result)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote dir)) (:content result)))
        (is (string? spill))
        (is (.exists (io/file spill)))))))
