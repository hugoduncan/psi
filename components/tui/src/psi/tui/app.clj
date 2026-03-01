(ns psi.tui.app
  "Psi TUI application using charm.clj's Elm Architecture.

   Replaces the custom ProcessTerminal + differential renderer with
   charm.clj's JLine3-backed terminal and Elm update/view loop.

   Public API:
     start!        — blocking entry point for --tui mode
     make-init     — create init fn (for testing)
     make-update   — create update fn (for testing)
     view          — pure view fn (for testing)

   Architecture:
     init    → [initial-state nil]
     update  → (state, msg) → [new-state, cmd]
     view    → state → string

   Agent integration:
     When user submits text, `run-agent-fn!` is called with (text queue).
     It starts the agent in a background thread and puts the result
     on the queue. A polling command reads from the queue, driving
     the spinner animation on each poll timeout."
  (:require
   [charm.core :as charm]
   [charm.input.handler :as charm-input-handler]
   [charm.input.keymap] ; loaded so we can patch before use
   [charm.message :as msg]
   [charm.render.core]  ; loaded so we can patch enter-alt-screen!
   [charm.terminal :as charm-term]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.persistence :as persist]
   [psi.tui.ansi :as ansi]
   [psi.tui.extension-ui :as ext-ui]
   [psi.tui.markdown :as md])
  (:import
   [java.time Instant]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]
   [org.jline.keymap KeyMap]
   [org.jline.terminal Terminal]))

;; ── JLine compat patch ──────────────────────────────────────
;; charm.clj v0.1.42: bind-from-capability! calls (String. ^chars seq)
;; but JLine 3.30+ KeyMap/key returns String, not char[].
;; Patch the private fn at load time, before create-keymap is called.

(alter-var-root
 #'charm.input.keymap/bind-from-capability!
 (constantly
  (fn [^KeyMap keymap ^Terminal terminal cap event]
    (when terminal
      (when-let [seq-val (KeyMap/key terminal cap)]
        (let [^String seq-str (if (string? seq-val)
                                seq-val
                                (String. ^chars seq-val))]
          (when (and (pos? (count seq-str))
                     (= (int (.charAt seq-str 0)) 27))
            (.bind keymap event (subs seq-str 1)))))))))

;; ── Alt-screen fix ───────────────────────────────────────────
;; charm.clj v0.1.42: create-renderer stores :alt-screen from opts
;; in the renderer atom. enter-alt-screen! checks
;; (when-not (:alt-screen @renderer)) — which short-circuits because
;; the flag is already true. Result: alt-screen is never entered,
;; so the TUI runs inline in the main buffer and Display cursor
;; tracking desyncs on content height changes.

(alter-var-root
 #'charm.render.core/enter-alt-screen!
 (constantly
  (fn [renderer]
    (let [terminal (:terminal @renderer)]
      (charm-term/enter-alt-screen terminal)
      (charm-term/clear-screen terminal)
      (charm-term/cursor-home terminal))
    (swap! renderer assoc :alt-screen true))))

;; ── Resize repaint fix ──────────────────────────────────────
;; On terminal height changes, JLine Display can retain stale rows from the
;; previous frame in some terminals. Force a hard repaint + clear so shrinking
;; and re-expanding the viewport never leaves garbage lines behind.

(alter-var-root
 #'charm.render.core/update-size!
 (constantly
  (fn [renderer width height]
    (let [{:keys [display height old-height]}
          (assoc @renderer :old-height (:height @renderer))
          height-changed? (not= old-height height)]
      (.resize ^org.jline.utils.Display display height width)
      (swap! renderer assoc :width width :height height)
      (when height-changed?
        ;; Reset Display diff baseline and hard-clear terminal buffer.
        (.clear ^org.jline.utils.Display display)
        (let [terminal (:terminal @renderer)]
          (charm-term/clear-screen terminal)
          (charm-term/cursor-home terminal)))))))

;; ── Extended key fallback patch ─────────────────────────────
;; Some terminals emit modified keys as CSI-u or modifyOtherKeys
;; sequences that charm.clj currently leaves as :unknown. Decode the
;; subset we need for the prompt UX.

