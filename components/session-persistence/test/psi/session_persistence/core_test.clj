(ns psi.session-persistence.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as core]
   [psi.session-persistence.core :as p]
   [psi.session-state.state :as ss])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-session-persistence-test"
                                      (into-array FileAttribute []))))

(defn- slurp-lines [^File f]
  (str/split-lines (slurp f)))

(defn- user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(deftest path-and-subtree-ownership-test
  (is (= [:agent-session :sessions "sid" :persistence :journal]
         (p/session-journal-path "sid")))
  (is (= [:agent-session :sessions "sid" :persistence :flush-state]
         (p/session-flush-state-path "sid")))
  (is (= {:flushed? false :session-file nil}
         (p/flush-state)))
  (is (= {:flushed? true :session-file ::file}
         (p/flush-state ::file true)))
  (is (= {:journal []
          :flush-state {:flushed? false :session-file nil}}
         (p/persistence-state)))
  (is (= {:journal [(p/message-entry {:role "user" :content "hi"})]
          :flush-state {:flushed? true :session-file ::file}}
         (p/persistence-state {:journal [(p/message-entry {:role "user" :content "hi"})]
                               :session-file ::file
                               :flushed? true})))
  (is (= {:agent-session {:sessions {"sid" {:persistence {:journal []
                                                          :flush-state {:flushed? false
                                                                        :session-file nil}}}}}}
         (p/initialize-persistence-state {} "sid" {}))))

(deftest pure-persistence-request-shaping-test
  (testing "returns nil before first assistant message"
    (let [entry (p/message-entry (user-msg "hi"))]
      (is (nil? (p/persistence-io-request {:entries [entry]
                                           :flush-state {:flushed? false :session-file (io/file (tmp-dir) "lazy.ndedn")}
                                           :session-id "sid"
                                           :worktree-path "/proj"
                                           :parent-session-id nil
                                           :parent-session-path nil})))))

  (testing "returns flush request on first assistant-visible flush"
    (let [entries [(p/message-entry (user-msg "hello"))
                   (p/message-entry (assistant-msg "world"))]
          file    (io/file (tmp-dir) "flush.ndedn")]
      (is (= {:op :flush-journal
              :session-id "sid"
              :session-file file
              :worktree-path "/proj"
              :parent-session-id "parent"
              :parent-session-path "/tmp/parent.ndedn"
              :entries entries}
             (p/persistence-io-request {:entries entries
                                        :flush-state {:flushed? false :session-file file}
                                        :session-id "sid"
                                        :worktree-path "/proj"
                                        :parent-session-id "parent"
                                        :parent-session-path "/tmp/parent.ndedn"})))))

  (testing "returns append request after initial flush"
    (let [entries [(p/message-entry (user-msg "hello"))
                   (p/message-entry (assistant-msg "world"))
                   (p/thinking-level-entry :medium)]
          file    (io/file (tmp-dir) "append.ndedn")]
      (is (= {:op :append-entry
              :session-id "sid"
              :session-file file
              :worktree-path "/proj"
              :parent-session-id nil
              :parent-session-path nil
              :entry (last entries)}
             (p/persistence-io-request {:entries entries
                                        :flush-state {:flushed? true :session-file file}
                                        :session-id "sid"
                                        :worktree-path "/proj"
                                        :parent-session-id nil
                                        :parent-session-path nil}))))))

(deftest mark-flushed-root-update-test
  (let [state {:agent-session {:sessions {"sid" {:persistence {:journal []
                                                               :flush-state {:flushed? false
                                                                             :session-file ::file}}}}}}
        updated ((p/mark-flushed-root-update "sid") state)]
    (is (false? (get-in state [:agent-session :sessions "sid" :persistence :flush-state :flushed?])))
    (is (true? (get-in updated [:agent-session :sessions "sid" :persistence :flush-state :flushed?])))
    (is (= ::file (get-in updated [:agent-session :sessions "sid" :persistence :flush-state :session-file])))))

(deftest append-journal-entry-in-test
  (let [ctx (core/create-context)
        sd  (core/new-session-in! ctx nil {})
        sid (:session-id sd)
        before (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        entry (p/message-entry {:role "user" :content [{:type :text :text "hi"}]})]
    (is (= entry (p/append-journal-entry-in! ctx sid entry)))
    (is (= (conj before entry)
           (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))))))

(deftest persist-journal-in-lazy-flush-test
  (testing "ctx append-first semantics preserve memory even before flush"
    (let [ctx (core/create-context {:persist? false})
          sd  (core/new-session-in! ctx nil {:worktree-path "/tmp/ws"})
          sid (:session-id sd)
          entry (p/message-entry (user-msg "hi"))]
      (is (= entry (p/append-entry-in! ctx sid entry)))
      (is (= [entry] (p/all-entries-in ctx sid)))))

  (testing "no write before first assistant message"
    (let [dir (tmp-dir)
          f   (io/file dir "lazy.ndedn")
          j   (p/create-journal)
          fs  (p/create-flush-state)]
      (swap! fs assoc :session-file f)
      (p/append-entry! j (p/message-entry (user-msg "hi")))
      (p/persist-entry! j fs "sess-4" "/proj" nil)
      (is (not (.exists f)))))

  (testing "bulk flush on first assistant message and later append"
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
        (is (= (inc lines-before) (count (slurp-lines f))))))))

(deftest persisted-session-store-wrapper-test
  (let [dir (tmp-dir)
        file (io/file dir "session.ndedn")
        entry (p/message-entry (assistant-msg "done"))]
    (p/flush-journal! file "sid-1" "/tmp/ws" nil nil [entry])
    (let [loaded (p/load-session-file file)]
      (is (= "sid-1" (get-in loaded [:header :id])))
      (is (= "/tmp/ws" (get-in loaded [:header :worktree-path])))
      (is (= [entry] (vec (:entries loaded)))))))

(deftest entry-constructors-test
  (let [msg (user-msg "hello")]
    (is (= :message (:kind (p/message-entry msg))))
    (is (= msg (get-in (p/message-entry msg) [:data :message])))
    (is (= :thinking-level (:kind (p/thinking-level-entry :medium))))
    (is (= :model (:kind (p/model-entry "anthropic" "claude-3"))))
    (is (= :label (:kind (p/label-entry "target" "label"))))
    (is (= :session-info (:kind (p/session-info-entry "Session Name"))))
    (is (= :custom-message (:kind (p/custom-message-entry :note "x" nil false))))
    (is (= :compaction (:kind (p/compaction-entry {:summary "compact"
                                                   :first-kept-entry-id "e1"
                                                   :tokens-before 10
                                                   :details nil}
                                                  false))))
    (is (= :branch-summary (:kind (p/branch-summary-entry "e1" "summary" nil "label" false))))))

(deftest persistence-public-surface-test
  (testing "lock retry tuning is owned by session-journal.store, not the canonical persistence ns"
    (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-retry-ms*)))
    (is (nil? (ns-resolve 'psi.session-persistence.core '*session-file-lock-max-attempts*)))))
