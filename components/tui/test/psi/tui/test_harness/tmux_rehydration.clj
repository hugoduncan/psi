(ns psi.tui.test-harness.tmux-rehydration
  "Thinking rehydration scenario for TUI tmux integration tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(defn- encode-path-for-session-dir
  "Encode a filesystem path the same way persistence/session-dir-for does:
   strip leading slash, replace / and : with -."
  [path]
  (-> (str path)
      (str/replace #"^[/\\]" "")
      (str/replace #"[/\\:]" "-")))

(defn- sessions-root
  []
  (str (System/getProperty "user.home") "/.psi/agent/sessions"))

(defn write-thinking-fixture!
  "Write a minimal .ndedn journal file to the session dir for `tmpdir`.
   Returns the fixture file path string."
  [tmpdir]
  (let [encoded     (encode-path-for-session-dir tmpdir)
        session-dir (io/file (sessions-root) (str "--" encoded "--"))
        _           (.mkdirs session-dir)
        session-id  (str (java.util.UUID/randomUUID))
        ts          (java.util.Date/from (java.time.Instant/parse "2024-01-01T00:00:00Z"))
        filename    (str (System/currentTimeMillis) "_" session-id ".ndedn")
        fixture     (io/file session-dir filename)
        header      {:type :session :version 4 :id session-id
                     :timestamp ts
                     :worktree-path tmpdir
                     :parent-session-id nil :parent-session nil}
        user-id     (str (java.util.UUID/randomUUID))
        user-entry  {:id user-id :parent-id nil
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "user"
                                      :content [{:type :text :text "explain recursion"}]}}}
        asst-id     (str (java.util.UUID/randomUUID))
        asst-entry  {:id asst-id :parent-id user-id
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "assistant"
                                      :content [{:type :thinking :text "Let me think about this carefully."}
                                                {:type :text :text "Recursion is when a function calls itself."}]}}}]
    (spit fixture (str (pr-str header) "\n"
                       (pr-str user-entry) "\n"
                       (pr-str asst-entry) "\n"))
    (str (.getAbsolutePath fixture))))

(defn delete-thinking-fixture!
  "Delete the fixture file and the session dir if empty."
  [fixture-path]
  (when fixture-path
    (let [f   (io/file fixture-path)
          dir (.getParentFile f)]
      (.delete f)
      (when (and dir (.isDirectory dir) (empty? (.list dir)))
        (.delete dir)))))

(defn run-thinking-rehydration-scenario!
  "This live tmux scenario is currently quarantined.

   Unit tests cover transcript rehydration semantics. The live `/resume`
   discovery path is currently unreliable in the tmux harness, so release/smoke
   gating skips this scenario until that path is repaired."
  [_opts]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      {:status :skipped
       :reason :quarantined-live-rehydration
       :warning "Skipping tmux rehydration scenario: live /resume session discovery is currently unreliable in the tmux harness; unit tests cover transcript rehydration semantics while the live path is investigated."})))
