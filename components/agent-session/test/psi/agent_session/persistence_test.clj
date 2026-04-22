(ns psi.agent-session.persistence-test
  "Tests for NDEDN session persistence — write path, read path, migration,
  session directory layout, and session listing."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.core :as core]
   [psi.agent-session.persistence :as p]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss])
  (:import
   (java.io File RandomAccessFile)
   (java.nio.channels FileLock)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;;; ── Helpers ────────────────────────────────────────────────────────────────

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-persist-test"
                                      (into-array FileAttribute []))))

(defn- slurp-lines [^File f]
  (str/split-lines (slurp f)))

(defn- user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

;;; ── Journal (in-memory) ────────────────────────────────────────────────────

(deftest journal-ops-test
  (testing "create-journal returns empty atom"
    (let [j (p/create-journal)]
      (is (= [] (p/all-entries j)))))

  (testing "all-entries-in ignores non-seq journal values"
    (let [ctx (core/create-context)
          sd  (core/new-session-in! ctx nil {})
          sid (:session-id sd)
          _   (ss/assoc-state-value-in! ctx (ss/state-path :journal sid) :pathom/unknown)]
      (is (= [] (p/all-entries-in ctx sid)))
      (is (nil? (core/last-assistant-message-in ctx sid)))))

  (testing "append-entry! adds entry and returns it"
    (let [j (p/create-journal)
          e (session/make-entry :thinking-level {:thinking-level :off})]
      (is (= e (p/append-entry! j e)))
      (is (= [e] (p/all-entries j)))))

  (testing "entries-of-kind filters correctly"
    (let [j (p/create-journal)
          e1 (session/make-entry :model {:provider "a" :model-id "m"})
          e2 (session/make-entry :thinking-level {:thinking-level :off})
          e3 (session/make-entry :model {:provider "b" :model-id "n"})]
      (p/append-entry! j e1)
      (p/append-entry! j e2)
      (p/append-entry! j e3)
      (is (= [e1 e3] (p/entries-of-kind j :model)))
      (is (= [e2]    (p/entries-of-kind j :thinking-level)))))

  (testing "entries-up-to returns entries up to and including id"
    (let [j  (p/create-journal)
          e1 (p/append-entry! j (session/make-entry :model {:provider "a" :model-id "m"}))
          e2 (p/append-entry! j (session/make-entry :thinking-level {:thinking-level :off}))
          _  (p/append-entry! j (session/make-entry :model {:provider "b" :model-id "n"}))]
      (is (= [e1 e2] (p/entries-up-to j (:id e2))))))

  (testing "entries-up-to returns all entries when id nil"
    (let [j (p/create-journal)
          e (p/append-entry! j (session/make-entry :model {:provider "a" :model-id "m"}))]
      (is (= [e] (p/entries-up-to j nil)))))

  (testing "messages-from-entries extracts only message entries"
    (let [j   (p/create-journal)
          msg (user-msg "hello")]
      (p/append-entry! j (p/message-entry msg))
      (p/append-entry! j (p/thinking-level-entry :off))
      (is (= [msg] (p/messages-from-entries j)))))

  (testing "last-entry-of-kind returns most recent"
    (let [j  (p/create-journal)
          _  (p/append-entry! j (p/thinking-level-entry :off))
          e2 (p/append-entry! j (p/thinking-level-entry :medium))]
      (is (= e2 (p/last-entry-of-kind j :thinking-level))))))

;;; ── Session directory layout ───────────────────────────────────────────────

(deftest session-dir-test
  (testing "session-dir-for creates directory"
    ;; We can't easily override the sessions-root, so just verify the encoding logic
    ;; by testing new-session-file-path with a temp dir
    (let [dir (tmp-dir)
          f   (p/new-session-file-path dir "test-uuid")]
      (is (str/ends-with? (.getName f) "_test-uuid.ndedn"))
      (.delete dir))))

;;; ── NDEDN write path ───────────────────────────────────────────────────────

(deftest write-header-test
  (testing "write-header! produces valid first line"
    (let [dir (tmp-dir)
          f   (io/file dir "test.ndedn")]
      (p/write-header! f "sess-1" "/my/project" nil)
      (let [lines  (slurp-lines f)
            header (edn/read-string
                    {:readers {'inst #(java.util.Date/from
                                       (java.time.Instant/parse %))}}
                    (first lines))]
        (is (= 1 (count lines)))
        (is (= :session (:type header)))
        (is (= "sess-1" (:id header)))
        (is (= "/my/project" (:worktree-path header)))
        (is (nil? (:cwd header)))
        (is (nil? (:parent-session-id header)))
        (is (nil? (:parent-session header))))
      (.delete f)
      (.delete dir))))

