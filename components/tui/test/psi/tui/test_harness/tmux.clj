(ns psi.tui.test-harness.tmux
  "Reusable tmux-backed integration harness utilities for TUI tests."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]))

(def canonical-launch-command
  "exec psi --tui")

(def repo-local-launch-command
  "exec bb bb/psi.clj -- --tui")

(def default-startup-timeout-ms 120000)
(def default-step-timeout-ms 15000)
(def default-poll-interval-ms 100)
(def default-ready-markers ["刀:" "Type a message"])
(def default-help-marker "(anything else is sent to the agent)")
(def default-autocomplete-suggestions-marker "Suggestions")
(def default-autocomplete-selected-marker "▸ ")
(def default-capture-lines 3000)

(defn- run-sh
  [cmd]
  (shell/sh "bash" "-lc" cmd))

(defn tmux-available?
  []
  (zero? (:exit (run-sh "command -v tmux >/dev/null 2>&1"))))

(defn command-available?
  [cmd]
  (zero? (:exit (run-sh (format "command -v %s >/dev/null 2>&1" cmd)))))

(defn launcher-command
  "Resolve the best available TUI launch command, preferring the installed
   canonical `psi` binary when available.  For scenarios that need to exercise
   code in the current worktree, use [[worktree-launch-command]] instead."
  []
  (cond
    (command-available? "psi")
    canonical-launch-command

    (command-available? "bb")
    repo-local-launch-command

    :else
    canonical-launch-command))

(defn worktree-launch-command
  "Resolve a launch command that always runs code from the current worktree,
   preferring `bb` (repo-local) over the installed `psi` binary.
   Use this for scenarios that test features that may not yet be in the
   installed release."
  []
  (cond
    (command-available? "bb")
    repo-local-launch-command

    (command-available? "psi")
    canonical-launch-command

    :else
    canonical-launch-command))

(defn ci-env?
  []
  (boolean
   (some seq
         [(System/getenv "CI")
          (System/getenv "GITHUB_ACTIONS")
          (System/getenv "BUILDKITE")
          (System/getenv "CIRCLECI")
          (System/getenv "TEAMCITY_VERSION")
          (System/getenv "JENKINS_URL")])))

(defn tmux-preflight-result
  []
  (cond
    (tmux-available?)
    {:status :ok}

    (ci-env?)
    {:status :failed
     :reason :tmux-required-in-ci
     :error-message "tmux is required for TUI integration tests in CI but was not found on PATH"}

    :else
    {:status :skipped
     :reason :tmux-not-available
     :warning "Skipping TUI tmux integration test locally: tmux not found on PATH"}))

(defn unique-session-name
  []
  (str "psi-tui-it-" (System/currentTimeMillis) "-" (rand-int 1000000)))

(defn sanitize-pane-text
  [s]
  (-> (or s "")
      ansi/strip-ansi
      (str/replace #"\r" "\n")
      (str/replace #"\u0008" "")
      (str/replace #"\u000e|\u000f" "")))

(defn primary-pane-id
  [session-name]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s:0.0 '#{pane_id}'"
                        session-name))]
    (when (zero? exit)
      (str/trim out))))

(defn- pane-target
  [target]
  (let [{:keys [session-name pane-id]} (if (string? target)
                                         {:session-name target}
                                         target)]
    (or pane-id
        (primary-pane-id session-name)
        (str session-name ":0.0"))))

(defn capture-pane
  ([target]
   (capture-pane target {}))
  ([target _opts]
   (let [capture-lines (when (map? target) (:capture-lines target))
         {:keys [exit out err]}
         (run-sh (format "tmux capture-pane -pt %s -S -%d"
                         (pane-target target)
                         (or capture-lines default-capture-lines)))]
     (if (zero? exit)
       out
       (str "tmux-capture-pane-failed: " (or err ""))))))

(defn pane-current-command
  [target]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s '#{pane_current_command}'"
                        (pane-target target)))]
    (when (zero? exit)
      (str/trim out))))

