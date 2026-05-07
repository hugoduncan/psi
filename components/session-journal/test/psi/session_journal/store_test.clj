(ns psi.session-journal.store-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.session-journal.store :as store])
  (:import
   (java.io File RandomAccessFile)
   (java.nio.channels FileLock)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-session-journal-test"
                                      (into-array FileAttribute []))))

(defn- slurp-lines [^File f]
  (str/split-lines (slurp f)))

(defn- user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (Instant/now)})

(defn- assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :timestamp (Instant/now)})

(defn- message-entry [message]
  {:id (str (java.util.UUID/randomUUID))
   :parent-id nil
   :timestamp (Instant/now)
   :kind :message
   :data {:message message}})

(defn- thinking-entry [level]
  {:id (str (java.util.UUID/randomUUID))
   :parent-id nil
   :timestamp (Instant/now)
   :kind :thinking-level
   :data {:thinking-level level}})

(deftest session-dir-root-ownership-test
  (testing "session-dir-for owns root/layout policy and supports explicit root override"
    (let [root (tmp-dir)
          dir  (store/session-dir-for root "/tmp/my:repo/worktree")]
      (is (.isDirectory dir))
      (is (= (.getAbsolutePath (io/file root "--tmp-my-repo-worktree--"))
             (.getAbsolutePath dir))))))