(deftest flush-journal-test
  (testing "flush-journal! writes header + entries"
    (let [dir     (tmp-dir)
          f       (io/file dir "test.ndedn")
          entries [(p/thinking-level-entry :off)
                   (p/message-entry (user-msg "hello"))]]
      (p/flush-journal! f "sess-2" "/proj" nil entries)
      (let [lines (slurp-lines f)]
        (is (= 3 (count lines)))   ;; header + 2 entries
        (let [header (clojure.edn/read-string
                      {:readers {'inst #(java.util.Date/from
                                         (java.time.Instant/parse %))}}
                      (first lines))]
          (is (= :session (:type header)))
          (is (= "sess-2" (:id header)))))
      (.delete f)
      (.delete dir))))

(deftest append-entry-to-disk-test
  (testing "append-entry-to-disk! adds a line"
    (let [dir (tmp-dir)
          f   (io/file dir "test.ndedn")]
      (p/write-header! f "sess-3" "/proj" nil)
      (p/append-entry-to-disk! f (p/thinking-level-entry :off))
      (is (= 2 (count (slurp-lines f))))
      (.delete f)
      (.delete dir))))

(deftest session-file-locking-test
  (testing "write operations block/fail while lock sidecar is held by another process handle"
    (let [dir       (tmp-dir)
          session-f (io/file dir "locked.ndedn")
          lock-f    (io/file dir "locked.ndedn.lock")
          raf       (RandomAccessFile. lock-f "rw")]
      (try
        (let [channel (.getChannel raf)
              held    (.lock channel)]
          (try
            (binding [p/*session-file-lock-max-attempts* 2
                      p/*session-file-lock-retry-ms*    1]
              (let [ex (try
                         (p/write-header! session-f "sess-lock" "/proj" nil)
                         nil
                         (catch clojure.lang.ExceptionInfo e
                           e))]
                (is (some? ex))
                (is (re-find #"Failed to acquire session file lock" (ex-message ex)))))
            (finally
              (.release ^FileLock held))))
        (finally
          (.close raf)
          (when (.exists session-f) (.delete session-f))
          (when (.exists lock-f) (.delete lock-f))
          (.delete dir))))))

;;; ── persist-entry! lazy flush ──────────────────────────────────────────────

(deftest persist-entry-lazy-flush-test
  (testing "no write before first assistant message"
    (let [dir   (tmp-dir)
          f     (io/file dir "lazy.ndedn")
          j     (p/create-journal)
          fs    (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      ;; Append only a user message — no assistant yet
      (p/append-entry! j (p/message-entry (user-msg "hi")))
      (p/persist-entry! j fs "sess-4" "/proj" nil)
      (is (not (.exists f)) "file should not exist yet")
      (.delete dir)))

  (testing "bulk flush on first assistant message"
    (let [dir   (tmp-dir)
          f     (io/file dir "lazy.ndedn")
          j     (p/create-journal)
          fs    (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      (p/append-entry! j (p/thinking-level-entry :off))
      (p/append-entry! j (p/message-entry (user-msg "hello")))
      (p/append-entry! j (p/message-entry (assistant-msg "world")))
      (p/persist-entry! j fs "sess-5" "/proj" nil)
      (is (.exists f) "file should exist after assistant message")
      (is (:flushed? @fs) "flushed? should be true")
      (let [lines (slurp-lines f)]
        (is (= 4 (count lines)) "header + 3 entries"))
      (.delete f)
      (.delete dir)))

  (testing "subsequent entries appended individually after flush"
    (let [dir   (tmp-dir)
          f     (io/file dir "lazy.ndedn")
          j     (p/create-journal)
          fs    (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      ;; Trigger initial flush
      (p/append-entry! j (p/message-entry (user-msg "hi")))
      (p/append-entry! j (p/message-entry (assistant-msg "hello")))
      (p/persist-entry! j fs "sess-6" "/proj" nil)
      (is (:flushed? @fs))
      (let [lines-before (count (slurp-lines f))]
        ;; Append another entry
        (p/append-entry! j (p/thinking-level-entry :medium))
        (p/persist-entry! j fs "sess-6" "/proj" nil)
        (is (= (inc lines-before) (count (slurp-lines f)))))
      (.delete f)
      (.delete dir)))

  (testing "no-op when session-file is nil"
    (let [j  (p/create-journal)
          fs (p/create-flush-state)]
      ;; :session-file is nil by default
      (p/append-entry! j (p/message-entry (assistant-msg "x")))
      ;; Should not throw
      (is (nil? (p/persist-entry! j fs "sess-7" "/proj" nil))))))

;;; ── Disk read path ─────────────────────────────────────────────────────────

(deftest load-session-file-test
  (testing "returns nil for missing file"
    (is (nil? (p/load-session-file "/tmp/does-not-exist-psi.ndedn"))))

  (testing "returns nil for empty file"
    (let [f (File/createTempFile "psi-test" ".ndedn")]
      (spit f "")
      (is (nil? (p/load-session-file f)))
      (.delete f)))

  (testing "returns nil for file with no valid header"
    (let [f (File/createTempFile "psi-test" ".ndedn")]
      (spit f "{:not :a :header}\n")
      (is (nil? (p/load-session-file f)))
      (.delete f)))

  (testing "round-trips header and entries"
    (let [dir     (tmp-dir)
          f       (io/file dir "rt.ndedn")
          entries [(p/thinking-level-entry :off)
                   (p/message-entry (user-msg "hello"))
                   (p/message-entry (assistant-msg "world"))]]
      (p/flush-journal! f "sess-rt" "/my/cwd" nil entries)
      (let [loaded (p/load-session-file f)]
        (is (some? loaded))
        (is (= "sess-rt" (get-in loaded [:header :id])))
        (is (= "/my/cwd" (get-in loaded [:header :worktree-path])))
        (is (nil? (get-in loaded [:header :cwd])))
        (is (= 3 (count (:entries loaded))))
        (is (= :thinking-level (:kind (first (:entries loaded)))))
        (is (= "hello"
               (get-in (second (:entries loaded))
                       [:data :message :content 0 :text]))))
      (.delete f)
      (.delete dir)))

  (testing "skips malformed lines"
    (let [f (File/createTempFile "psi-test" ".ndedn")]
      (spit f (str "{:type :session :version 3 :id \"x\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/c\"}\n"
                   "THIS IS NOT EDN\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :off}}\n"))
      (let [loaded (p/load-session-file f)]
        (is (= 1 (count (:entries loaded)))))
      (.delete f))))

;;; ── Migration ──────────────────────────────────────────────────────────────

(deftest migration-test
  (testing "v1 entries get :id and :parent-id added"
    (let [f (File/createTempFile "psi-test" ".ndedn")]
      ;; Write a v1-style file: header with no :version, entries with no :id
      (spit f (str "{:type :session :id \"v1-sess\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/c\"}\n"
                   "{:kind :thinking-level :timestamp #inst \"2024-01-01T00:00:01Z\" :data {:thinking-level :off}}\n"
                   "{:kind :message :timestamp #inst \"2024-01-01T00:00:02Z\" :data {:message {:role \"user\" :content \"hi\"}}}\n"))
      (let [loaded  (p/load-session-file f)
            entries (:entries loaded)]
        (is (= 2 (count entries)))
        (is (string? (:id (first entries))))
        (is (nil? (:parent-id (first entries))))
        (is (= (:id (first entries)) (:parent-id (second entries)))))
      (.delete f)))

  (testing "v2 hook-message role renamed to custom"
    (let [f (File/createTempFile "psi-test" ".ndedn")]
      (spit f (str "{:type :session :version 2 :id \"v2-sess\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/c\"}\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :message :data {:message {:role \"hook-message\" :content \"x\"}}}\n"))
      (let [loaded  (p/load-session-file f)
            entry   (first (:entries loaded))]
        (is (= "custom" (get-in entry [:data :message :role]))))
      (.delete f)))

  (testing "v3 file is not re-migrated"
    (let [dir     (tmp-dir)
          f       (io/file dir "v3.ndedn")
          entries [(p/thinking-level-entry :off)]]
      (p/flush-journal! f "v3-sess" "/c" nil entries)
      (let [loaded (p/load-session-file f)]
        (is (= 4 (get-in loaded [:header :version]))
            "load migrates current v3 files to v4 on read"))
      (.delete f)
      (.delete dir))))

;;; ── find-most-recent-session ───────────────────────────────────────────────

(deftest find-most-recent-session-test
  (testing "returns nil for empty directory"
    (let [dir (tmp-dir)]
      (is (nil? (p/find-most-recent-session dir)))
      (.delete dir)))

  (testing "returns nil when no valid session files"
    (let [dir (tmp-dir)
          f   (io/file dir "invalid.ndedn")]
      (spit f "not valid edn\n")
      (is (nil? (p/find-most-recent-session dir)))
      (.delete f)
      (.delete dir)))

  (testing "returns path of most recently modified valid file"
    (let [dir (tmp-dir)
          f1  (io/file dir "old.ndedn")
          f2  (io/file dir "new.ndedn")]
      (p/flush-journal! f1 "sess-old" "/c" nil [(p/thinking-level-entry :off)])
      (Thread/sleep 10)
      (p/flush-journal! f2 "sess-new" "/c" nil [(p/thinking-level-entry :off)])
      ;; Touch f2 to make it definitively newer
      (.setLastModified f2 (+ (.lastModified f1) 1000))
      (let [result (p/find-most-recent-session dir)]
        (is (= (.getAbsolutePath f2) result)))
      (.delete f1)
      (.delete f2)
      (.delete dir))))

;;; ── list-sessions ──────────────────────────────────────────────────────────

(deftest list-sessions-test
  (testing "returns empty vector for missing directory"
    (is (= [] (p/list-sessions "/tmp/psi-nonexistent-dir-xyz"))))

  (testing "returns SessionInfo maps sorted by modified descending"
    (let [dir (tmp-dir)
          f1  (io/file dir "s1.ndedn")
          f2  (io/file dir "s2.ndedn")]
      (p/flush-journal! f1 "sess-a" "/proj" nil
                        [(p/message-entry (user-msg "first message"))
                         (p/message-entry (assistant-msg "reply"))])
      (Thread/sleep 10)
      (p/flush-journal! f2 "sess-b" "/proj" nil
                        [(p/message-entry (user-msg "second message"))
                         (p/message-entry (assistant-msg "reply2"))])
      (.setLastModified f2 (+ (.lastModified f1) 1000))
      (let [sessions (p/list-sessions dir)]
        (is (= 2 (count sessions)))
        ;; Most recently modified first
        (is (= "sess-b" (:id (first sessions))))
        (is (= "sess-a" (:id (second sessions))))
        (is (= "/proj" (:cwd (first sessions))))
        (is (= "second message" (:first-message (first sessions))))
        (is (= 2 (:message-count (first sessions)))))
      (.delete f1)
      (.delete f2)
      (.delete dir)))

  (testing "SessionInfo includes session name from session-info entry"
    (let [dir (tmp-dir)
          f   (io/file dir "named.ndedn")]
      (p/flush-journal! f "sess-named" "/proj" nil
                        [(p/message-entry (assistant-msg "hi"))
                         (p/session-info-entry "My Session")])
      (let [sessions (p/list-sessions dir)]
        (is (= "My Session" (:name (first sessions)))))
      (.delete f)
      (.delete dir)))

  (testing "SessionInfo includes parent-session-id when present in header"
    (let [dir (tmp-dir)
          f   (io/file dir "child.ndedn")]
      (p/flush-journal! f "sess-child" "/proj" "sess-parent" "/tmp/parent.ndedn"
                        [(p/message-entry (assistant-msg "hi"))])
      (let [sessions (p/list-sessions dir)
            info     (first sessions)]
        (is (= "sess-parent" (:parent-session-id info)))
        (is (= "/tmp/parent.ndedn" (:parent-session-path info))))
      (.delete f)
      (.delete dir)))

  (testing "SessionInfo uses persisted worktree-path"
    (let [dir (tmp-dir)
          f   (io/file dir "worktree-bound.ndedn")]
      (spit f (str "{:type :session :version 4 :id \"sess-worktree\" :timestamp #inst \"2024-01-01T00:00:00Z\" :worktree-path \"/repo/feature-x\"}\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :session-info :data {:name \"Feature X\"}}\n"
                   "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :message :data {:message {:role \"assistant\" :content [{:type :text :text \"hi\"}]}}}\n"))
      (let [sessions (p/list-sessions dir)
            info     (first sessions)]
        (is (= "/repo/feature-x" (:cwd info)))
        (is (= "/repo/feature-x" (:worktree-path info)))
        (is (= "Feature X" (:name info))))
      (.delete f)
      (.delete dir)))

  (testing "skips invalid files silently"
    (let [dir  (tmp-dir)
          bad  (io/file dir "bad.ndedn")
          good (io/file dir "good.ndedn")]
      (spit bad "not valid\n")
      (p/flush-journal! good "sess-good" "/proj" nil
                        [(p/message-entry (assistant-msg "ok"))])
      (let [sessions (p/list-sessions dir)]
        (is (= 1 (count sessions)))
        (is (= "sess-good" (:id (first sessions)))))
      (.delete bad)
      (.delete good)
      (.delete dir))))

;;; ── Entry constructors ─────────────────────────────────────────────────────

(deftest entry-constructors-test
  (testing "message-entry wraps message"
    (let [msg (user-msg "hello")
          e   (p/message-entry msg)]
      (is (= :message (:kind e)))
      (is (= msg (get-in e [:data :message])))))

  (testing "thinking-level-entry"
    (let [e (p/thinking-level-entry :medium)]
      (is (= :thinking-level (:kind e)))
      (is (= :medium (get-in e [:data :thinking-level])))))

  (testing "model-entry"
    (let [e (p/model-entry "anthropic" "claude-3")]
      (is (= :model (:kind e)))
      (is (= "anthropic" (get-in e [:data :provider])))
      (is (= "claude-3" (get-in e [:data :model-id])))))

  (testing "compaction-entry"
    (let [result {:summary "compact" :first-kept-entry-id "e1"
                  :tokens-before 1000 :details nil}
          e      (p/compaction-entry result false)]
      (is (= :compaction (:kind e)))
      (is (= "compact" (get-in e [:data :summary])))
      (is (false? (get-in e [:data :from-hook])))))

  (testing "label-entry"
    (let [e (p/label-entry "target-id" "my label")]
      (is (= :label (:kind e)))
      (is (= "target-id" (get-in e [:data :target-id])))
      (is (= "my label" (get-in e [:data :label])))))

  (testing "session-info-entry"
    (let [e (p/session-info-entry "Session Name")]
      (is (= :session-info (:kind e)))
      (is (= "Session Name" (get-in e [:data :name]))))))