(defn send-line!
  [target s]
  (let [pane (pane-target target)]
    (run-sh (format "tmux send-keys -l -t %s %s" pane (pr-str s)))
    (run-sh (format "tmux send-keys -t %s Enter" pane))))

(defn send-text!
  "Send literal text to the pane without pressing Enter."
  [target s]
  (let [pane (pane-target target)]
    (run-sh (format "tmux send-keys -l -t %s %s" pane (pr-str s)))))

(defn send-key!
  "Send a named tmux key (e.g. \"Escape\", \"Down\", \"Up\") to the pane."
  [target key-name]
  (run-sh (format "tmux send-keys -t %s %s" (pane-target target) key-name)))

(defn wait-until
  ([pred timeout-ms]
   (wait-until pred timeout-ms default-poll-interval-ms))
  ([pred timeout-ms poll-interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if (pred)
         true
         (if (>= (System/currentTimeMillis) deadline)
           false
           (do
             (Thread/sleep poll-interval-ms)
             (recur))))))))

(defn kill-session-if-exists!
  [session-name]
  (run-sh (format "tmux kill-session -t %s >/dev/null 2>&1 || true" session-name)))

(defn start-session!
  [{:keys [session-name working-dir launch-command]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (launcher-command)}}]
  (run-sh (format "tmux new-session -d -s %s -c %s"
                  session-name
                  (pr-str working-dir)))
  (let [pane-id (primary-pane-id session-name)
        target  {:session-name session-name
                 :pane-id pane-id}]
    (send-line! target launch-command)
    target))