(deftest write-and-append-test
  (testing "write-header! produces valid first line"
    (let [dir (tmp-dir)
          f   (io/file dir "test.ndedn")]
      (store/write-header! f "sess-1" "/my/project" nil)
      (let [lines  (slurp-lines f)
            header (edn/read-string
                    {:readers {'inst #(java.util.Date/from (Instant/parse %))}}
                    (first lines))]
        (is (= 1 (count lines)))
        (is (= :session (:type header)))
        (is (= "sess-1" (:id header)))
        (is (= "/my/project" (:worktree-path header)))
        (is (nil? (:parent-session-id header)))
        (is (nil? (:parent-session header))))))

  (testing "flush-journal! writes header + entries and append-entry-to-disk! appends one more line"
    (let [dir     (tmp-dir)
          f       (io/file dir "test.ndedn")
          entries [(thinking-entry :off)
                   (message-entry (user-msg "hello"))]]
      (store/flush-journal! f "sess-2" "/proj" nil entries)
      (is (= 3 (count (slurp-lines f))))
      (store/append-entry-to-disk! f (thinking-entry :medium))
      (is (= 4 (count (slurp-lines f)))))))

(deftest session-file-locking-test
  (testing "write operations fail while lock sidecar is held by another process handle"
    (let [dir       (tmp-dir)
          session-f (io/file dir "locked.ndedn")
          lock-f    (io/file dir "locked.ndedn.lock")
          raf       (RandomAccessFile. lock-f "rw")]
      (try
        (let [channel (.getChannel raf)
              held    (.lock channel)]
          (try
            (binding [store/*session-file-lock-max-attempts* 2
                      store/*session-file-lock-retry-ms*    1]
              (let [ex (try
                         (store/write-header! session-f "sess-lock" "/proj" nil)
                         nil
                         (catch clojure.lang.ExceptionInfo e
                           e))]
                (is (some? ex))
                (is (re-find #"Failed to acquire session file lock" (ex-message ex)))))
            (finally
              (.release ^FileLock held))))
        (finally
          (.close raf))))))

(deftest load-and-migration-test
  (testing "load-session-file returns nil for missing, empty, and invalid-header files"
    (is (nil? (store/load-session-file "/tmp/does-not-exist-psi.ndedn")))
    (let [f (File/createTempFile "psi-store-test" ".ndedn")]
      (spit f "")
      (is (nil? (store/load-session-file f)))
      (spit f "{:not :a :header}\n")
      (is (nil? (store/load-session-file f)))
      (.delete f)))

  (testing "load-session-file round-trips header and entries and skips malformed lines"
    (let [dir     (tmp-dir)
          f       (io/file dir "rt.ndedn")
          entries [(thinking-entry :off)
                   (message-entry (user-msg "hello"))
                   (message-entry (assistant-msg "world"))]]
      (store/flush-journal! f "sess-rt" "/my/cwd" nil entries)
      (spit f (str (slurp f) "THIS IS NOT EDN\n"))
      (let [loaded (store/load-session-file f)]
        (is (= "sess-rt" (get-in loaded [:header :id])))
        (is (= "/my/cwd" (get-in loaded [:header :worktree-path])))
        (is (= 3 (count (:entries loaded)))))))

  (testing "v1 entries gain id chain and v2 hook-message role migrates to custom"
    (let [f1 (File/createTempFile "psi-store-v1" ".ndedn")
          f2 (File/createTempFile "psi-store-v2" ".ndedn")]
      (spit f1 (str "{:type :session :id \"v1-sess\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/c\"}\n"
                    "{:kind :thinking-level :timestamp #inst \"2024-01-01T00:00:01Z\" :data {:thinking-level :off}}\n"
                    "{:kind :message :timestamp #inst \"2024-01-01T00:00:02Z\" :data {:message {:role \"user\" :content \"hi\"}}}\n"))
      (let [loaded (store/load-session-file f1)
            entries (:entries loaded)]
        (is (= 2 (count entries)))
        (is (string? (:id (first entries))))
        (is (nil? (:parent-id (first entries))))
        (is (= (:id (first entries)) (:parent-id (second entries)))))
      (spit f2 (str "{:type :session :version 2 :id \"v2-sess\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/c\"}\n"
                    "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :message :data {:message {:role \"hook-message\" :content \"x\"}}}\n"))
      (is (= "custom" (get-in (store/load-session-file f2) [:entries 0 :data :message :role])))))

  (testing "v3 header migration derives parent-session-id"
    (let [f (File/createTempFile "psi-store-v3" ".ndedn")]
      (spit f "{:type :session :version 3 :id \"v3-sess\" :timestamp #inst \"2024-01-01T00:00:00Z\" :worktree-path \"/c\" :parent-session \"/tmp/2024-01-01_parent-1.ndedn\"}\n")
      (let [loaded (store/load-session-file f)]
        (is (= 4 (get-in loaded [:header :version])))
        (is (= "parent-1" (get-in loaded [:header :parent-session-id])))))))

(deftest listing-and-discovery-test
  (testing "find-most-recent-session returns nil for empty or invalid-only directories"
    (let [dir (tmp-dir)
          bad (io/file dir "invalid.ndedn")]
      (is (nil? (store/find-most-recent-session dir)))
      (spit bad "not valid edn\n")
      (is (nil? (store/find-most-recent-session dir)))))

  (testing "find-most-recent-session and list-sessions preserve discovery return shapes"
    (let [root (tmp-dir)
          dir  (store/session-dir-for root "/proj")
          f1   (io/file dir "s1.ndedn")
          f2   (io/file dir "s2.ndedn")]
      (store/flush-journal! f1 "sess-a" "/proj" nil
                            [(message-entry (user-msg "first message"))
                             (message-entry (assistant-msg "reply"))])
      (Thread/sleep 10)
      (store/flush-journal! f2 "sess-b" "/proj" "sess-parent" "/tmp/parent.ndedn"
                            [(message-entry (user-msg "second message"))
                             {:id (str (java.util.UUID/randomUUID))
                              :parent-id nil
                              :timestamp (Instant/now)
                              :kind :session-info
                              :data {:name "Feature X"}}
                             (message-entry (assistant-msg "reply2"))])
      (.setLastModified f2 (+ (.lastModified f1) 1000))
      (let [result   (store/find-most-recent-session dir)
            sessions (store/list-sessions dir)
            info     (first sessions)]
        (is (= (.getAbsolutePath f2) result))
        (is (= 2 (count sessions)))
        (is (= "sess-b" (:id info)))
        (is (= "/proj" (:cwd info)))
        (is (= "/proj" (:worktree-path info)))
        (is (= "Feature X" (:name info)))
        (is (= "sess-parent" (:parent-session-id info)))
        (is (= "/tmp/parent.ndedn" (:parent-session-path info)))
        (is (= 2 (:message-count info)))
        (is (= "second message" (:first-message info)))))

    (testing "list-all-sessions supports explicit root override"
      (let [root (tmp-dir)
            dir1 (store/session-dir-for root "/proj-a")
            dir2 (store/session-dir-for root "/proj-b")
            f1   (io/file dir1 "a.ndedn")
            f2   (io/file dir2 "b.ndedn")]
        (store/flush-journal! f1 "sess-a" "/proj-a" nil [(message-entry (assistant-msg "a"))])
        (Thread/sleep 10)
        (store/flush-journal! f2 "sess-b" "/proj-b" nil [(message-entry (assistant-msg "b"))])
        (.setLastModified f2 (+ (.lastModified f1) 1000))
        (let [sessions (store/list-all-sessions root)]
          (is (= ["sess-b" "sess-a"] (mapv :id sessions))))))))
