(in-ns 'psi.tui.app)
;; extracted: render

(declare title-style
         dim-style
         user-style
         assist-style
         sep-style
         error-style
         spinner-frames
         has-active-dialog?)

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

(def ^:private agent-title-style (charm/style :fg charm/yellow :bold true))
(def ^:private agent-head-style (charm/style :fg charm/cyan :bold true))
(def ^:private psl-title-style (charm/style :fg charm/green :bold true))

(defn- render-agent-result
  "Render a rich block for agent-result custom messages."
  [text width]
  (let [lines     (str/split-lines (or text ""))
        heading   (or (first lines) "Agent result")
        body      (->> (rest lines)
                       (drop-while str/blank?)
                       (str/join "\n"))
        md-width  (when (and width (> width 4)) (- width 4))
        body-text (or (md/render-markdown body md-width) body)
        body-lines (if (seq body-text) (str/split-lines body-text) [])]
    (str/join "\n"
              (concat
               [(str (charm/render agent-title-style "ψ: ⎇ Agent Result"))
                (str "   " (charm/render agent-head-style heading))]
               (when (seq body-lines)
                 (cons "   " (map #(str "   " %) body-lines)))))))

(defn- render-message
  [{:keys [role text custom-type]} width]
  (case role
    :user
    (str (charm/render user-style "刀: ") text)

    :assistant
    (cond
      (= "agent-result" custom-type)
      (render-agent-result text width)

      (= "plan-state-learning" custom-type)
      (str (charm/render psl-title-style "ψ: ⟳ Plan/State/Learning")
           "\n"
           "   " (or text ""))

      :else
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

(defn- render-separator
  "Render a horizontal separator sized to terminal WIDTH."
  [width]
  (charm/render sep-style (apply str (repeat (max 1 (or width 1)) "─"))))

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

(defn- render-widgets [ui-snapshot placement]
  (when ui-snapshot
    (let [widgets (->> (:widgets ui-snapshot)
                       (filter #(= placement (:placement %)))
                       vec)]
      (when (seq widgets)
        (str (str/join "\n" (mapcat :content widgets)) "\n")))))

(def ^:private footer-query footer/footer-query)

(defn- footer-data
  [state]
  (if-let [query-fn (:query-fn state)]
    (try
      (or (query-fn footer-query) {})
      (catch Exception _
        {}))
    {}))

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

(defn- context-style
  [fraction]
  (cond
    (and (number? fraction) (> fraction 0.9)) error-style
    (and (number? fraction) (> fraction 0.7)) notify-warning-style
    :else dim-style))

(defn- build-footer-lines
  [state width]
  (let [d               (footer-data state)
        model           (footer/footer-model-from-data d {:cwd (:cwd state)})
        path-text       (get-in model [:footer/lines :path-line] "")
        usage-parts     (get-in model [:footer/usage :parts] [])
        context-text    (get-in model [:footer/context :text] "")
        model-text      (get-in model [:footer/model :text] "")
        context-style*  (context-style (get-in model [:footer/context :fraction]))
        left-parts      (mapv (fn [part]
                                (charm/render (if (= part context-text)
                                                context-style*
                                                dim-style)
                                              part))
                              usage-parts)
        left            (str/join " " left-parts)
        right0          model-text
        path-line       (charm/render dim-style (middle-truncate path-text (max 1 width)))
        stats-line      (let [left*   (if (> (ansi/visible-width left) width)
                                        (trim-right-visible left width)
                                        left)
                              right   (charm/render dim-style right0)
                              left-w  (ansi/visible-width left*)
                              right-w (ansi/visible-width right)
                              min-pad 2]
                          (cond
                            (<= (+ left-w min-pad right-w) width)
                            (str left* (apply str (repeat (- width left-w right-w) " ")) right)

                            (> (- width left-w min-pad) 3)
                            (let [avail       (- width left-w min-pad)
                                  right-trunc (charm/render dim-style (trim-right-visible right0 avail))]
                              (str left*
                                   (apply str (repeat (max min-pad (- width left-w (ansi/visible-width right-trunc))) " "))
                                   right-trunc))

                            :else left*))
        status-line     (some-> (get-in model [:footer/lines :status-line])
                                (ansi/truncate-to-width width (charm/render dim-style "...")))]
    (cond-> [path-line stats-line]
      status-line (conj status-line))))

(defn- render-footer
  [state width]
  (let [lines (build-footer-lines state width)
        cleared-lines (map #(str % clear-line-end-seq) lines)]
    (str (str/join "\n" cleared-lines) "\n")))

(defn- render-notifications [ui-snapshot]
  (when ui-snapshot
    (let [notes (vec (:visible-notifications ui-snapshot))]
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

(defn- render-dialog [ui-snapshot selected-index input-text]
  (when ui-snapshot
    (when-let [dialog (:active-dialog ui-snapshot)]
      (case (:kind dialog)
        :confirm
        (str (charm/render title-style (:title dialog)) "\n"
             "  " (:message dialog) "\n"
             (charm/render dim-style "  Enter=confirm  Escape=cancel") "\n")

        :select
        (let [idx     (or selected-index 0)
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
             "  " (or input-text "") "█" "\n"
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
                next-start (if hard-break? (inc end-idx) end-idx)
                chunks'    (conj chunks {:text chunk-text
                                         :start start
                                         :end end-idx})
                chunks'    (if (and hard-break? (>= next-start len))
                             (conj chunks' {:text "" :start len :end len})
                             chunks')]
            (recur next-start chunks')))))))

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

;; ── Tool progress rendering ──────────────────────────────────
(defn- render-stream-thinking
  "Render accumulated streaming thinking from the LLM.

   Thinking is shown as a dim inline transcript above the main assistant text."
  [text]
  (when (and text (not (str/blank? text)))
    (str (charm/render dim-style (str "ψ⋯ " text)) "\n")))

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

(defn- render-tool-snapshot
  [snapshot spinner-char width tools-expanded? ui-snapshot tool-id]
  (when snapshot
    (tool-render/render-tool-calls {tool-id (assoc snapshot :expanded? tools-expanded?)}
                                   [tool-id]
                                   spinner-char
                                   width
                                   tools-expanded?
                                   ui-snapshot)))

(defn- render-active-turn-event
  [state {:keys [item-kind content-index text tool-id snapshot]} spinner-char width]
  (case item-kind
    :thinking
    (render-stream-thinking text)

    :text
    (render-stream-text text width)

    (:tool :tool-lifecycle)
    (if snapshot
      (render-tool-snapshot snapshot spinner-char width (:tools-expanded? state) (:ui-snapshot state)
                            (or tool-id
                                (get-in state [:tool-ui-id-by-content-index content-index])
                                (str "tool/event-" content-index)))
      (let [ui-id (or (get-in state [:tool-ui-id-by-tool-id tool-id])
                      (get-in state [:tool-ui-id-by-content-index content-index])
                      tool-id)]
        (when ui-id
          (tool-render/render-tool-calls (:tool-calls state) [ui-id] spinner-char width (:tools-expanded? state) (:ui-snapshot state)))))

    nil))

(defn- render-active-turn
  [state spinner-char width]
  (let [events   (:active-turn-events state)
        rendered (->> events
                      (keep #(render-active-turn-event state % spinner-char width))
                      (apply str))]
    (when-not (str/blank? rendered)
      rendered)))

(defn view
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name
                prompt-templates skills extension-summary ui-snapshot
                tool-calls tool-order
                active-turn-order active-turn-events session-selector current-session-file width force-clear?]} state
        spinner-char   (nth spinner-frames (mod spinner-frame (count spinner-frames)))
        dialog-active? (has-active-dialog? state)
        has-progress?  (or (seq active-turn-events)
                           (seq active-turn-order)
                           (seq tool-order))
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
            (selector-render/render-session-selector dim-style render-separator session-selector current-session-file term-width (:session-selector-mode state))
            clear-to-end-seq)
       ;; Normal chat view
       (str (render-banner model-name prompt-templates skills extension-summary)
            "\n"
            (render-messages messages term-width)
            ;; Current turn progress
            (when (= :streaming phase)
              (if has-progress?
                (str (or (render-active-turn state spinner-char term-width)
                         (tool-render/render-tool-calls tool-calls tool-order spinner-char term-width (boolean (:tools-expanded? state)) ui-snapshot)
                         "")
                     "\n")
                (str "\n" (charm/render assist-style "ψ: ")
                     spinner-char " thinking…\n")))
            (when error
              (str "\n" (charm/render error-style (str "[error: " error "]")) "\n"))
            ;; Widgets above editor
            (render-widgets ui-snapshot :above-editor)
            "\n"
            (render-separator term-width) "\n"
            ;; Dialog replaces editor when active
            (if dialog-active?
              (render-dialog ui-snapshot (:dialog-selected-index state) (:dialog-input-text state))
              (str (wrap-text-input-view input term-width)
                   (when (= :streaming phase)
                     (str "\n"
                          (charm/render dim-style
                                        (if progress-spinner-visible?
                                          "(Enter queues input • Esc interrupts)"
                                          (str spinner-char " waiting for response…")))
                          clear-line-end-seq))))
            "\n"
            (render-separator term-width) "\n"
            ;; Widgets below editor
            (render-widgets ui-snapshot :below-editor)
            ;; Default footer (path, stats, statuses)
            (render-footer state term-width)
            ;; Notifications toast
            (render-notifications ui-snapshot)
            clear-to-end-seq)))))