(defn wait-for-any-marker
  [target markers timeout-ms]
  (wait-until
   (fn []
     (let [pane (sanitize-pane-text (capture-pane target))]
       (boolean (some #(str/includes? pane %) markers))))
   timeout-ms))

(defn wait-for-marker
  [target marker timeout-ms]
  (wait-until
   (fn []
     (str/includes? (sanitize-pane-text (capture-pane target)) marker))
   timeout-ms))

(defn wait-for-java-exit
  [target timeout-ms]
  (wait-until
   (fn []
     (not= "java" (pane-current-command target)))
   timeout-ms))

(defn- failure-result
  [target reason]
  {:status :failed
   :reason reason
   :session-name (:session-name target)
   :pane-id (:pane-id target)
   :pane-snapshot (sanitize-pane-text (capture-pane target))})

(defn run-basic-help-quit-scenario!
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           help-marker
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (launcher-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         help-marker default-help-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name session-name*
                                        :working-dir working-dir
                                        :launch-command launch-command})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           (send-line! target "/help")
                           (cond
                             (not (wait-for-marker target help-marker step-timeout-ms))
                             (failure-result target :help-timeout)

                             :else
                             (do
                               (send-line! target "/quit")
                               (if (wait-for-java-exit target step-timeout-ms)
                                 {:status :passed
                                  :session-name session-name*
                                  :pane-id (:pane-id target)}
                                 (failure-result target :quit-timeout))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))

(defn run-slash-autocomplete-scenario!
  "Prove that typing '/' opens a visible autocomplete menu with a selected suggestion,
   that moving selection with Down changes the highlighted row, and that Escape dismisses
   the menu cleanly before exiting.

   Scenario: boot -> ready -> type '/' -> 'Suggestions' visible + '▸ ' marker ->
             Down key -> '▸ ' still visible -> Escape -> '/quit' -> clean exit."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           suggestions-marker
           selected-marker
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (worktree-launch-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         suggestions-marker default-autocomplete-suggestions-marker
         selected-marker default-autocomplete-selected-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name session-name*
                                        :working-dir working-dir
                                        :launch-command launch-command})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           ;; type '/' to trigger slash-command autocomplete
                           (send-text! target "/")
                           (cond
                             (not (wait-for-marker target suggestions-marker step-timeout-ms))
                             (failure-result target :autocomplete-suggestions-timeout)

                             (not (str/includes?
                                   (sanitize-pane-text (capture-pane target))
                                   selected-marker))
                             (assoc (failure-result target :autocomplete-selected-marker-missing)
                                    :detail "Suggestions header appeared but '▸ ' selected marker was not visible")

                             :else
                             (do
                               ;; move selection down one row
                               (send-key! target "Down")
                               (cond
                                 (not (wait-for-marker target selected-marker step-timeout-ms))
                                 (failure-result target :autocomplete-post-down-marker-missing)

                                 :else
                                 (do
                                   ;; dismiss autocomplete with Escape
                                   (send-key! target "Escape")
                                   (Thread/sleep 200)
                                   ;; exit cleanly
                                   (send-line! target "/quit")
                                   (if (wait-for-java-exit target step-timeout-ms)
                                     {:status :passed
                                      :session-name session-name*
                                      :pane-id (:pane-id target)}
                                     (failure-result target :quit-timeout))))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))

(def default-thinking-marker "· ")
(def default-selector-marker "Enter=confirm")

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
        ts          "2024-01-01T00:00:00.000-00:00"
        filename    (str (System/currentTimeMillis) "_" session-id ".ndedn")
        fixture     (io/file session-dir filename)
        header      (str "{:type :session :version 4 :id \"" session-id "\""
                         " :timestamp #inst \"" ts "\""
                         " :worktree-path \"" tmpdir "\""
                         " :parent-session-id nil :parent-session nil}")
        user-id     (str (java.util.UUID/randomUUID))
        user-entry  (str "{:id \"" user-id "\" :parent-id nil"
                         " :timestamp #inst \"" ts "\""
                         " :kind :message"
                         " :data {:message {:role \"user\" :content [{:type :text :text \"explain recursion\"}]}}}")
        asst-id     (str (java.util.UUID/randomUUID))
        asst-entry  (str "{:id \"" asst-id "\" :parent-id \"" user-id "\""
                         " :timestamp #inst \"" ts "\""
                         " :kind :message"
                         " :data {:message {:role \"assistant\""
                         " :content [{:type :thinking :text \"Let me think about this carefully.\"}"
                         " {:type :text :text \"Recursion is when a function calls itself.\"}]}}}")]
    (spit fixture (str header "\n" user-entry "\n" asst-entry "\n"))
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
  "Prove that thinking rehydration and the · style are observable through a real
   terminal boundary.

   Scenario: write fixture → boot TUI (working-dir = tmpdir) → ready →
   /resume → selector → Enter → wait for '· ' → assert → /quit → clean exit."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           thinking-marker
           selector-marker
           keep-session-on-failure?]
    :or {launch-command (worktree-launch-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         thinking-marker default-thinking-marker
         selector-marker default-selector-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [tmpdir       (or working-dir
                             (str (System/getProperty "java.io.tmpdir")
                                  "/psi-thinking-it-" (System/currentTimeMillis)))
            _            (.mkdirs (io/file tmpdir))
            fixture-path (write-thinking-fixture! tmpdir)
            session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name session-name*
                                        :working-dir tmpdir
                                        :launch-command launch-command})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           (send-line! target "/resume")
                           (cond
                             (not (wait-for-marker target selector-marker step-timeout-ms))
                             (failure-result target :selector-timeout)

                             :else
                             (do
                               ;; select the first (only) session
                               (send-key! target "Enter")
                               (cond
                                 (not (wait-for-marker target thinking-marker step-timeout-ms))
                                 (failure-result target :thinking-marker-timeout)

                                 :else
                                 (do
                                   (send-line! target "/quit")
                                   (if (wait-for-java-exit target step-timeout-ms)
                                     {:status :passed
                                      :session-name session-name*
                                      :pane-id (:pane-id target)}
                                     (failure-result target :quit-timeout))))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result))
          (finally
            (delete-thinking-fixture! fixture-path)))))))

;; ── Resize scenario ──────────────────────────────────────────────────────────

(defn pane-width
  "Return the current width of the pane in columns, or nil on failure."
  [target]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s '#{pane_width}'"
                        (pane-target target)))]
    (when (zero? exit)
      (try (Long/parseLong (str/trim out))
           (catch Exception _ nil)))))

