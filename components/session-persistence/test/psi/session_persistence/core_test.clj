(ns psi.session-persistence.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as core]
   [psi.session-persistence.core :as p]
   [psi.session-state.state :as ss]
   [psi.session-journal.codec :as codec])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-session-persistence-test"
                                      (into-array FileAttribute []))))

(defn- slurp-lines [^File f]
  (str/split-lines (slurp f)))

(defn- instant->millis-precision
  [^Instant instant]
  (Instant/ofEpochMilli (.toEpochMilli instant)))

(defn- entry->canonical
  [x]
  (cond
    (instance? Instant x) (instant->millis-precision x)
    (instance? java.util.Date x) (Instant/ofEpochMilli (.getTime ^java.util.Date x))
    (map? x) (reduce-kv (fn [m k v] (assoc m k (entry->canonical v))) {} x)
    (sequential? x) (mapv entry->canonical x)
    :else x))

(defn- read-lines
  [^File f]
  (->> (slurp-lines f)
       (keep codec/parse-line)
       (mapv entry->canonical)))

(defn- user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- execute-io-request!
  [request on-flushed!]
  (let [{:keys [op session-file session-id worktree-path parent-session-id parent-session-path entry entries]} request]
    (case op
      :append-entry
      (p/append-entry-to-disk! session-file entry)

      :flush-journal
      (do
        (p/flush-journal! session-file session-id worktree-path parent-session-id parent-session-path entries)
        (when on-flushed!
          (on-flushed!)))

      nil)))

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
  (let [entry (p/message-entry {:role "user" :content "hi"})]
    (is (= {:journal [entry]
            :flush-state {:flushed? true :session-file ::file}}
           (p/persistence-state {:journal [entry]
                                 :session-file ::file
                                 :flushed? true}))))
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

(deftest persistence-io-request-lazy-flush-test
  (testing "in-memory append semantics preserve memory before explicit IO"
    (let [ctx (core/create-context {:persist? false})
          sd  (core/new-session-in! ctx nil {:worktree-path "/tmp/ws"})
          sid (:session-id sd)
          before (p/all-entries-in ctx sid)
          entry (p/message-entry (user-msg "hi"))]
      (is (= entry (p/append-journal-entry-in! ctx sid entry)))
      (is (= (conj before entry) (p/all-entries-in ctx sid)))))

  (testing "no write before first assistant message"
    (let [dir (tmp-dir)
          file (io/file dir "lazy.ndedn")
          entries [(p/message-entry (user-msg "hi"))]
          request (p/persistence-io-request {:entries entries
                                             :flush-state {:flushed? false :session-file file}
                                             :session-id "sess-4"
                                             :worktree-path "/proj"
                                             :parent-session-id nil
                                             :parent-session-path nil})]
      (is (nil? request))
      (is (not (.exists file)))))

  (testing "bulk flush on first assistant message and later append"
    (let [dir          (tmp-dir)
          file         (io/file dir "lazy.ndedn")
          flush-state* (atom {:flushed? false :session-file file})
          entries*     (atom [(p/thinking-level-entry :off)
                              (p/message-entry (user-msg "hello"))
                              (p/message-entry (assistant-msg "world"))])]
      (execute-io-request! (p/persistence-io-request {:entries @entries*
                                                      :flush-state @flush-state*
                                                      :session-id "sess-5"
                                                      :worktree-path "/proj"
                                                      :parent-session-id nil
                                                      :parent-session-path nil})
                           #(swap! flush-state* assoc :flushed? true))
      (is (.exists file))
      (is (:flushed? @flush-state*))
      (let [lines (read-lines file)]
        (is (= {:type :session
                :version 4
                :id "sess-5"
                :worktree-path "/proj"
                :parent-session-id nil
                :parent-session nil}
               (dissoc (first lines) :timestamp)))
        (is (= (mapv entry->canonical @entries*)
               (subvec lines 1))))
      (let [lines-before (count (slurp-lines file))]
        (swap! entries* conj (p/thinking-level-entry :medium))
        (execute-io-request! (p/persistence-io-request {:entries @entries*
                                                        :flush-state @flush-state*
                                                        :session-id "sess-5"
                                                        :worktree-path "/proj"
                                                        :parent-session-id nil
                                                        :parent-session-path nil})
                             #(swap! flush-state* assoc :flushed? true))
        (is (= (inc lines-before) (count (slurp-lines file))))))))

(deftest persisted-session-store-wrapper-test
  (let [dir (tmp-dir)
        file (io/file dir "session.ndedn")
        entry (p/message-entry (assistant-msg "done"))]
    (p/flush-journal! file "sid-1" "/tmp/ws" nil nil [entry])
    (let [loaded (p/load-session-file file)]
      (is (= "sid-1" (get-in loaded [:header :id])))
      (is (= "/tmp/ws" (get-in loaded [:header :worktree-path])))
      (is (= [(entry->canonical entry)]
             (mapv entry->canonical (:entries loaded)))))))

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
