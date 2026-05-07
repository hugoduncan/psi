(ns psi.agent-session.persistence-test
  "Session-facing persistence tests: in-memory journal helpers, lazy flush orchestration,
  and representative use of the extracted session-journal store boundary."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as core]
   [psi.agent-session.persistence :as p]
   [psi.session-state.model :as session]
   [psi.session-state.state :as ss])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

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

  (testing "entries-of-kind, entries-up-to, and last-entry-of-kind work"
    (let [j  (p/create-journal)
          e1 (p/append-entry! j (session/make-entry :model {:provider "a" :model-id "m"}))
          e2 (p/append-entry! j (session/make-entry :thinking-level {:thinking-level :off}))
          e3 (p/append-entry! j (session/make-entry :model {:provider "b" :model-id "n"}))]
      (is (= [e1 e3] (p/entries-of-kind j :model)))
      (is (= [e1 e2] (p/entries-up-to j (:id e2))))
      (is (= e2 (p/last-entry-of-kind j :thinking-level)))))

  (testing "message projection helpers extract only message entries"
    (let [j   (p/create-journal)
          msg (user-msg "hello")]
      (p/append-entry! j (p/message-entry msg))
      (p/append-entry! j (p/thinking-level-entry :off))
      (is (= [msg] (p/messages-from-entries j)))
      (is (= [msg] (vec (p/messages-up-to j nil)))))))

(deftest persist-entry-lazy-flush-test
  (testing "no write before first assistant message"
    (let [dir (tmp-dir)
          f   (io/file dir "lazy.ndedn")
          j   (p/create-journal)
          fs  (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      (p/append-entry! j (p/message-entry (user-msg "hi")))
      (p/persist-entry! j fs "sess-4" "/proj" nil)
      (is (not (.exists f)))))

  (testing "bulk flush on first assistant message and later append go through store boundary"
    (let [dir (tmp-dir)
          f   (io/file dir "lazy.ndedn")
          j   (p/create-journal)
          fs  (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      (p/append-entry! j (p/thinking-level-entry :off))
      (p/append-entry! j (p/message-entry (user-msg "hello")))
      (p/append-entry! j (p/message-entry (assistant-msg "world")))
      (p/persist-entry! j fs "sess-5" "/proj" nil)
      (is (.exists f))
      (is (:flushed? @fs))
      (is (= 4 (count (slurp-lines f))))
      (let [lines-before (count (slurp-lines f))]
        (p/append-entry! j (p/thinking-level-entry :medium))
        (p/persist-entry! j fs "sess-5" "/proj" nil)
        (is (= (inc lines-before) (count (slurp-lines f)))))))

  (testing "no-op when session-file is nil"
    (let [j  (p/create-journal)
          fs (p/create-flush-state)]
      (p/append-entry! j (p/message-entry (assistant-msg "x")))
      (is (nil? (p/persist-entry! j fs "sess-7" "/proj" nil))))))

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
