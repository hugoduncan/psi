(ns psi.tui.test-harness.tmux-real-launcher-wrap
  "Real launcher wrapping scenario for TUI tmux integration tests.

   Uses the actual repo-local/invoked TUI launcher rather than the demo harness,
   then resumes a persisted session containing a long assistant message."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(def ^:private default-message-marker "This resumed paragraph should wrap")
(def ^:private default-selector-marker "Enter=select")
(def ^:private default-minimum-wrap-lines 3)
(def ^:private default-terminal-width 40)

(defn- encode-path-for-session-dir
  [path]
  (-> (str path)
      (str/replace #"^[/\\]" "")
      (str/replace #"[/\\:]" "-")))

(defn- sessions-root
  []
  (str (System/getProperty "user.home") "/.psi/agent/sessions"))

(defn write-wrap-fixture!
  [tmpdir assistant-text]
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
                     :parent-session-id nil
                     :parent-session nil}
        user-id     (str (java.util.UUID/randomUUID))
        user-entry  {:id user-id
                     :parent-id nil
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "user"
                                      :content [{:type :text
                                                 :text "show me the startup wrap regression"}]}}}
        asst-id     (str (java.util.UUID/randomUUID))
        asst-entry  {:id asst-id
                     :parent-id user-id
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "assistant"
                                      :content [{:type :text :text assistant-text}]}}}]
    (spit fixture (str (pr-str header) "\n"
                       (pr-str user-entry) "\n"
                       (pr-str asst-entry) "\n"))
    (str (.getAbsolutePath fixture))))

(defn delete-wrap-fixture!
  [fixture-path]
  (when fixture-path
    (let [f   (io/file fixture-path)
          dir (.getParentFile f)]
      (.delete f)
      (when (and dir (.isDirectory dir) (empty? (.list dir)))
        (.delete dir)))))

(defn- failure
  [target reason]
  {:status :failed
   :reason reason
   :session-name (:session-name target)
   :pane-id (:pane-id target)
   :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})

(defn- wrapped-message-lines
  [pane message-fragment]
  (->> (str/split-lines pane)
       (filter #(or (str/includes? % message-fragment)
                    (and (str/starts-with? % "   ")
                         (not (str/includes? % "Enter=select"))
                         (not (str/includes? % "Select a session to resume")))))
       vec))

(defn- wrapped-lines-valid?
  [lines visible-width minimum-wrap-lines]
  (cond
    (< (count lines) minimum-wrap-lines)
    {:ok? false
     :reason :message-not-wrapped
     :detail (str "Expected at least " minimum-wrap-lines
                  " wrapped lines, found " (count lines))
     :wrapped-lines lines}

    (some #(< visible-width (count %)) lines)
    {:ok? false
     :reason :line-exceeds-width
     :detail (str "Expected wrapped lines to fit within width " visible-width)
     :wrapped-lines lines}

    :else
    {:ok? true}))

(defn- scenario-result
  [target selector-marker step-timeout-ms message-marker minimum-wrap-lines terminal-width session-name*]
  (tmux/send-line! target "/resume")
  (cond
    (not (tmux/wait-for-marker target selector-marker step-timeout-ms))
    (failure target :selector-timeout)

    :else
    (do
      (tmux/send-key! target "Enter")
      (if-not (tmux/wait-for-marker target message-marker step-timeout-ms)
        (failure target :message-marker-timeout)
        (let [pane          (tmux/sanitize-pane-text (tmux/capture-pane-visible target))
              lines         (wrapped-message-lines pane message-marker)
              visible-width (or (tmux/pane-width target) terminal-width)
              wrap-check    (wrapped-lines-valid? lines visible-width minimum-wrap-lines)]
          (if-not (:ok? wrap-check)
            (merge (failure target (:reason wrap-check))
                   (dissoc wrap-check :ok? :reason))
            (do
              (tmux/send-line! target "/quit")
              (if (tmux/wait-for-java-exit target step-timeout-ms)
                {:status :passed
                 :session-name session-name*
                 :pane-id (:pane-id target)}
                (failure target :quit-timeout)))))))))

(defn run-real-launcher-wrap-scenario!
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           selector-marker
           keep-session-on-failure?
           terminal-width
           assistant-text
           message-marker
           minimum-wrap-lines]
    :or {startup-timeout-ms tmux/default-startup-timeout-ms
         step-timeout-ms tmux/default-step-timeout-ms
         ready-markers tmux/default-ready-markers
         selector-marker default-selector-marker
         keep-session-on-failure? false
         terminal-width default-terminal-width
         message-marker default-message-marker
         minimum-wrap-lines default-minimum-wrap-lines
         assistant-text "This resumed paragraph should wrap across multiple visible lines when the real TUI launcher restores a persisted transcript inside a narrow terminal pane rather than cutting the text off at the right edge."}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [tmpdir        (or working-dir
                              (str (System/getProperty "java.io.tmpdir")
                                   "/psi-wrap-real-launcher-" (System/currentTimeMillis)))
            _             (.mkdirs (io/file tmpdir))
            fixture-path  (write-wrap-fixture! tmpdir assistant-text)
            session-name* (or session-name (tmux/unique-session-name))
            launch        (or launch-command
                              (str (tmux/worktree-launch-command)
                                   " --cwd " (pr-str tmpdir)
                                   " --resume " (pr-str fixture-path)))]
        (try
          (let [target (tmux/start-session! {:session-name session-name*
                                             :working-dir (str (.getCanonicalPath (io/file ".")))
                                             :launch-command launch})
                result (if-not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms)
                         (failure target :startup-timeout)
                         (do
                           (tmux/resize-pane-width! target terminal-width)
                           (scenario-result target selector-marker step-timeout-ms message-marker minimum-wrap-lines terminal-width session-name*)))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (tmux/kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (tmux/primary-pane-id session-name*)}]
              (when-not keep-session-on-failure?
                (tmux/kill-session-if-exists! session-name*))
              {:status :failed
               :reason :exception
               :session-name session-name*
               :pane-id (:pane-id target)
               :error-message (or (ex-message t) (str t))
               :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}))
          (finally
            (delete-wrap-fixture! fixture-path)))))))