(defn resize-pane-width!
  "Resize the pane to COLS columns."
  [target cols]
  (run-sh (format "tmux resize-pane -t %s -x %d"
                  (pane-target target) cols)))

(defn check-layout-invariants
  "Check that a captured pane snapshot satisfies TUI layout invariants.

   Returns {:ok? true} when all checks pass, or
           {:ok? false :violations [...]} when one or more fail.

   Checks performed:

   :banner-at-column-0
     The line containing 'ψ Psi Agent Session' starts with 'ψ' (no leading
     spaces).  A repaint failure or display offset shifts the banner right,
     leaving blank columns before the first character.

   :banner-appears-once
     'ψ Psi Agent Session' appears exactly once.  A failed differential
     repaint can leave ghost copies of the previous render on screen,
     producing duplicate banner lines.

   :separator-at-column-0
     At least one '────' separator line starts with '─'.  If the display
     is shifted right the separator begins with spaces instead.

   :separator-spans-width
     The trimmed length of the first separator line is within 2 columns of
     `expected-width` (when provided).  After a resize the separator is
     reflowed to the new width; a stale repaint leaves it at the old width.

   :min-content-lines
     At least 4 non-blank lines are present.  Guards against a totally blank
     screen when the repaint produced no output at all.

   `pane-text` should be the output of `sanitize-pane-text`."
  ([pane-text]
   (check-layout-invariants pane-text nil))
  ([pane-text expected-width]
   (let [lines        (str/split-lines pane-text)
         non-blank    (remove str/blank? lines)
         banner-lines (filter #(str/includes? % "ψ Psi Agent Session") lines)
         banner-line  (first banner-lines)
         sep-lines    (filter #(str/includes? % "────") lines)
         sep-line     (first sep-lines)
         violations
         (cond-> []
           ;; 1. Banner present and starts at column 0
           (nil? banner-line)
           (conj {:check  :banner-at-column-0
                  :detail "No line containing 'ψ Psi Agent Session' found"})

           (and banner-line (not (str/starts-with? banner-line "ψ")))
           (conj {:check  :banner-at-column-0
                  :detail (str "Banner line has unexpected leading content: "
                               (pr-str (subs banner-line 0 (min 40 (count banner-line)))))})

           ;; 2. Banner appears exactly once
           (not= 1 (count banner-lines))
           (conj {:check  :banner-appears-once
                  :detail (str "Expected 1 banner line, found " (count banner-lines))})

           ;; 3. Separator present and starts at column 0
           (nil? sep-line)
           (conj {:check  :separator-at-column-0
                  :detail "No separator line (────) found"})

           (and sep-line (not (str/starts-with? sep-line "─")))
           (conj {:check  :separator-at-column-0
                  :detail (str "Separator line has unexpected leading content: "
                               (pr-str (subs sep-line 0 (min 40 (count sep-line)))))})

           ;; 4. Separator spans expected width (when provided)
           (and sep-line expected-width
                (> (Math/abs (- (count (str/trim sep-line)) expected-width)) 2))
           (conj {:check  :separator-spans-width
                  :detail (str "Separator length " (count (str/trim sep-line))
                               " differs from expected width " expected-width
                               " by more than 2 columns")})

           ;; 5. At least 4 non-blank lines
           (< (count non-blank) 4)
           (conj {:check  :min-content-lines
                  :detail (str "Only " (count non-blank)
                               " non-blank lines found (expected ≥ 4)")}))]
     (if (empty? violations)
       {:ok? true}
       {:ok? false :violations violations}))))

(defn run-resize-scenario!
  "Prove that the TUI repaints correctly after a terminal resize.

   Scenario:
   1. Boot → ready marker; check initial layout invariants
   2. Record initial pane width W
   3. Resize pane to W-resize-delta (narrower, floor 40)
   4. Wait for banner-marker to reappear; check layout invariants at new width
   5. Resize pane back to W
   6. Wait for banner-marker again; check layout invariants at restored width
   7. /quit → clean exit

   Layout invariants checked at each stage (via check-layout-invariants):
   - 'ψ Psi Agent Session' starts at column 0 (not shifted right)
   - banner appears exactly once (no double-render artefact)
   - separator '────' starts at column 0
   - separator length matches the current pane width (reflowed, not stale)
   - at least 4 non-blank lines present (screen not blank)

   This exercises the JLine Display WidthChangedRender path: on every
   width change the renderer clears its internal state and re-renders
   the full view from scratch."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           banner-marker
           resize-delta
           keep-session-on-failure?]
    :or {working-dir      (str (.getCanonicalPath (io/file ".")))
         launch-command   (worktree-launch-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms  default-step-timeout-ms
         ready-markers    default-ready-markers
         banner-marker    "ESC=interrupt"
         resize-delta     20
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name   session-name*
                                        :working-dir    working-dir
                                        :launch-command launch-command})
                result
                (cond
                  (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                  (failure-result target :startup-timeout)

                  :else
                  (let [initial-width (pane-width target)]
                    (if (nil? initial-width)
                      (assoc (failure-result target :pane-width-unavailable)
                             :detail "Could not read initial pane width")
                      ;; Check layout before any resize
                      (let [initial-snap  (sanitize-pane-text (capture-pane target))
                            initial-check (check-layout-invariants initial-snap initial-width)]
                        (if-not (:ok? initial-check)
                          (assoc (failure-result target :layout-invalid-before-resize)
                                 :detail     "Layout invariants failed before any resize"
                                 :violations (:violations initial-check))
                          (let [narrow-width (max 40 (- initial-width resize-delta))]
                            ;; Step 1: shrink
                            (resize-pane-width! target narrow-width)
                            (cond
                              (not (wait-for-marker target banner-marker step-timeout-ms))
                              (assoc (failure-result target :banner-missing-after-shrink)
                                     :detail (str "Banner not visible after resize to "
                                                  narrow-width " cols"))

                              :else
                              (let [narrow-snap  (sanitize-pane-text (capture-pane target))
                                    narrow-check (check-layout-invariants narrow-snap narrow-width)]
                                (if-not (:ok? narrow-check)
                                  (assoc (failure-result target :layout-invalid-after-shrink)
                                         :detail     (str "Layout invariants failed after resize to "
                                                          narrow-width " cols")
                                         :violations (:violations narrow-check))
                                  ;; Step 2: restore
                                  (do
                                    (resize-pane-width! target initial-width)
                                    (cond
                                      (not (wait-for-marker target banner-marker step-timeout-ms))
                                      (assoc (failure-result target :banner-missing-after-restore)
                                             :detail (str "Banner not visible after resize back to "
                                                          initial-width " cols"))

                                      :else
                                      (let [restored-snap  (sanitize-pane-text (capture-pane target))
                                            restored-check (check-layout-invariants
                                                            restored-snap initial-width)]
                                        (if-not (:ok? restored-check)
                                          (assoc (failure-result target :layout-invalid-after-restore)
                                                 :detail     (str "Layout invariants failed after "
                                                                  "restore to " initial-width " cols")
                                                 :violations (:violations restored-check))
                                          (do
                                            (send-line! target "/quit")
                                            (if (wait-for-java-exit target step-timeout-ms)
                                              {:status       :passed
                                               :session-name session-name*
                                               :pane-id      (:pane-id target)}
                                              (failure-result target :quit-timeout))))))))))))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id      (primary-pane-id session-name*)}
                  result {:status        :failed
                          :reason        :exception
                          :session-name  session-name*
                          :pane-id       (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))

;; ── Scripted streaming scenario ───────────────────────────────────────────────

(def default-streaming-marker "⠋")
(def default-tool-done-marker "✓")
(def default-thinking-prefix "· ")

(defn- edn-str
  "Serialize value to a compact EDN string suitable for shell env var injection."
  [v]
  (pr-str v))

(defn- demo-launch-command
  "Launch command for the scripted TUI demo (no live LLM required)."
  []
  "exec clojure -M:tui-demo")

(defn run-streaming-display-scenario!
  "Prove that the TUI renders thinking blocks, tool streaming, and tool result
   truncation correctly through a real terminal, without a live LLM.

   Uses PSI_TUI_DEMO_SCRIPT to inject pre-scripted events — no live LLM needed.

   Scenario steps:
   1. Boot → ready marker
   2. Submit 'think' → wait for '· ' (thinking prefix)
   3. Submit 'tool'  → wait for spinner (⠋), then done marker (✓)
   4. Assert content NOT visible in collapsed mode (no 'output-line-1')
   5. Press ctrl+o   → assert expanded content visible ('output-line-10')
   6. /quit → clean exit"
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [long-result   (str/join "\n" (map #(str "output-line-" %) (range 1 25)))
            script        (edn-str
                           [{:trigger "think"
                             :delay-ms 80
                             :events  [{:type :agent-event :event-kind :thinking-delta
                                        :content-index 0 :text "Reasoning about the question."}]
                             :done    {:role "assistant"
                                       :content [{:type :thinking :text "Reasoning about the question."}
                                                 {:type :text    :text "Here is the answer."}]}}
                            {:trigger "tool"
                             :delay-ms 80
                             :events  [{:type :agent-event :event-kind :tool-call-assembly
                                        :phase :end :content-index 0
                                        :tool-id "t1" :tool-name "bash"
                                        :arguments "{\"command\":\"ls\"}"}
                                       {:type :agent-event :event-kind :tool-start
                                        :tool-id "t1" :tool-name "bash"}
                                       {:type :agent-event :event-kind :tool-executing
                                        :tool-id "t1" :tool-name "bash"
                                        :parsed-args {:command "ls"}}
                                       {:type :agent-event :event-kind :tool-result
                                        :tool-id "t1" :tool-name "bash"
                                        :content [{:type :text :text long-result}]
                                        :is-error false}]
                             :done    {:role "assistant"
                                       :content [{:type :text :text "Done."}]}}])
            session-name* (or session-name (unique-session-name))
            launch        (or launch-command (demo-launch-command))]
        (try
          (let [target (start-session!
                        {:session-name session-name*
                         :working-dir  working-dir
                         :launch-command
                         (str "PSI_TUI_DEMO_SCRIPT=" (pr-str script)
                              " PSI_TUI_DEMO_MODEL=demo " launch)})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           ;; Turn 1: thinking
                           (send-line! target "think")
                           (if (not (wait-for-marker target default-thinking-prefix step-timeout-ms))
                             (failure-result target :thinking-prefix-not-visible)
                             ;; Turn 2: tool with long result
                             (do
                               (send-line! target "tool")
                               (cond
                                 (not (wait-for-marker target default-streaming-marker step-timeout-ms))
                                 (failure-result target :tool-spinner-not-visible)

                                 (not (wait-for-marker target default-tool-done-marker step-timeout-ms))
                                 (failure-result target :tool-done-marker-not-visible)

                                 :else
                                 (let [pane (sanitize-pane-text (capture-pane target))]
                                   (if (str/includes? pane "output-line-1")
                                     (assoc (failure-result target :content-visible-when-collapsed)
                                            :detail
                                            "Tool content should not be visible in collapsed mode")
                                     ;; ctrl+o expand
                                     (do
                                       (send-key! target "C-o")
                                       (if (not (wait-for-marker target "output-line-10" step-timeout-ms))
                                         (failure-result target :expand-not-visible)
                                         (do
                                           (send-line! target "/quit")
                                           (if (wait-for-java-exit target step-timeout-ms)
                                             {:status :passed
                                              :session-name session-name*
                                              :pane-id (:pane-id target)}
                                             (failure-result target :quit-timeout))))))))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id      (primary-pane-id session-name*)}
                  result {:status        :failed
                          :reason        :exception
                          :session-name  session-name*
                          :pane-id       (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))