(def ^:private kitty-csi-u-pattern #"^\[(\d+);(\d+)u$")
(def ^:private modify-other-keys-pattern #"^\[27;(\d+);(\d+)~$")

(defn- kitty-mod-code->mods
  [mod-code]
  (let [c (dec mod-code)]
    {:shift (pos? (bit-and c 1))
     ;; Treat Super/Meta as Alt so existing key bindings continue to work
     ;; (e.g. cmd+backspace => alt+backspace delete-word-backward).
     :alt   (or (pos? (bit-and c 2))    ; alt
                (pos? (bit-and c 8))    ; super
                (pos? (bit-and c 32)))  ; meta
     :ctrl  (pos? (bit-and c 4))}))

(defn- keycode->event
  [code]
  (cond
    (or (= code 13) (= code 10)) {:type :enter}
    (or (= code 127) (= code 8)) {:type :backspace}
    (<= 32 code 126)             {:type :runes :runes (str (char code))}
    :else                        nil))

(defn- parse-extended-key
  [escape-seq]
  (or
   (when-let [[_ code-s mod-s] (re-matches kitty-csi-u-pattern (or escape-seq ""))]
     (let [code (Long/parseLong code-s)
           mods (kitty-mod-code->mods (Long/parseLong mod-s))]
       (when-let [base (keycode->event code)]
         (merge base mods))))
   (when-let [[_ mod-s code-s] (re-matches modify-other-keys-pattern (or escape-seq ""))]
     (let [mods (kitty-mod-code->mods (Long/parseLong mod-s))
           code (Long/parseLong code-s)]
       (when-let [base (keycode->event code)]
         (merge base mods))))))

(defn- normalize-parsed-event
  [parsed]
  (if (and (= :runes (:type parsed))
           (:alt parsed)
           (string? (:runes parsed))
           (= 1 (count ^String (:runes parsed))))
    (let [ch (:runes parsed)]
      (cond
        (or (= ch "\r") (= ch "\n")) (-> parsed (dissoc :runes) (assoc :type :enter))
        (or (= ch "\u007f") (= ch "\u0008")) (-> parsed (dissoc :runes) (assoc :type :backspace))
        (= ch " ") (-> parsed (dissoc :runes) (assoc :type :space))
        :else parsed))
    parsed))

(alter-var-root
 #'charm-input-handler/parse-input
 (fn [orig]
   (fn
     ([byte-val]
      (normalize-parsed-event (orig byte-val)))
     ([byte-val escape-seq]
      (normalize-parsed-event (orig byte-val escape-seq)))
     ([byte-val escape-seq keymap]
      (let [parsed (orig byte-val escape-seq keymap)
            parsed (if (and (= :unknown (:type parsed))
                            (string? escape-seq))
                     (or (parse-extended-key escape-seq)
                         parsed)
                     parsed)]
        (normalize-parsed-event parsed))))))

;; ── Styles ──────────────────────────────────────────────────

(def ^:private title-style   (charm/style :fg charm/magenta :bold true))
(def ^:private user-style    (charm/style :fg charm/cyan :bold true))
(def ^:private assist-style  (charm/style :fg charm/green :bold true))
(def ^:private error-style   (charm/style :fg charm/red))
(def ^:private dim-style     (charm/style :fg 240))
(def ^:private sep-style     (charm/style :fg 240))
(def ^:private tool-style    (charm/style :fg charm/yellow :bold true))
(def ^:private tool-ok-style (charm/style :fg charm/green))
(def ^:private tool-err-style (charm/style :fg charm/red))
(def ^:private tool-dim-style (charm/style :fg 245))

;; ── Spinner frames (driven by poll ticks, no separate timer) ──

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private prompt-history-max-entries 100)

(defn- initial-prompt-input-state
  []
  {:autocomplete {:prefix ""
                  :candidates []
                  :selected-index 0
                  :context nil
                  :trigger-mode nil}
   :history {:entries []
             :browse-index nil
             :max-entries prompt-history-max-entries}
   :timing {:last-ctrl-c-ms nil
            :last-escape-ms nil}})

(def ^:private builtin-slash-commands
  ["/quit" "/exit" "/resume" "/new" "/status" "/help"])

(defn- input-value [state]
  (charm/text-input-value (:input state)))

(defn- input-pos [state]
  (let [v (input-value state)]
    (min (max 0 (or (get-in state [:input :pos]) (count v))) (count v))))

(defn- set-input-value
  [state s]
  (assoc state :input (charm/text-input-set-value (:input state) s)))

(defn- clear-history-browse
  [state]
  (assoc-in state [:prompt-input-state :history :browse-index] nil))

(defn- set-input-model
  [state input]
  (-> state
      (assoc :input input)
      clear-history-browse))

(defn- now-ms [] (System/currentTimeMillis))

(defn- double-press-window-ms
  [state]
  (or (:double-press-window-ms state) 500))

(defn- within-double-press-window?
  [last-ms now-ms window-ms]
  (and (some? last-ms)
       (<= (- now-ms last-ms) window-ms)))

(defn- append-assistant-status
  [state text]
  (if (str/blank? text)
    state
    (update state :messages conj {:role :assistant :text text})))

(defn- merge-queued-and-draft
  [queued-text draft-text]
  (let [queued (str/trim (or queued-text ""))
        draft  (str/trim (or draft-text ""))]
    (cond
      (and (str/blank? queued) (str/blank? draft)) ""
      (str/blank? queued) draft
      (str/blank? draft) queued
      :else (str queued "\n" draft))))

(defn- history-entries
  [state]
  (vec (get-in state [:prompt-input-state :history :entries] [])))

(defn- history-max-entries
  [state]
  (or (get-in state [:prompt-input-state :history :max-entries])
      prompt-history-max-entries))

(defn- record-history-entry
  [state text]
  (let [entry (str/trim (or text ""))]
    (if (str/blank? entry)
      state
      (let [entries      (history-entries state)
            last-entry   (peek entries)
            max-entries  (history-max-entries state)
            next-entries (if (= last-entry entry)
                           entries
                           (let [appended (conj entries entry)
                                 extra    (max 0 (- (count appended) max-entries))]
                             (if (pos? extra)
                               (vec (subvec appended extra))
                               appended)))]
        (-> state
            (assoc-in [:prompt-input-state :history :entries] next-entries)
            (assoc-in [:prompt-input-state :history :browse-index] nil))))))

(defn- history-current-entry
  [state idx]
  (let [entries (history-entries state)]
    (when (and (some? idx)
               (<= 0 idx)
               (< idx (count entries)))
      (nth entries idx))))

(defn- browse-history
  [state direction]
  (let [entries (history-entries state)
        n       (count entries)
        idx     (get-in state [:prompt-input-state :history :browse-index])
        input   (input-value state)]
    (if (zero? n)
      state
      (case direction
        :up
        (cond
          (and (nil? idx) (str/blank? input))
          (let [new-idx (dec n)]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          (some? idx)
          (let [new-idx (max 0 (dec idx))]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          :else
          state)

        :down
        (if (some? idx)
          (if (>= idx (dec n))
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] nil)
                (set-input-value ""))
            (let [new-idx (min (dec n) (inc idx))]
              (-> state
                  (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                  (set-input-value (history-current-entry state new-idx)))))
          state)

        state))))

(defn- whitespace-char?
  [^Character c]
  (Character/isWhitespace c))

(defn- token-context-at-cursor
  "Derive token context at cursor.
   Returns {:text :pos :before :after :token :token-start :token-end :context :prefix}"
  [state]
  (let [text        (input-value state)
        pos         (input-pos state)
        before      (subs text 0 pos)
        after       (subs text pos)
        token-start (or (some->> (keep-indexed (fn [i ch]
                                                 (when (whitespace-char? ch) i))
                                               before)
                                 last
                                 inc)
                        0)
        token       (subs before token-start)
        context     (cond
                      (and (= token-start 0)
                           (str/starts-with? token "/"))
                      :slash_command

                      (str/starts-with? token "@")
                      :file_reference

                      :else
                      :file_path)]
    {:text text
     :pos pos
     :before before
     :after after
     :token token
     :token-start token-start
     :token-end pos
     :context context
     :prefix token}))

(defn- slash-candidates
  [state prefix]
  (let [templates  (mapv (fn [{:keys [name]}] (str "/" name)) (:prompt-templates state))
        skills     (mapv (fn [{:keys [name]}] (str "/skill:" name)) (:skills state))
        ext-cmds   (vec (:extension-command-names state))
        all        (->> (concat builtin-slash-commands templates skills ext-cmds)
                        (remove str/blank?)
                        distinct
                        sort)
        pfx        (or prefix "/")
        lowered    (str/lower-case pfx)]
    (->> all
         (filter #(str/starts-with? (str/lower-case %) lowered))
         (mapv (fn [cmd]
                 {:value cmd
                  :label cmd
                  :description nil
                  :kind :slash_command
                  :is-directory false})))))

(defn- rel-path
  [cwd ^java.io.File f]
  (let [cwd-path (.toPath (io/file cwd))
        file-path (.toPath f)]
    (-> (.relativize cwd-path file-path)
        (.normalize)
        (.toString))))

(defn- hidden-or-git-path?
  [rel]
  (or (= ".git" rel)
      (str/starts-with? rel ".git/")
      (str/includes? rel "/.git/")
      (str/ends-with? rel "/.git")))

(defn- quote-if-needed [s]
  (if (str/includes? s " ")
    (str "\"" s "\"")
    s))

(defn- file-reference-candidates
  [state prefix]
  (let [cwd        (:cwd state)
        token      (or prefix "@")
        typed      (subs token (min 1 (count token)))
        typed      (str/replace typed #"^\"" "")
        query      (str/lower-case typed)
        root       (io/file cwd)]
    (->> (file-seq root)
         (remove #(.equals root %))
         (map (fn [^java.io.File f]
                (let [rel (rel-path cwd f)]
                  {:file f :rel rel :dir? (.isDirectory f)})))
         (remove #(hidden-or-git-path? (:rel %)))
         (filter (fn [{:keys [rel]}]
                   (or (str/blank? query)
                       (str/includes? (str/lower-case rel) query))))
         (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
         (take 100)
         (mapv (fn [{:keys [rel dir?]}]
                 (let [v (cond-> rel
                           dir? (str "/"))
                       v (quote-if-needed v)]
                   {:value v
                    :label v
                    :description nil
                    :kind :file_reference
                    :is-directory dir?}))))))

(defn- path-completion-candidates
  [state prefix]
  (let [cwd          (:cwd state)
        token        (or prefix "")
        slash-idx    (str/last-index-of token "/")
        [dir-part name-part] (if slash-idx
                               [(subs token 0 (inc slash-idx))
                                (subs token (inc slash-idx))]
                               ["" token])
        base         (io/file cwd dir-part)
        dir-exists?  (.isDirectory base)]
    (if-not dir-exists?
      []
      (->> (.listFiles base)
           (filter some?)
           (map (fn [^java.io.File f]
                  (let [nm (.getName f)
                        rel (str dir-part nm)]
                    {:name nm
                     :rel rel
                     :dir? (.isDirectory f)})))
           (remove #(hidden-or-git-path? (:rel %)))
           (filter (fn [{:keys [name]}]
                     (str/starts-with? (str/lower-case name)
                                       (str/lower-case name-part))))
           (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
           (mapv (fn [{:keys [rel dir?]}]
                   (let [v (cond-> rel
                             dir? (str "/"))]
                     {:value v
                      :label v
                      :description nil
                      :kind :file_path
                      :is-directory dir?})))))))

(defn- clear-autocomplete
  [state]
  (assoc-in state [:prompt-input-state :autocomplete]
            {:prefix ""
             :candidates []
             :selected-index 0
             :context nil
             :trigger-mode nil}))

(defn- open-autocomplete
  [state {:keys [prefix context trigger-mode token-start token-end]} candidates]
  (if (seq candidates)
    (assoc-in state [:prompt-input-state :autocomplete]
              {:prefix prefix
               :candidates candidates
               :selected-index 0
               :context context
               :trigger-mode trigger-mode
               :token-start token-start
               :token-end token-end})
    (clear-autocomplete state)))

(defn- context-candidates
  [state context prefix]
  (case context
    :slash_command (slash-candidates state prefix)
    :file_reference (file-reference-candidates state prefix)
    :file_path (path-completion-candidates state prefix)
    []))

(defn- refresh-autocomplete
  [state trigger-mode]
  (let [{:keys [context prefix token-start token-end]} (token-context-at-cursor state)
        candidates (context-candidates state context prefix)]
    (open-autocomplete state {:prefix prefix
                              :context context
                              :trigger-mode trigger-mode
                              :token-start token-start
                              :token-end token-end}
                       candidates)))

(defn- autocomplete-open?
  [state]
  (seq (get-in state [:prompt-input-state :autocomplete :candidates])))

(defn- move-autocomplete-selection
  [state delta]
  (let [cands (get-in state [:prompt-input-state :autocomplete :candidates])
        cnt   (count cands)]
    (if (zero? cnt)
      state
      (update-in state [:prompt-input-state :autocomplete :selected-index]
                 (fn [i]
                   (let [i (or i 0)]
                     (mod (+ i delta) cnt)))))))

(defn- drop-duplicate-closing-quote-in-after
  [replacement after]
  (if (and (str/ends-with? replacement "\"")
           (str/starts-with? (or after "") "\""))
    (subs after 1)
    after))

(defn- apply-selected-autocomplete
  [state]
  (let [ac         (get-in state [:prompt-input-state :autocomplete])
        idx        (or (:selected-index ac) 0)
        candidate  (nth (:candidates ac) idx nil)
        text       (input-value state)
        start      (or (:token-start ac) (count text))
        end        (or (:token-end ac) (count text))
        before     (subs text 0 (min start (count text)))
        after      (subs text (min end (count text)))
        context    (:context ac)]
    (if-not candidate
      state
      (let [base-value (:value candidate)
            replacement (case context
                          :file_reference (str "@" base-value)
                          base-value)
            after       (drop-duplicate-closing-quote-in-after replacement after)
            replacement (if (and (= :file_reference context)
                                 (not (:is-directory candidate)))
                          (str replacement " ")
                          replacement)
            text'      (str before replacement after)]
        (-> state
            (set-input-value text')
            clear-autocomplete)))))

(declare printable-key)

(defn- maybe-auto-open-autocomplete
  [state key-token]
  (let [ch (printable-key key-token)]
    (if-not (or (= ch "/") (= ch "@"))
      state
      (let [{:keys [context token]} (token-context-at-cursor state)]
        (cond
          (and (= ch "/") (= :slash_command context))
          (refresh-autocomplete state :auto)

          (and (= ch "@") (= :file_reference context)
               (str/starts-with? token "@"))
          (refresh-autocomplete state :auto)

          :else
          state)))))

(defn- open-tab-autocomplete
  [state]
  (let [{:keys [token token-start token-end]} (token-context-at-cursor state)
        context    (if (and (= token-start 0) (str/starts-with? token "/"))
                     :slash_command
                     :file_path)
        candidates (context-candidates state context token)
        opened     (open-autocomplete state {:prefix token
                                             :context context
                                             :trigger-mode :tab
                                             :token-start token-start
                                             :token-end token-end}
                                      candidates)]
    (if (and (= :file_path context)
             (= 1 (count candidates)))
      (apply-selected-autocomplete opened)
      opened)))

;; ── Custom message predicates ───────────────────────────────

(defn agent-result? [m] (= :agent-result (:type m)))
(defn agent-error?  [m] (= :agent-error  (:type m)))
(defn agent-poll?   [m] (= :agent-poll   (:type m)))
(defn agent-event?  [m] (= :agent-event  (:type m)))
(defn external-message? [m] (= :external-message (:type m)))

(defn- key-token->string
  "Normalize charm key token to a string when possible."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (name k)
    (char? k)    (str k)
    :else        nil))

(defn- printable-key
  "Return a single printable character string for key token, else nil."
  [k]
  (let [s (key-token->string k)]
    (when (and (string? s)
               (= 1 (count s))
               (>= (int (.charAt ^String s 0)) 32))
      s)))

;; ── Dialog state helpers ────────────────────────────────────

(defn- has-active-dialog? [state]
  (boolean (some-> (:ui-state-atom state) ext-ui/active-dialog)))

(defn- handle-dialog-key
  "Route keypress to the active dialog. Returns [new-state cmd] or nil
   if no dialog is active."
  [state m]
  (when-let [ui-atom (:ui-state-atom state)]
    (when-let [dialog (ext-ui/active-dialog ui-atom)]
      (cond
        ;; Escape cancels any dialog
        (msg/key-match? m "escape")
        (do (ext-ui/cancel-dialog! ui-atom)
            [state nil])

        ;; Enter confirms / submits
        (msg/key-match? m "enter")
        (case (:kind dialog)
          :confirm
          (do (ext-ui/resolve-dialog! ui-atom (:id dialog) true)
              [state nil])

          :select
          (let [idx     (or (:selected-index dialog) 0)
                options (:options dialog)
                value   (when (seq options) (:value (nth options idx nil)))]
            (when value
              (ext-ui/resolve-dialog! ui-atom (:id dialog) value))
            [state nil])

          :input
          (let [text (or (:input-text dialog) "")]
            (ext-ui/resolve-dialog! ui-atom (:id dialog) text)
            [state nil])

          ;; fallback
          [state nil])

        ;; For select: up/down to change selection
        (and (= :select (:kind dialog)) (msg/key-match? m "up"))
        (do (swap! ui-atom update-in [:dialog-queue :active :selected-index]
                   (fn [i] (max 0 (dec (or i 0)))))
            [state nil])

        (and (= :select (:kind dialog)) (msg/key-match? m "down"))
        (do (swap! ui-atom update-in [:dialog-queue :active :selected-index]
                   (fn [i] (min (dec (count (:options dialog)))
                                (inc (or i 0)))))
            [state nil])

        ;; For input: printable chars and backspace
        (and (= :input (:kind dialog)) (msg/key-match? m "backspace"))
        (do (swap! ui-atom update-in [:dialog-queue :active :input-text]
                   (fn [s] (let [s (or s "")]
                             (if (pos? (count s)) (subs s 0 (dec (count s))) s))))
            [state nil])

        (and (= :input (:kind dialog)) (msg/key-press? m))
        (let [ch (printable-key (:key m))]
          (when ch
            (swap! ui-atom update-in [:dialog-queue :active :input-text]
                   (fn [s] (str (or s "") ch))))
          [state nil])

        :else [state nil]))))

;; ── Session selector ────────────────────────────────────────
;;
;; State lives under :session-selector in the app state map:
;;   {:sessions     [SessionInfo ...]   — loaded for current scope
;;    :all-sessions [SessionInfo ...]   — loaded for "all" scope (lazy)
;;    :scope        :current | :all
;;    :search       ""                  — current search string
;;    :selected     0                   — cursor index into filtered list
;;    :loading?     false}
;;
;; Sessions are displayed as a flat filtered list (no tree for simplicity).
;; Tab toggles scope.  Type to search.  ↑/↓ navigate.  Enter selects.  Esc cancels.

(defn- format-age
  "Human-readable age string from a timestamp (Instant or Date)."
  [ts]
  (when ts
    (let [epoch-ms (cond
                     (instance? Instant ts) (.toEpochMilli ^Instant ts)
                     (instance? java.util.Date ts) (.getTime ^java.util.Date ts)
                     :else nil)]
      (when epoch-ms
        (let [diff-ms   (- (System/currentTimeMillis) epoch-ms)
              diff-mins (quot diff-ms 60000)
              diff-hrs  (quot diff-ms 3600000)
              diff-days (quot diff-ms 86400000)]
          (cond
            (< diff-mins 1)   "now"
            (< diff-mins 60)  (str diff-mins "m")
            (< diff-hrs 24)   (str diff-hrs "h")
            (< diff-days 7)   (str diff-days "d")
            (< diff-days 30)  (str (quot diff-days 7) "w")
            (< diff-days 365) (str (quot diff-days 30) "mo")
            :else             (str (quot diff-days 365) "y")))))))

(defn- filter-sessions
  "Return sessions matching `query` (case-insensitive substring on first-message + name)."
  [sessions query]
  (if (str/blank? query)
    sessions
    (let [q (str/lower-case (str/trim query))]
      (filterv (fn [s]
                 (or (str/includes? (str/lower-case (or (:first-message s) "")) q)
                     (str/includes? (str/lower-case (or (:name s) "")) q)
                     (str/includes? (str/lower-case (or (:cwd s) "")) q)))
               sessions))))

(defn- session-selector-init
  "Build the initial session selector state for `cwd`."
  [cwd current-session-file]
  (let [dir      (persist/session-dir-for cwd)
        sessions (persist/list-sessions dir)]
    {:sessions              sessions
     :all-sessions          nil       ;; loaded lazily on Tab
     :scope                 :current
     :search                ""
     :selected              0
     :loading?              false
     :current-session-file  current-session-file}))

(defn- selector-sessions
  "Return the active sessions list for the current scope."
  [{:keys [scope sessions all-sessions]}]
  (if (= :current scope) sessions (or all-sessions [])))

(defn- selector-filtered
  "Return filtered + bounded sessions."
  [sel-state]
  (filter-sessions (selector-sessions sel-state) (:search sel-state)))

(defn- selector-clamp
  "Clamp :selected to valid range after list changes."
  [sel-state]
  (let [n (count (selector-filtered sel-state))]
    (update sel-state :selected #(max 0 (min % (dec (max 1 n)))))))

(defn- selector-move
  "Move cursor by `delta`, clamped."
  [sel-state delta]
  (let [n (count (selector-filtered sel-state))]
    (selector-clamp
     (update sel-state :selected #(max 0 (min (dec (max 1 n)) (+ % delta)))))))

(defn- selector-type
  "Append/delete character from search string."
  [sel-state key-token]
  (let [key-str    (key-token->string key-token)
        new-search (cond
                     (= "backspace" key-str)
                     (let [s (:search sel-state)]
                       (if (pos? (count s)) (subs s 0 (dec (count s))) s))

                     :else
                     (if-let [ch (printable-key key-token)]
                       (str (:search sel-state) ch)
                       (:search sel-state)))]
    (selector-clamp (assoc sel-state :search new-search))))

;; ── Commands ────────────────────────────────────────────────

(defn poll-cmd
  "Command that polls the shared event queue with a short timeout.
   Returns :agent-result, :agent-error, :agent-event, :external-message,
   :agent-aborted, or :agent-poll.

   Queue payloads accepted:
   - {:kind :done  :result ...}
   - {:kind :error :message ...}
   - {:kind :aborted :message ... :queued-text ...}
   - {:type :agent-event ...}       ; progress events
   - {:type :external-message ...}  ; async extension transcript message
   "
  [^LinkedBlockingQueue queue]
  (charm/cmd
   (fn []
     (if-let [event (.poll queue 120 TimeUnit/MILLISECONDS)]
       (cond
         (= :done (:kind event))
         {:type :agent-result :result (:result event)}

         (= :error (:kind event))
         {:type :agent-error :error (:message event)}

         (= :aborted (:kind event))
         {:type :agent-aborted
          :message (:message event)
          :queued-text (:queued-text event)}

         (= :agent-event (:type event))
         event

         (= :external-message (:type event))
         event

         :else
         {:type :agent-poll})
       {:type :agent-poll}))))

;; ── Init ────────────────────────────────────────────────────

(defn- key-debug-enabled?
  []
  (contains? #{"1" "true" "yes" "on"}
             (some-> (System/getenv "PSI_TUI_DEBUG_KEYS") str/lower-case)))

(defn make-init
  "Create an init function for the charm program.
   `model-name` is displayed in the banner.
   `query-fn`  — optional (fn [eql-query]) → result map; used to
                  introspect the session for prompt templates, etc.
   `ui-state-atom` — optional extension UI state atom; when present,
                     the TUI renders widgets, status, notifications,
                     and dialogs from extensions.
   `opts` map:
     :cwd                  — working directory string (for /resume)
     :current-session-file — current session file path (highlighted in selector)
     :resume-fn!           — (fn [session-path]) called when user selects a session;
                              returns {:messages [...], :tool-calls {...}, :tool-order [...]}
     :dispatch-fn          — (fn [text]) → command result map or nil; central command dispatch
     :on-interrupt-fn!     — (fn [state]) -> {:queued-text str? :message str?} | nil
     :on-queue-input-fn!   — (fn [text state]) -> {:message str?} | nil
                              called when Enter is pressed while streaming
     :double-press-window-ms — ctrl+c / escape timing window (default 500)
     :double-escape-action — :tree | :fork | :none (default :none)
     :event-queue          — shared LinkedBlockingQueue for agent + extension events"
  ([model-name] (make-init model-name nil))
  ([model-name query-fn] (make-init model-name query-fn nil))
  ([model-name query-fn ui-state-atom] (make-init model-name query-fn ui-state-atom {}))
  ([model-name query-fn ui-state-atom opts]
   (fn []
     (let [introspected (when query-fn
                          (query-fn [:psi.agent-session/prompt-templates
                                     :psi.agent-session/skills
                                     :psi.agent-session/extension-summary
                                     :psi.agent-session/session-file
                                     :psi.extension/command-names]))]
       (let [queue (or (:event-queue opts) (LinkedBlockingQueue.))]
         [{:messages              []
           :phase                 :idle
           :error                 nil
           :input                 (charm/text-input :prompt "刀: "
                                                    :placeholder "Type a message…"
                                                    :focused true)
           :spinner-frame         0
           :model-name            model-name
           :prompt-templates      (or (:psi.agent-session/prompt-templates introspected) [])
           :skills                (or (:psi.agent-session/skills introspected) [])
           :extension-summary     (or (:psi.agent-session/extension-summary introspected) {})
           :extension-command-names (vec (:psi.extension/command-names introspected))
           :query-fn              query-fn
           :ui-state-atom         ui-state-atom
           :dispatch-fn           (:dispatch-fn opts)
           :on-interrupt-fn!      (:on-interrupt-fn! opts)
           :on-queue-input-fn!    (:on-queue-input-fn! opts)
           :double-press-window-ms (or (:double-press-window-ms opts) 500)
           :double-escape-action  (or (:double-escape-action opts) :none)
           :cwd                   (or (:cwd opts) (System/getProperty "user.dir"))
           :current-session-file  (or (:current-session-file opts)
                                      (:psi.agent-session/session-file introspected))
           :resume-fn!            (:resume-fn! opts)
           :session-selector      nil   ;; non-nil when /resume is active
           :prompt-input-state    (initial-prompt-input-state)
           :queue                 queue
           :width                 80
           :height                24
           ;; Live turn progress
           :stream-text           nil
           :tool-calls            {}
           :tool-order            []
           :tools-expanded?       (ext-ui/get-tools-expanded ui-state-atom)}
          (poll-cmd queue)])))))

;; ── Update helpers ──────────────────────────────────────────

(defn- open-session-selector
  "Enter session-selector phase."
  [state]
  (let [sel (session-selector-init (:cwd state) (:current-session-file state))]
    [(-> state
         (assoc :phase :selecting-session
                :session-selector sel)
         (set-input-model (charm/text-input-reset (:input state))))
     nil]))

(defn- handle-dispatch-result
  "Translate a command dispatch result map into [new-state cmd].
   Returns nil if the result is nil (not a command)."
  [state result]
  (when result
    (case (:type result)
      :quit
      [state charm/quit-cmd]

      :resume
      (open-session-selector state)

      :new-session
      [(-> state
           (assoc :messages []
                  :error    nil
                  :force-clear? true)
           (set-input-model (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      :text
      [(-> state
           (set-input-model (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      (:login-error :logout)
      [(-> state
           (set-input-model (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      :extension-cmd
      (let [output (try
                     (when-let [handler (:handler result)]
                       (let [captured (with-out-str (handler (:args result)))]
                         (when-not (str/blank? captured)
                           (str/trimr captured))))
                     (catch Exception e
                       (timbre/warn "Extension command error:" (ex-message e))
                       (str "[command error: " (ex-message e) "]")))]
        [(cond-> (set-input-model state (charm/text-input-reset (:input state)))
           output (update :messages conj {:role :assistant :text output}))
         nil])

      ;; Login start — show URL. For callback-server providers, the
      ;; dispatch-fn in main.clj kicks off async completion. For manual-code
      ;; providers, the next input will be treated as the auth code.
      :login-start
      [(-> state
           (set-input-model (charm/text-input-reset (:input state)))
           (update :messages conj
                   {:role :assistant
                    :text (str "🔑 Login to " (get-in result [:provider :name])
                               "\n\nOpen this URL in your browser:\n" (:url result)
                               (if (:uses-callback-server result)
                                 "\n\nWaiting for browser callback…"
                                 "\n\nPaste the authorization code below ↓"))}))
       nil]

      ;; Fallback — treat as text
      [(-> state
           (set-input-model (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (str result)}))
       nil])))

(defn- handle-selector-key
  "Handle a keypress while the session selector is open.
  Returns [new-state cmd]."
  [state m]
  (let [sel       (:session-selector state)
        key-token (when (msg/key-press? m) (:key m))]
    (cond
      ;; Escape — cancel, return to idle
      (msg/key-match? m "escape")
      [(assoc state :phase :idle :session-selector nil) nil]

      ;; Ctrl+C — quit
      (msg/key-match? m "ctrl+c")
      [state charm/quit-cmd]

      ;; Tab — toggle scope
      (msg/key-match? m "tab")
      (let [new-scope (if (= :current (:scope sel)) :all :current)
            new-sel   (if (and (= :all new-scope) (nil? (:all-sessions sel)))
                        ;; Lazily load all sessions on first Tab to :all
                        (let [all (persist/list-all-sessions)]
                          (-> sel
                              (assoc :scope :all :all-sessions all)
                              selector-clamp))
                        (-> sel
                            (assoc :scope new-scope)
                            selector-clamp))]
        [(assoc state :session-selector new-sel) nil])

      ;; Up
      (msg/key-match? m "up")
      [(update state :session-selector selector-move -1) nil]

      ;; Down
      (msg/key-match? m "down")
      [(update state :session-selector selector-move 1) nil]

      ;; Enter — select the highlighted session
      (msg/key-match? m "enter")
      (let [filtered (selector-filtered sel)
            chosen   (nth filtered (:selected sel) nil)]
        (if chosen
          (let [path         (:path chosen)
                resume-fn    (:resume-fn! state)
                ;; resume-fn! returns {:messages [...]
                ;;                      :tool-calls {...}
                ;;                      :tool-order [...]}.
                ;; Fallback keeps older callback shape ([{:role ... :text ...} ...]).
                restored     (when resume-fn (resume-fn path))
                restored-msgs (if (map? restored) (:messages restored) restored)
                restored-tool-calls (if (map? restored) (:tool-calls restored) nil)
                restored-tool-order (if (map? restored) (:tool-order restored) nil)
                new-state    (-> state
                                 (assoc :phase            :idle
                                        :session-selector nil
                                        :current-session-file path
                                        :messages         (or restored-msgs [])
                                        :stream-text      nil
                                        :tool-calls       (or restored-tool-calls {})
                                        :tool-order       (or restored-tool-order [])))]
            [new-state nil])
          ;; Nothing selected — just close
          [(assoc state :phase :idle :session-selector nil) nil]))

      ;; Backspace / printable chars — update search
      (msg/key-press? m)
      [(update state :session-selector selector-type key-token) nil]

      :else [state nil])))

(defn- submit-to-agent
  "Start the agent with `text`, return [new-state cmd]."
  [state run-agent-fn! text]
  (let [queue (:queue state)]
    (run-agent-fn! text queue)
    [(-> state
         (update :messages conj {:role :user :text text})
         (assoc :phase         :streaming
                :error         nil
                :spinner-frame 0
                :stream-text   nil)
         (set-input-model (charm/text-input-reset (:input state))))
     (poll-cmd queue)]))

(defn- submit-input
  "Extract text from input, start agent, return [new-state cmd].
   Commands are dispatched via the dispatch-fn stored in state.
   Non-command input is forwarded to the agent."
  [state run-agent-fn!]
  (let [text (str/trim (charm/text-input-value (:input state)))]
    (cond
      (str/blank? text)
      [state nil]

      ;; Dispatch commands via central dispatcher
      :else
      (let [state       (record-history-entry state text)
            dispatch-fn (:dispatch-fn state)
            result      (when dispatch-fn (dispatch-fn text))]
        (if result
          (handle-dispatch-result state result)
          ;; Not a command — send to agent
          (submit-to-agent state run-agent-fn! text))))))

(defn- continue-input-line
  "Insert a newline in the text input instead of submitting.
   Supports modifier-enter and trailing backslash continuation."
  [state]
  (let [value       (charm/text-input-value (:input state))
        backslash?  (str/ends-with? value "\\")
        value'      (if backslash?
                      (subs value 0 (dec (count value)))
                      value)
        next-input  (charm/text-input-set-value (:input state) (str value' "\n"))]
    [(set-input-model state next-input) nil]))

(defn- delete-prev-word
  "Delete previous word from charm text input state.
   Needed because charm currently matches plain `backspace` before
   `alt+backspace` when modifiers are present."
  [input]
  (let [s   (charm/text-input-value input)
        pos (long (or (:pos input) (count s)))]
    (if (<= pos 0)
      input
      (let [i1 (loop [i (dec pos)]
                 (if (and (>= i 0)
                          (Character/isWhitespace (.charAt s i)))
                   (recur (dec i))
                   i))
            i2 (loop [i i1]
                 (if (and (>= i 0)
                          (not (Character/isWhitespace (.charAt s i))))
                   (recur (dec i))
                   i))
            start (inc i2)
            s'    (str (subs s 0 start) (subs s pos))]
        (-> input
            (assoc :value (vec s'))
            (assoc :pos start))))))

(defn- handle-agent-event
  "Process an intermediate progress event from the executor."
  [state event]
  (let [kind (:event-kind event)]
    (case kind
      :text-delta
      [(assoc state :stream-text (:text event)) nil]

      :tool-start
      (let [id   (:tool-id event)
            tc   {:name      (:tool-name event)
                  :args      ""
                  :status    :pending
                  :result    nil
                  :is-error  false
                  :expanded? (boolean (:tools-expanded? state))}]
        [(-> state
             (assoc-in [:tool-calls id] tc)
             (update :tool-order conj id))
         nil])

      :tool-delta
      [(assoc-in state [:tool-calls (:tool-id event) :args]
                 (:arguments event))
       nil]

      :tool-executing
      [(-> state
           (assoc-in [:tool-calls (:tool-id event) :status] :running)
           (assoc-in [:tool-calls (:tool-id event) :parsed-args]
                     (:parsed-args event)))
       nil]

      :tool-execution-update
      [(-> state
           (assoc-in [:tool-calls (:tool-id event) :status] :running)
           (assoc-in [:tool-calls (:tool-id event) :content] (:content event))
           (assoc-in [:tool-calls (:tool-id event) :details] (:details event))
           (assoc-in [:tool-calls (:tool-id event) :result]
                     (or (:result-text event)
                         (some->> (:content event)
                                  (keep (fn [block]
                                          (when (= :text (:type block))
                                            (:text block))))
                                  (str/join "\n"))))
           (assoc-in [:tool-calls (:tool-id event) :is-error]
                     (boolean (:is-error event))))
       nil]

      :tool-result
      [(-> state
           (assoc-in [:tool-calls (:tool-id event) :status]
                     (if (:is-error event) :error :success))
           (assoc-in [:tool-calls (:tool-id event) :content] (:content event))
           (assoc-in [:tool-calls (:tool-id event) :details] (:details event))
           (assoc-in [:tool-calls (:tool-id event) :result]
                     (or (:result-text event)
                         (some->> (:content event)
                                  (keep (fn [block]
                                          (when (= :text (:type block))
                                            (:text block))))
                                  (str/join "\n"))))
           (assoc-in [:tool-calls (:tool-id event) :is-error]
                     (boolean (:is-error event)))
           (assoc-in [:tool-calls (:tool-id event) :expanded?]
                     (boolean (:tools-expanded? state))))
       nil]

      ;; unknown event-kind — ignore
      [state nil])))

(defn- handle-agent-result
  "Process completed agent result."
  [state result]
  (let [text   (str/join
                (keep #(when (= :text (:type %)) (:text %))
                      (:content result)))
        errors (keep #(when (= :error (:type %)) (:text %))
                     (:content result))
        error  (first errors)
        display (if (seq text) text "(no response)")]
    [(-> state
         (update :messages conj {:role :assistant :text display})
         (assoc :phase       :idle
                :error       error
                :stream-text nil))
     (poll-cmd (:queue state))]))

(defn- handle-agent-poll
  "Agent still running — advance spinner, keep polling."
  [state]
  (let [n (count spinner-frames)]
    [(update state :spinner-frame #(mod (inc %) n))
     (poll-cmd (:queue state))]))

(defn- handle-ctrl-c
  [state]
  (let [now          (now-ms)
        window-ms    (double-press-window-ms state)
        last-clear   (get-in state [:prompt-input-state :timing :last-ctrl-c-ms])]
    (if (within-double-press-window? last-clear now window-ms)
      [(assoc-in state [:prompt-input-state :timing :last-ctrl-c-ms] nil)
       charm/quit-cmd]
      [(-> state
           (set-input-value "")
           (assoc-in [:prompt-input-state :timing :last-ctrl-c-ms] now))
       nil])))

(defn- handle-ctrl-d
  [state]
  (if (str/blank? (input-value state))
    [state charm/quit-cmd]
    [state nil]))

(defn- handle-idle-escape
  [state]
  (let [current-text   (input-value state)
        now            (now-ms)
        window-ms      (double-press-window-ms state)
        action         (:double-escape-action state :none)
        last-escape    (get-in state [:prompt-input-state :timing :last-escape-ms])
        second-escape? (within-double-press-window? last-escape now window-ms)]
    (cond
      (not (str/blank? current-text))
      [state nil]

      (= action :none)
      [state nil]

      second-escape?
      (case action
        :tree
        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (append-assistant-status "Double Escape action '/tree' is not available in this runtime."))
         nil]

        :fork
        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (append-assistant-status "Double Escape action '/fork' is not available in this runtime."))
         nil]

        [(-> state
             (assoc-in [:prompt-input-state :timing :last-escape-ms] nil)
             (append-assistant-status (str "Unsupported double Escape action: " (pr-str action))))
         nil])

      :else
      [(assoc-in state [:prompt-input-state :timing :last-escape-ms] now) nil])))

(defn- handle-streaming-escape
  [state]
  (if-let [interrupt-fn (:on-interrupt-fn! state)]
    (let [{:keys [queued-text message]} (or (interrupt-fn state) {})
          merged-text (merge-queued-and-draft queued-text (input-value state))
          next-state  (-> state
                          (set-input-value merged-text)
                          (assoc :phase :idle
                                 :stream-text nil)
                          (clear-autocomplete)
                          (assoc-in [:prompt-input-state :timing :last-escape-ms] (now-ms))
                          (append-assistant-status (or message "Interrupted.")))]
      [next-state (poll-cmd (:queue state))])
    [(append-assistant-status state "Interrupt unavailable in this runtime.") nil]))

(defn- handle-streaming-submit
  "Queue draft input as steering/follow-up while the agent is streaming."
  [state]
  (let [text     (str/trim (input-value state))
        queue-fn (:on-queue-input-fn! state)]
    (cond
      (str/blank? text)
      [state (poll-cmd (:queue state))]

      queue-fn
      (let [result  (try
                      (queue-fn text state)
                      (catch Exception e
                        {:message (str "Failed to queue input: " (ex-message e))}))
            message (or (:message result)
                        "Queued for next turn.")]
        [(-> state
             (set-input-value "")
             (clear-autocomplete)
             (append-assistant-status message))
         (poll-cmd (:queue state))])

      :else
      [(append-assistant-status state "Queueing input is unavailable in this runtime.")
       (poll-cmd (:queue state))])))

;; ── Update ──────────────────────────────────────────────────

(defn make-update
  "Create an update function.

   `run-agent-fn!` is called with (text queue) and should start the
   agent in a background thread that puts {:kind :done :result msg}
   or {:kind :error :message str} on queue when finished."
  [run-agent-fn!]
  (fn [state m]
    ;; One-shot render flag used by view for explicit full-screen clears.
    (let [state (if (:force-clear? state)
                  (assoc state :force-clear? false)
                  state)
          ;; Keep app state in sync with extension-controlled tools-expanded state.
          state (if-let [ui-atom (:ui-state-atom state)]
                  (assoc state :tools-expanded? (ext-ui/get-tools-expanded ui-atom))
                  state)]
      ;; Dismiss expired notifications on every tick
      (when-let [ui-atom (:ui-state-atom state)]
        (ext-ui/dismiss-expired! ui-atom)
        (ext-ui/dismiss-overflow! ui-atom))

      (when (and (key-debug-enabled?) (msg/key-press? m))
        (println (str "[key-debug] key=" (pr-str (:key m))
                      " ctrl=" (boolean (:ctrl m))
                      " alt=" (boolean (:alt m))
                      " shift=" (boolean (:shift m)))))

      (cond
      ;; Ctrl+C — clear first, then quit on second press within window.
        (msg/key-match? m "ctrl+c")
        (handle-ctrl-c state)

      ;; Ctrl+D — exit only when input is empty.
        (and (= :idle (:phase state))
             (msg/key-match? m "ctrl+d"))
        (handle-ctrl-d state)

      ;; Escape closes autocomplete first.
        (and (= :idle (:phase state))
             (autocomplete-open? state)
             (msg/key-match? m "escape"))
        [(clear-autocomplete state) nil]

      ;; Escape in streaming interrupts active work.
        (and (= :streaming (:phase state))
             (msg/key-match? m "escape"))
        (handle-streaming-escape state)

      ;; Escape while idle delegates to interrupt/double-escape behavior.
        (and (= :idle (:phase state))
             (not (has-active-dialog? state))
             (msg/key-match? m "escape"))
        (handle-idle-escape state)

      ;; Window resize
        (msg/window-size? m)
        [(assoc state
                :width (:width m)
                :height (:height m)
                :force-clear? true)
         nil]

      ;; Dialog active — route all key input to dialog handler
        (and (has-active-dialog? state) (msg/key-press? m))
        (or (handle-dialog-key state m) [state nil])

      ;; Agent progress event (tool start, delta, result, text delta)
        (agent-event? m)
        (let [[new-state cmd] (handle-agent-event state m)]
          [new-state (or cmd (poll-cmd (:queue state)))])

      ;; Async external transcript message (e.g. extension background completion)
        (external-message? m)
        (let [msg        (:message m)
              text       (or (some #(when (= :text (:type %)) (:text %)) (:content msg))
                             "")
              custom-type (:custom-type msg)]
          [(cond-> state
             (seq text) (update :messages conj {:role :assistant
                                                :text text
                                                :custom-type custom-type}))
           (poll-cmd (:queue state))])

      ;; Agent result
        (agent-result? m)
        (handle-agent-result state (:result m))

      ;; Agent error
        (agent-error? m)
        [(-> state
             (assoc :phase       :idle
                    :error       (:error m)
                    :stream-text nil))
         (poll-cmd (:queue state))]

      ;; Agent aborted via interrupt
        (= :agent-aborted (:type m))
        (let [queued-text (:queued-text m)
              merged-text (merge-queued-and-draft queued-text (input-value state))
              status-msg  (or (:message m) "Interrupted.")]
          [(-> state
               (set-input-value merged-text)
               (assoc :phase :idle
                      :stream-text nil)
               (append-assistant-status status-msg))
           (poll-cmd (:queue state))])

      ;; Agent poll timeout → keep polling (and animate spinner while streaming)
        (agent-poll? m)
        (if (= :streaming (:phase state))
          (handle-agent-poll state)
          [state (poll-cmd (:queue state))])

      ;; Session selector active — route all key input to selector handler
        (= :selecting-session (:phase state))
        (handle-selector-key state m)

      ;; Ctrl+O toggles global tool expansion state.
        (and (= :idle (:phase state))
             (msg/key-match? m "ctrl+o"))
        (let [new-expanded? (not (:tools-expanded? state))]
          (when-let [ui-atom (:ui-state-atom state)]
            (ext-ui/set-tools-expanded! ui-atom new-expanded?))
          [(assoc state :tools-expanded? new-expanded?) nil])

      ;; Alt/Meta+Backspace delete previous word.
      ;; (Explicit handling to avoid charm's binding-order issue.)
        (and (= :idle (:phase state))
             (msg/key-match? m "alt+backspace"))
        (let [before (charm/text-input-value (:input state))
              new-state (update state :input delete-prev-word)
              after  (charm/text-input-value (:input new-state))]
          (when (key-debug-enabled?)
            (println (str "[key-debug] branch=alt+backspace before=" (pr-str before)
                          " after=" (pr-str after)
                          " pos=" (:pos (:input new-state)))))
          [new-state nil])

      ;; Up/Down navigate autocomplete selection when menu is open.
        (and (= :idle (:phase state))
             (autocomplete-open? state)
             (msg/key-match? m "up"))
        [(move-autocomplete-selection state -1) nil]

        (and (= :idle (:phase state))
             (autocomplete-open? state)
             (msg/key-match? m "down"))
        [(move-autocomplete-selection state 1) nil]

      ;; Up/Down browse prompt history when autocomplete is closed.
        (and (= :idle (:phase state))
             (not (autocomplete-open? state))
             (msg/key-match? m "up"))
        [(browse-history state :up) nil]

        (and (= :idle (:phase state))
             (not (autocomplete-open? state))
             (msg/key-match? m "down"))
        [(browse-history state :down) nil]

      ;; Tab accepts selected autocomplete suggestion.
        (and (= :idle (:phase state))
             (autocomplete-open? state)
             (msg/key-match? m "tab"))
        [(apply-selected-autocomplete state) nil]

      ;; Tab opens contextual slash/path autocomplete when no menu exists.
        (and (= :idle (:phase state))
             (msg/key-match? m "tab"))
        [(open-tab-autocomplete state) nil]

      ;; Enter behavior:
      ;; - shift/alt/cmd(ctrl+alt)+enter => newline continuation
      ;; - trailing "\\" + enter => newline continuation
      ;; - plain enter accepts autocomplete (and submits slash command)
      ;; - otherwise plain enter submits
        (and (= :idle (:phase state))
             (msg/key-match? m "enter")
             (or (:shift m)
                 (:alt m)
                 (and (:ctrl m) (:alt m))
                 (str/ends-with? (charm/text-input-value (:input state)) "\\")))
        (continue-input-line state)

        (and (= :idle (:phase state))
             (autocomplete-open? state)
             (msg/key-match? m "enter"))
        (let [slash? (= :slash_command (get-in state [:prompt-input-state :autocomplete :context]))
              s1     (apply-selected-autocomplete state)]
          (if slash?
            (submit-input s1 run-agent-fn!)
            [s1 nil]))

      ;; Enter → submit (idle + has text)
        (and (= :idle (:phase state))
             (msg/key-match? m "enter"))
        (submit-input state run-agent-fn!)

      ;; Enter while streaming queues steering/follow-up input.
        (and (= :streaming (:phase state))
             (msg/key-match? m "enter"))
        (handle-streaming-submit state)

      ;; Backspace edits text while streaming.
        (and (= :streaming (:phase state))
             (msg/key-match? m "backspace"))
        (let [[new-input cmd] (charm/text-input-update (:input state) m)]
          [(set-input-model state new-input) cmd])

      ;; Space may arrive as keyword :space while streaming.
        (and (= :streaming (:phase state))
             (msg/key-match? m "space"))
        (let [[new-input cmd] (charm/text-input-update (:input state) (msg/key-press " "))]
          [(set-input-model state new-input) cmd])

      ;; Text input remains editable while streaming.
        (and (= :streaming (:phase state))
             (msg/key-press? m))
        (let [[new-input cmd] (charm/text-input-update (:input state) m)]
          [(set-input-model state new-input) cmd])

      ;; Backspace edits text then refreshes open autocomplete.
        (and (= :idle (:phase state))
             (msg/key-match? m "backspace"))
        (let [[new-input cmd] (charm/text-input-update (:input state) m)
              next-state      (set-input-model state new-input)
              next-state      (if (autocomplete-open? state)
                                (refresh-autocomplete next-state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                next-state)]
          [next-state cmd])

      ;; Space from terminal input may arrive as keyword :space (not " ").
      ;; Normalize to a printable char so it inserts immediately.
        (and (= :idle (:phase state))
             (msg/key-match? m "space"))
        (let [[new-input cmd] (charm/text-input-update (:input state) (msg/key-press " "))
              next-state      (set-input-model state new-input)
              next-state      (if (autocomplete-open? state)
                                (refresh-autocomplete next-state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                (maybe-auto-open-autocomplete next-state :space))]
          [next-state cmd])

      ;; All other keys → text input (idle only)
        (and (= :idle (:phase state))
             (msg/key-press? m))
        (let [key-token       (:key m)
              [new-input cmd] (charm/text-input-update (:input state) m)
              next-state      (set-input-model state new-input)
              next-state      (if (autocomplete-open? state)
                                (refresh-autocomplete next-state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                (maybe-auto-open-autocomplete next-state key-token))]
          [next-state cmd])

      ;; Ignore everything else (keys during streaming, etc.)
        :else
        [state nil]))))

;; ── View ────────────────────────────────────────────────────

(defn- render-banner [model-name prompt-templates skills extension-summary]
  (let [visible-skills (remove :disable-model-invocation skills)
        ext-count      (:extension-count extension-summary 0)]
    (str (charm/render title-style "ψ Psi Agent Session") "\n"
         (charm/render dim-style (str "  Model: " model-name)) "\n"
         (when (seq prompt-templates)
           (str (charm/render dim-style
                              (str "  Prompts: "
                                   (str/join ", " (map #(str "/" (:name %)) prompt-templates))))
                "\n"))
         (when (seq visible-skills)
           (str (charm/render dim-style
                              (str "  Skills: "
                                   (str/join ", " (map :name visible-skills))))
                "\n"))
         (when (pos? ext-count)
           (str (charm/render dim-style
                              (str "  Exts: " ext-count " loaded"))
                "\n"))
         (charm/render dim-style "  ESC=interrupt  Ctrl+C=clear/quit  Ctrl+D=exit-empty") "\n")))

(def ^:private subagent-title-style (charm/style :fg charm/yellow :bold true))
(def ^:private subagent-head-style (charm/style :fg charm/cyan :bold true))

(defn- render-subagent-result
  "Render a rich block for subagent-result custom messages."
  [text width]
  (let [lines     (str/split-lines (or text ""))
        heading   (or (first lines) "Subagent result")
        body      (->> (rest lines)
                       (drop-while str/blank?)
                       (str/join "\n"))
        md-width  (when (and width (> width 4)) (- width 4))
        body-text (or (md/render-markdown body md-width) body)
        body-lines (if (seq body-text) (str/split-lines body-text) [])]
    (str/join "\n"
              (concat
               [(str (charm/render subagent-title-style "ψ: ⎇ Subagent Result"))
                (str "   " (charm/render subagent-head-style heading))]
               (when (seq body-lines)
                 (cons "   " (map #(str "   " %) body-lines)))))))

(defn- render-message
  "Render a single chat message. `width` is the terminal
   column count for word wrapping (nil = no wrap)."
  [{:keys [role text custom-type]} width]
  (case role
    :user
    (str (charm/render user-style "刀: ") text)

    :assistant
    (if (= "subagent-result" custom-type)
      (render-subagent-result text width)
      (let [;; "ψ: " prefix is 3 visible cols; continuation
            ;; lines get 3-space indent. Wrap to width - 3.
            md-width (when (and width (> width 3))
                       (- width 3))
            rendered (or (md/render-markdown text md-width) text)
            lines    (str/split-lines rendered)
            first-line (str (charm/render assist-style "ψ: ")
                            (first lines))
            rest-lines (map #(str "   " %) (rest lines))]
        (str/join "\n" (cons first-line rest-lines))))

    ;; fallback
    (str "[" (name role) "] " text)))

(defn- render-messages
  "Render all chat messages. `width` is terminal columns."
  [messages width]
  (when (seq messages)
    (str (str/join "\n\n"
                   (map #(render-message % width) messages))
         "\n")))

(defn- render-separator []
  (charm/render sep-style (apply str (repeat 40 "─"))))

(def ^:private clear-to-end-seq
  "ANSI CSI J — clear from cursor to end of screen.
   Appended to each frame to prevent stale lines when the next render is
   shorter than the previous one (e.g. after /new)."
  "\u001b[J")

(def ^:private clear-line-end-seq
  "ANSI CSI K — clear from cursor to end of current line.
   Applied on dynamic footer rows so shorter re-renders don't leave
   stale trailing characters to the right."
  "\u001b[K")

(def ^:private clear-screen-home-seq
  "ANSI full clear + cursor home.
   Used as a one-shot prefix when we need a hard redraw (e.g. resize in
   terminals that don't maintain a clean alt-screen backing buffer)."
  "\u001b[2J\u001b[H")

;; ── Extension UI rendering ──────────────────────────────────

(def ^:private notify-info-style    dim-style)
(def ^:private notify-warning-style (charm/style :fg charm/yellow))
(def ^:private notify-error-style   error-style)

(defn- render-widgets [ui-state-atom placement]
  (when ui-state-atom
    (let [widgets (ext-ui/widgets-by-placement ui-state-atom placement)]
      (when (seq widgets)
        (str (str/join "\n"
                       (mapcat :content widgets))
             "\n")))))

(def ^:private footer-query
  [:psi.agent-session/cwd
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/context-fraction
   :psi.agent-session/context-window
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/thinking-level
   :psi.ui/statuses])

(defn- footer-data
  [state]
  (if-let [query-fn (:query-fn state)]
    (try
      (or (query-fn footer-query) {})
      (catch Exception _
        {}))
    {}))

(defn- format-token-count
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (double (/ n 1000.0))) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (double (/ n 1000000.0))) "M"))))

(defn- sanitize-status-text
  [text]
  (-> (or text "")
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      (str/trim)))

(defn- replace-home-with-tilde
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (string? path) (string? home) (str/starts-with? path home))
      (str "~" (subs path (count home)))
      (or path ""))))

(defn- middle-truncate
  [s width]
  (if (<= (ansi/display-width s) width)
    s
    (let [half (- (quot width 2) 2)]
      (if (> half 1)
        (let [start (subs s 0 (min (count s) half))
              end-len (max 0 (dec half))
              end (if (pos? end-len)
                    (subs s (max 0 (- (count s) end-len)))
                    "")]
          (str start "..." end))
        (subs s 0 (min (count s) (max 1 width)))))))

(defn- trim-right-visible
  [s width]
  (if (<= (ansi/visible-width s) width)
    s
    (ansi/strip-ansi (ansi/truncate-to-width s width "..."))))

(defn- context-piece
  [fraction context-window auto-compact?]
  (let [suffix (if auto-compact? " (auto)" "")
        window (format-token-count (or context-window 0))
        text (if (number? fraction)
               (str (format "%.1f" (* 100.0 fraction)) "%/" window suffix)
               (str "?/" window suffix))]
    (cond
      (and (number? fraction) (> fraction 0.9)) (charm/render error-style text)
      (and (number? fraction) (> fraction 0.7)) (charm/render notify-warning-style text)
      :else (charm/render dim-style text))))

(defn- build-footer-lines
  [state width]
  (let [d                (footer-data state)
        cwd              (or (:psi.agent-session/cwd d) (:cwd state) "")
        git-branch       (:psi.agent-session/git-branch d)
        session-name     (:psi.agent-session/session-name d)
        usage-input      (or (:psi.agent-session/usage-input d) 0)
        usage-output     (or (:psi.agent-session/usage-output d) 0)
        usage-cache-read (or (:psi.agent-session/usage-cache-read d) 0)
        usage-cache-write (or (:psi.agent-session/usage-cache-write d) 0)
        usage-cost-total (or (:psi.agent-session/usage-cost-total d) 0.0)
        context-fraction (:psi.agent-session/context-fraction d)
        context-window   (:psi.agent-session/context-window d)
        auto-compact?    (boolean (:psi.agent-session/auto-compaction-enabled d))
        model-provider   (:psi.agent-session/model-provider d)
        model-id         (:psi.agent-session/model-id d)
        model-reasoning? (boolean (:psi.agent-session/model-reasoning d))
        thinking-level   (:psi.agent-session/thinking-level d)
        statuses         (or (:psi.ui/statuses d) [])

        path0 (replace-home-with-tilde cwd)
        path1 (if (seq git-branch) (str path0 " (" git-branch ")") path0)
        path2 (if (seq session-name) (str path1 " • " session-name) path1)
        path-line (charm/render dim-style (middle-truncate path2 (max 1 width)))

        left-parts (cond-> []
                     (pos? usage-input) (conj (charm/render dim-style (str "↑" (format-token-count usage-input))))
                     (pos? usage-output) (conj (charm/render dim-style (str "↓" (format-token-count usage-output))))
                     (pos? usage-cache-read) (conj (charm/render dim-style (str "R" (format-token-count usage-cache-read))))
                     (pos? usage-cache-write) (conj (charm/render dim-style (str "W" (format-token-count usage-cache-write))))
                     (pos? usage-cost-total) (conj (charm/render dim-style (format "$%.3f" (double usage-cost-total))))
                     :always (conj (context-piece context-fraction context-window auto-compact?)))
        left0 (str/join " " left-parts)
        left  (if (> (ansi/visible-width left0) width)
                (trim-right-visible left0 width)
                left0)

        model-label (or model-id "no-model")
        right-base (if model-reasoning?
                     (if (= :off thinking-level)
                       (str model-label " • thinking off")
                       (str model-label " • " (name (or thinking-level :off))))
                     model-label)
        provider-label (or model-provider "no-provider")
        right0 (str "(" provider-label ") " right-base)
        right (charm/render dim-style right0)

        left-w  (ansi/visible-width left)
        right-w (ansi/visible-width right)
        min-pad 2
        total-needed (+ left-w min-pad right-w)
        stats-line
        (cond
          (<= total-needed width)
          (str left (apply str (repeat (- width left-w right-w) " ")) right)

          (> (- width left-w min-pad) 3)
          (let [avail (- width left-w min-pad)
                right-trunc (charm/render dim-style (trim-right-visible right0 avail))]
            (str left (apply str (repeat (max min-pad (- width left-w (ansi/visible-width right-trunc))) " ")) right-trunc))

          :else left)

        status-line
        (when (seq statuses)
          (let [joined (->> statuses
                            (sort-by :extension-id)
                            (map (comp sanitize-status-text :text))
                            (remove str/blank?)
                            (str/join " "))]
            (when (seq joined)
              (ansi/truncate-to-width joined width (charm/render dim-style "...")))))

        lines (cond-> [path-line stats-line]
                status-line (conj status-line))]
    lines))

(defn- render-footer
  [state width]
  (let [lines (build-footer-lines state width)
        cleared-lines (map #(str % clear-line-end-seq) lines)]
    (str (str/join "\n" cleared-lines) "\n")))

(defn- render-notifications [ui-state-atom]
  (when ui-state-atom
    (let [notes (ext-ui/visible-notifications ui-state-atom)]
      (when (seq notes)
        (str (str/join "\n"
                       (map (fn [n]
                              (let [style (case (:level n)
                                            :warning notify-warning-style
                                            :error   notify-error-style
                                            notify-info-style)]
                                (charm/render style (str "  " (:message n)))))
                            notes))
             "\n")))))

(defn- render-dialog [ui-state-atom]
  (when ui-state-atom
    (when-let [dialog (ext-ui/active-dialog ui-state-atom)]
      (case (:kind dialog)
        :confirm
        (str (charm/render title-style (:title dialog)) "\n"
             "  " (:message dialog) "\n"
             (charm/render dim-style "  Enter=confirm  Escape=cancel") "\n")

        :select
        (let [idx     (or (:selected-index dialog) 0)
              options (:options dialog)]
          (str (charm/render title-style (:title dialog)) "\n"
               (str/join "\n"
                         (map-indexed
                          (fn [i opt]
                            (if (= i idx)
                              (str (charm/render user-style (str "▸ " (:label opt)))
                                   (when (:description opt)
                                     (str "  " (charm/render dim-style (:description opt)))))
                              (str "  " (:label opt))))
                          options))
               "\n"
               (charm/render dim-style "  ↑/↓=navigate  Enter=select  Escape=cancel") "\n"))

        :input
        (str (charm/render title-style (:title dialog)) "\n"
             "  " (or (:input-text dialog) "") "█" "\n"
             (charm/render dim-style "  Enter=submit  Escape=cancel") "\n")

        ;; fallback
        ""))))

;; ── Text input word wrap ─────────────────────────────────────

(defn- wrap-chunks
  "Split plain text into display chunks with position tracking.
   Preserves whitespace exactly (including trailing spaces), and treats
   newline as a hard break.

   Returns [{:text \"line\" :start N :end N} ...] where start/end are
   character indices in the original text."
  [^String text max-width]
  (if (or (nil? text) (empty? text))
    [{:text "" :start 0 :end 0}]
    (let [len   (count text)
          width (max 1 (or max-width 1))]
      (loop [start 0, chunks []]
        (if (>= start len)
          (if (empty? chunks)
            [{:text "" :start 0 :end 0}]
            chunks)
          (let [[end-idx hard-break?]
                (loop [j start, col 0]
                  (if (>= j len)
                    [j false]
                    (let [c (.charAt text j)]
                      (cond
                        (= c \newline)
                        [j true]

                        :else
                        (let [cw (ansi/char-width c)]
                          (if (> (+ col cw) width)
                            (if (= j start)
                              [(inc j) false]
                              [j false])
                            (recur (inc j) (+ col cw))))))))
                chunk-text (subs text start end-idx)
                next-start (if hard-break? (inc end-idx) end-idx)]
            (let [chunks' (conj chunks {:text chunk-text
                                        :start start
                                        :end end-idx})
                  chunks' (if (and hard-break? (>= next-start len))
                            (conj chunks' {:text "" :start len :end len})
                            chunks')]
              (recur next-start chunks'))))))))

(defn- wrap-text-input-view
  "Render text input with word wrapping at terminal width.
   Continuation lines indent to align with the prompt end."
  [input width]
  (let [{:keys [prompt value pos focused cursor-style
                prompt-style placeholder-style placeholder]} input
        prompt-str (if prompt-style
                     (charm/render prompt-style prompt)
                     (or prompt ""))
        prompt-w   (ansi/visible-width (or prompt ""))
        avail      (max 1 (- width prompt-w))
        indent     (apply str (repeat prompt-w \space))
        cursor-sty (or cursor-style (charm/style :reverse true))]
    (if (and (empty? value) placeholder (not (str/blank? placeholder)))
      ;; Placeholder
      (str prompt-str
           (if focused
             (str (charm/render cursor-sty (subs placeholder 0 1))
                  (if placeholder-style
                    (charm/render placeholder-style (subs placeholder 1))
                    (subs placeholder 1)))
             (if placeholder-style
               (charm/render placeholder-style placeholder)
               placeholder)))
      ;; Normal value — word-wrap and place cursor
      (let [text   (apply str value)
            chunks (wrap-chunks text avail)]
        (str/join
         "\n"
         (map-indexed
          (fn [i {:keys [text start end]}]
            (let [prefix  (if (zero? i) prompt-str indent)
                  is-last (= i (dec (count chunks)))
                  ;; Cursor in this chunk? Last chunk owns pos >= start;
                  ;; others own start <= pos < end
                  cursor? (and focused
                               (>= pos start)
                               (or is-last (< pos end)))]
              (if cursor?
                (let [lp     (- pos start)
                      before (subs text 0 (min lp (count text)))
                      c-char (if (< lp (count text))
                               (subs text lp (inc lp))
                               " ")
                      after  (if (< lp (count text))
                               (subs text (inc lp))
                               "")]
                  (str prefix before
                       (charm/render cursor-sty c-char)
                       after))
                (str prefix text))))
          chunks))))))

;; ── Session selector rendering ──────────────────────────────

(def ^:private selector-title-style  (charm/style :fg charm/magenta :bold true))
(def ^:private selector-sel-style    (charm/style :fg charm/cyan :bold true))
(def ^:private selector-cur-style    (charm/style :fg charm/yellow))
(def ^:private selector-hint-style   dim-style)
(def ^:private selector-search-style (charm/style :fg charm/green))

(defn- shorten-path [p]
  (let [home (System/getProperty "user.home")]
    (if (and p (.startsWith ^String p home))
      (str "~" (subs p (count home)))
      (or p ""))))

(defn- render-session-selector
  "Render the /resume session picker."
  [sel-state current-session-file width]
  (let [{:keys [scope search selected]} sel-state
        filtered  (selector-filtered sel-state)
        n         (count filtered)
        scope-str (if (= :current scope)
                    (str (charm/render selector-sel-style "◉ Current") "  ○ All")
                    (str "○ Current  " (charm/render selector-sel-style "◉ All")))
        title     (str (charm/render selector-title-style "Resume Session")
                       "  " scope-str
                       "  " (charm/render selector-hint-style "[Tab=scope ↑↓=nav Enter=select Esc=cancel]"))]
    (str title "\n"
         (charm/render selector-search-style (str "Search: " search "█")) "\n"
         (render-separator) "\n"
         (if (zero? n)
           (charm/render dim-style "  (no sessions found)\n")
           (str/join "\n"
                     (map-indexed
                      (fn [i info]
                        (let [is-sel     (= i selected)
                              is-current (= (:path info) current-session-file)
                              age        (format-age (:modified info))
                              label      (or (:name info) (:first-message info) "(empty)")
                              label      (str/replace label #"\n" " ")
                              cwd-part   (when (= :all scope)
                                           (str " " (charm/render dim-style
                                                                  (shorten-path (:cwd info)))))
                              right      (str (charm/render dim-style
                                                            (str (:message-count info) " " age))
                                              (or cwd-part ""))
                              right-w    (count right) ; approximate
                              avail      (max 10 (- width 4 right-w))
                              label-tr   (if (> (count label) avail)
                                           (str (subs label 0 (- avail 1)) "…")
                                           label)
                              cursor     (if is-sel
                                           (charm/render selector-sel-style "▸ ")
                                           "  ")
                              styled-lbl (cond
                                           is-current (charm/render selector-cur-style label-tr)
                                           is-sel     (charm/render selector-sel-style label-tr)
                                           :else      label-tr)
                              pad        (str/join (repeat (max 1 (- width 2 (count label-tr) right-w)) " "))]
                          (str cursor styled-lbl pad right)))
                      filtered)))
         "\n"
         (when (pos? n)
           (charm/render dim-style (str "  " (inc selected) "/" n "\n"))))))

;; ── Tool progress rendering ──────────────────────────────────

(def ^:private default-preview-lines 5)
(def ^:private read-preview-lines 10)
(def ^:private write-preview-lines 10)
(def ^:private ls-preview-lines 20)
(def ^:private find-preview-lines 20)
(def ^:private grep-preview-lines 15)
(def ^:private bash-preview-lines 5)

(defn- parse-tool-args
  [parsed-args args-str]
  (or parsed-args
      (try (json/parse-string args-str)
           (catch Exception _ nil))))

(defn- tool-header
  "Format tool name and key argument for display."
  [tool-name parsed-args args-str]
  (let [args (parse-tool-args parsed-args args-str)]
    (case tool-name
      "read"  (str (charm/render tool-style "read")  " " (get args "path" "…"))
      "bash"  (str (charm/render tool-style "$")      " " (get args "command" "…"))
      "edit"  (str (charm/render tool-style "edit")  " " (get args "path" "…"))
      "write" (str (charm/render tool-style "write") " " (get args "path" "…"))
      (str (charm/render tool-style tool-name)))))

(defn- tool-status-indicator
  "Status icon for a tool execution."
  [status spinner-char]
  (case status
    :pending (str spinner-char)
    :running (str spinner-char)
    :success (charm/render tool-ok-style "✓")
    :error   (charm/render tool-err-style "✗")
    ""))

(defn- wrap-tool-result-line
  "Wrap a single tool result line to fit within `avail`
   visible columns. Returns a seq of wrapped strings."
  [line avail]
  (if (or (nil? avail) (<= (ansi/visible-width line) avail))
    [line]
    (ansi/word-wrap-ansi line avail)))

(defn- tool-expanded?
  [tools-expanded? _tc]
  (boolean tools-expanded?))

(defn- content-block->text
  [block]
  (let [t (:type block)
        t-label (cond
                  (keyword? t) (name t)
                  (string? t)  t
                  :else        "unknown")]
    (case t
      :text  (:text block)
      :image (str "[image " (or (:mime-type block) "unknown") "]")
      (str "[unsupported content block: " t-label "]"))))

(defn- tool-content->text
  [content]
  (when (seq content)
    (str/join "\n" (map content-block->text content))))

(defn- preview-lines-for-tool
  [tool-name]
  (case tool-name
    "read" read-preview-lines
    "write" write-preview-lines
    "ls" ls-preview-lines
    "find" find-preview-lines
    "grep" grep-preview-lines
    "bash" bash-preview-lines
    default-preview-lines))

(defn- tool-preview
  [tool-name text expanded?]
  (when (and text (not (str/blank? text)))
    (let [lines (str/split-lines text)
          total (count lines)]
      (if expanded?
        {:lines lines :hidden? false :bash-tail? false :hidden-count 0}
        (let [limit (preview-lines-for-tool tool-name)]
          (if (<= total limit)
            {:lines lines :hidden? false :bash-tail? false :hidden-count 0}
            (if (= "bash" tool-name)
              {:lines (vec (take-last limit lines))
               :hidden? true
               :bash-tail? true
               :hidden-count (- total limit)}
              {:lines (vec (take limit lines))
               :hidden? true
               :bash-tail? false
               :hidden-count (- total limit)})))))))

(defn- detail-warning-lines
  [details]
  (let [truncation (or (:truncation details)
                       (get details "truncation"))
        full-output-path (or (:full-output-path details)
                             (:fullOutputPath details)
                             (get details "full-output-path")
                             (get details "fullOutputPath"))
        entry-limit (or (:entry-limit-reached details)
                        (:entryLimitReached details)
                        (get details "entry-limit-reached")
                        (get details "entryLimitReached"))
        result-limit (or (:result-limit-reached details)
                         (:resultLimitReached details)
                         (get details "result-limit-reached")
                         (get details "resultLimitReached"))
        match-limit (or (:match-limit-reached details)
                        (:matchLimitReached details)
                        (get details "match-limit-reached")
                        (get details "matchLimitReached"))
        lines-truncated? (boolean (or (:lines-truncated details)
                                      (:linesTruncated details)
                                      (get details "lines-truncated")
                                      (get details "linesTruncated")))]
    (cond-> []
      (and (map? truncation) (:truncated truncation))
      (conj (str "Truncated output"
                 (when-let [by (:truncated-by truncation)]
                   (str " (" by ")"))))

      full-output-path
      (conj (str "Full output: " full-output-path))

      entry-limit
      (conj (str "Entry limit reached: " entry-limit))

      result-limit
      (conj (str "Result limit reached: " result-limit))

      match-limit
      (conj (str "Match limit reached: " match-limit))

      lines-truncated?
      (conj "Long lines truncated"))))

(defn- extension-call-render
  [ui-state-atom tc]
  (when-let [render-fn (some-> (ext-ui/get-tool-renderer ui-state-atom (:name tc))
                               :render-call-fn)]
    (try
      (some-> (render-fn (parse-tool-args (:parsed-args tc) (:args tc))) str)
      (catch Exception e
        (timbre/warn "Tool call renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- extension-result-render
  [ui-state-atom tc opts]
  (when-let [render-fn (some-> (ext-ui/get-tool-renderer ui-state-atom (:name tc))
                               :render-result-fn)]
    (try
      (some-> (render-fn tc opts) str)
      (catch Exception e
        (timbre/warn "Tool result renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- render-tool-calls
  "Render all tool calls for the current turn.
   `width` is the terminal column count."
  [tool-calls tool-order spinner-char width tools-expanded? ui-state-atom]
  (when (seq tool-order)
    (let [;; "  ✓ " prefix = 4 visible cols for header
          header-avail (when (and width (> width 4))
                         (- width 4))
          ;; "    " prefix = 4 visible cols for result
          result-avail (when (and width (> width 4))
                         (- width 4))]
      (str/join
       "\n"
       (for [id tool-order
             :let [tc (get tool-calls id)]
             :when tc]
         (let [status-icon   (tool-status-indicator
                              (:status tc) spinner-char)
               call-render   (extension-call-render ui-state-atom tc)
               header        (if (seq call-render)
                               call-render
                               (tool-header (:name tc)
                                            (:parsed-args tc)
                                            (:args tc)))
               header        (if header-avail
                               (ansi/truncate-to-width
                                header header-avail)
                               header)
               expanded?     (tool-expanded? tools-expanded? tc)
               raw-result    (or (:result tc)
                                 (tool-content->text (:content tc)))
               {:keys [lines hidden? bash-tail? hidden-count]}
               (or (tool-preview (:name tc) raw-result expanded?)
                   {:lines [] :hidden? false :bash-tail? false :hidden-count 0})
               hint-line      (when hidden?
                                (if bash-tail?
                                  (str "… (" hidden-count " earlier lines hidden, ctrl+o to expand)")
                                  (str "… (" hidden-count " more lines, ctrl+o to expand)")))
               warning-lines  (into [] (concat (detail-warning-lines (:details tc))
                                               (when hint-line [hint-line])))
               result-render  (extension-result-render ui-state-atom tc
                                                       {:expanded? expanded?
                                                        :width width
                                                        :tool-id id
                                                        :tool-name (:name tc)})
               result-lines   (if (seq result-render)
                                (str/split-lines result-render)
                                lines)
               warning-lines  (if (seq result-render)
                                []
                                warning-lines)
               result-style   (if (:is-error tc)
                                tool-err-style
                                tool-dim-style)]
           (str "  " status-icon " " header
                (when (or (seq result-lines) (seq warning-lines))
                  (str "\n"
                       (str/join
                        "\n"
                        (concat
                         (mapcat
                          (fn [line]
                            (let [wrapped (wrap-tool-result-line line result-avail)]
                              (map #(str "    "
                                         (charm/render result-style %))
                                   wrapped)))
                          result-lines)
                         (mapcat
                          (fn [line]
                            (let [wrapped (wrap-tool-result-line line result-avail)]
                              (map #(str "    "
                                         (charm/render dim-style %))
                                   wrapped)))
                          warning-lines))))))))))))

(defn- render-stream-text
  "Render accumulated streaming text from the LLM
   with markdown styling. `width` is terminal columns."
  [text width]
  (when (and text (not (str/blank? text)))
    (let [md-width (when (and width (> width 3))
                     (- width 3))
          rendered (or (md/render-markdown text md-width) text)
          lines    (str/split-lines rendered)
          first-line (str (charm/render assist-style "ψ: ")
                          (first lines))
          rest-lines (map #(str "   " %) (rest lines))]
      (str (str/join "\n" (cons first-line rest-lines))
           "\n"))))

(defn view
  "Render the full TUI state to a string."
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name
                prompt-templates skills extension-summary ui-state-atom
                stream-text tool-calls tool-order tools-expanded?
                session-selector current-session-file width force-clear?]} state
        spinner-char   (nth spinner-frames (mod spinner-frame (count spinner-frames)))
        dialog-active? (has-active-dialog? state)
        has-progress?  (or (seq stream-text) (seq tool-order))
        active-tool-spinner?
        (boolean
         (some (fn [id]
                 (let [status (get-in tool-calls [id :status])]
                   (or (= :pending status)
                       (= :running status))))
               tool-order))
        progress-spinner-visible?
        (and (= :streaming phase)
             (or (not has-progress?) active-tool-spinner?))
        term-width     (or width 80)]
    (str
     (when force-clear?
       clear-screen-home-seq)
     (if (= :selecting-session phase)
       ;; Session selector takes over the whole screen
       (str (render-banner model-name prompt-templates skills extension-summary)
            "\n"
            (render-session-selector session-selector current-session-file term-width)
            clear-to-end-seq)
       ;; Normal chat view
       (str (render-banner model-name prompt-templates skills extension-summary)
            "\n"
            (render-messages messages term-width)
            ;; Current turn progress
            (when (= :streaming phase)
              (if has-progress?
                (str (render-stream-text stream-text term-width)
                     (render-tool-calls tool-calls tool-order spinner-char term-width tools-expanded? ui-state-atom)
                     "\n")
                (str "\n" (charm/render assist-style "ψ: ")
                     spinner-char " thinking…\n")))
            (when error
              (str "\n" (charm/render error-style (str "[error: " error "]")) "\n"))
            ;; Widgets above editor
            (render-widgets ui-state-atom :above-editor)
            "\n"
            (render-separator) "\n"
            ;; Dialog replaces editor when active
            (if dialog-active?
              (render-dialog ui-state-atom)
              (str (wrap-text-input-view input term-width)
                   (when (= :streaming phase)
                     (str "\n"
                          (charm/render dim-style
                                        (if progress-spinner-visible?
                                          "(Enter queues input • Esc interrupts)"
                                          (str spinner-char " waiting for response…")))
                          clear-line-end-seq))))
            "\n"
            (render-separator) "\n"
            ;; Widgets below editor
            (render-widgets ui-state-atom :below-editor)
            ;; Default footer (path, stats, statuses)
            (render-footer state term-width)
            ;; Notifications toast
            (render-notifications ui-state-atom)
            clear-to-end-seq)))))

;; ── Public entry point ──────────────────────────────────────

(defn start!
  "Run the Psi TUI. Blocks until the user exits.

   `model-name`     — display name for the banner
   `run-agent-fn!`  — (fn [text queue]) starts agent in background;
                       must put {:kind :done :result msg} or
                       {:kind :error :message str} on queue.
   `opts`           — optional map:
                       :query-fn            — (fn [eql-query]) for session introspection
                       :ui-state-atom       — extension UI state atom
                       :cwd                 — working directory for /resume filtering
                       :current-session-file — current session file path for highlight
                       :resume-fn!          — (fn [session-path]) =>
                                              {:messages [...]
                                               :tool-calls {...}
                                               :tool-order [...]}
                       :on-interrupt-fn!    — (fn [state]) -> {:queued-text str? :message str?}
                       :on-queue-input-fn!  — (fn [text state]) -> {:message str?}
                       :double-press-window-ms — ctrl+c / escape timing window (default 500)
                       :double-escape-action — :tree | :fork | :none (default :none)
                       :alt-screen          — true/false (default true)"
  ([model-name run-agent-fn!]
   (start! model-name run-agent-fn! {}))
  ([model-name run-agent-fn! opts]
   (charm/run {:init       (make-init model-name (:query-fn opts) (:ui-state-atom opts) opts)
               :update     (make-update run-agent-fn!)
               :view       view
               :alt-screen (if (contains? opts :alt-screen)
                             (boolean (:alt-screen opts))
                             true)})))
