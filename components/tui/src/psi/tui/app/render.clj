(ns psi.tui.app.render
  (:require
   [charm.style.core :as charm-style]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]
   [psi.tui.markdown :as md]
   [psi.tui.session-selector-render :as selector-render]
   [psi.tui.tool-render :as tool-render]))

(defn- prefixed-wrap-lines
  [prefix text width]
  (let [prefix      (or prefix "")
        text        (or text "")
        prefix-w    (ansi/visible-width prefix)
        avail       (max 1 (- (or width 80) prefix-w))
        wrapped     (ansi/word-wrap text avail)
        indent      (apply str (repeat prefix-w \space))]
    (map-indexed (fn [idx line]
                   (str (if (zero? idx) prefix indent) line))
                 wrapped)))

(defn- render-banner-summary
  [prefix text width]
  (when (seq text)
    (str/join "\n"
              (map #(charm-style/render shared/dim-style %)
                   (prefixed-wrap-lines prefix text width)))))

(defn- banner-rows
  [model-name prompt-templates skills extension-summary]
  (let [visible-skills (remove :disable-model-invocation skills)
        ext-count      (:extension-count extension-summary 0)]
    [{:prefix "  Model: "
      :text model-name}
     {:prefix "  Prompts: "
      :text (when (seq prompt-templates)
              (str/join ", " (map #(str "/" (:name %)) prompt-templates)))}
     {:prefix "  Skills: "
      :text (when (seq visible-skills)
              (str/join ", " (map :name visible-skills)))}
     {:prefix "  Exts: "
      :text (when (pos? ext-count)
              (str ext-count " loaded"))}
     {:prefix "  "
      :text "ESC=interrupt  Ctrl+C=clear/quit  Ctrl+D=exit-empty"}]))

(defn render-banner [model-name prompt-templates skills extension-summary width]
  (str (charm-style/render shared/title-style "ψ Psi Agent Session") "\n"
       (->> (banner-rows model-name prompt-templates skills extension-summary)
            (keep (fn [{:keys [prefix text]}]
                    (render-banner-summary prefix text width)))
            (str/join "\n"))
       "\n"))

(def agent-title-style (charm-style/style :fg charm-style/yellow :bold true))
(def agent-head-style (charm-style/style :fg charm-style/cyan :bold true))
(def psl-title-style (charm-style/style :fg charm-style/green :bold true))
(def thinking-style (charm-style/style :fg 240 :italic true))

(defn render-thinking-line
  [text width]
  (when (and text (not (str/blank? text)))
    (str/join "\n"
              (map #(charm-style/render thinking-style %)
                   (prefixed-wrap-lines "· " text width)))))

(defn render-agent-result
  [text width]
  (let [lines      (str/split-lines (or text ""))
        heading    (or (first lines) "Agent result")
        body       (->> (rest lines)
                        (drop-while str/blank?)
                        (str/join "\n"))
        md-width   (when (and width (> width 4)) (- width 4))
        body-text  (or (md/render-markdown body md-width) body)
        body-lines (if (seq body-text) (str/split-lines body-text) [])]
    (str/join "\n"
              (concat
               [(str (charm-style/render agent-title-style "ψ: ⎇ Agent Result"))
                (str "   " (charm-style/render agent-head-style heading))]
               (when (seq body-lines)
                 (cons "   " (map #(str "   " %) body-lines)))))))

(defn render-message
  [{:keys [role text custom-type]} width]
  (case role
    :thinking
    (str/join "\n"
              (map #(charm-style/render thinking-style %)
                   (prefixed-wrap-lines "· " text width)))

    :user
    (str/join "\n"
              (let [lines (prefixed-wrap-lines "刀: " text width)]
                (cons (charm-style/render shared/user-style (first lines))
                      (rest lines))))

    :assistant
    (cond
      (= "agent-result" custom-type)
      (render-agent-result text width)

      (= "plan-state-learning" custom-type)
      (str (charm-style/render psl-title-style "ψ: ⟳ Plan/State/Learning")
           "\n"
           "   " (or text ""))

      :else
      (let [md-width   (when (and width (> width 3))
                         (- width 3))
            rendered   (or (md/render-markdown text md-width) text)
            lines      (str/split-lines rendered)
            first-line (str (charm-style/render shared/assist-style "ψ: ")
                            (first lines))
            rest-lines (map #(str "   " %) (rest lines))]
        (str/join "\n" (cons first-line rest-lines))))

    ;; :tool messages are handled by render-tool-message before reaching here;
    ;; this branch is a safety fallback only.
    :tool
    (str "  [tool " (:tool-id text) "]")

    (str "[" (name role) "] " text)))

(defn render-tool-message
  "Render a {:role :tool :tool-id id} message entry from the tool-calls map.
   Returns nil when the tool-id is not found."
  [{:keys [tool-id]} tool-calls spinner-char width tools-expanded? ui-snapshot]
  (when (and tool-id (get tool-calls tool-id))
    (tool-render/render-tool-calls tool-calls [tool-id] spinner-char width tools-expanded? ui-snapshot)))

(defn render-messages
  "Render all messages, threading tool-call context for :tool role entries."
  ([messages width]
   (render-messages messages width nil))
  ([messages width tool-ctx]
   (when (seq messages)
     (let [{:keys [tool-calls spinner-char tools-expanded? ui-snapshot]} tool-ctx
           render-one (fn [msg]
                        (if (= :tool (:role msg))
                          (or (render-tool-message msg tool-calls spinner-char width tools-expanded? ui-snapshot)
                              ;; tool-id not in tool-calls yet (streaming): skip
                              nil)
                          (render-message msg width)))
           rendered (keep render-one messages)]
       (when (seq rendered)
         (str (str/join "\n\n" rendered) "\n"))))))

(defn render-separator
  [width]
  (charm-style/render shared/sep-style (apply str (repeat (max 1 (or width 1)) "─"))))

; These sequences are kept for backward-compatibility with any external callers
; but are no longer emitted in the view string.  JLine's Display.update handles
; diffing and erasing stale content automatically; embedding raw terminal control
; sequences (ESC[J, ESC[K, ESC[2J) in the view string corrupts JLine's
; AttributedString parsing and causes display artefacts.
(def ^:deprecated clear-to-end-seq "")
(def ^:deprecated clear-line-end-seq "")
(def ^:deprecated clear-screen-home-seq "")

(def notify-info-style    shared/dim-style)
(def notify-warning-style (charm-style/style :fg charm-style/yellow))
(def notify-error-style   shared/error-style)

(defn render-widgets [ui-snapshot placement]
  (when ui-snapshot
    (let [widgets (->> (:widgets ui-snapshot)
                       (filter #(= placement (:placement %)))
                       vec)]
      (when (seq widgets)
        (str (str/join "\n" (mapcat :content widgets)) "\n")))))

(defn render-context-session-tree-widget
  [widget selected-index]
  (when (seq (:content-lines widget))
    (let [selected-index (or selected-index 0)]
      (str (charm-style/render shared/title-style "Session Context") "\n"
           (str/join "\n"
                     (map-indexed (fn [idx line]
                                    (let [selected? (= idx selected-index)
                                          prefix    (if selected? "▸ " "  ")]
                                      (str prefix (:text line))))
                                  (:content-lines widget)))
           "\n"
           (charm-style/render shared/dim-style "  Ctrl+J/K navigate • Alt+Enter activate")
           "\n"))))

(defn middle-truncate
  [s width]
  (if (<= (ansi/display-width s) width)
    s
    (let [half (- (quot width 2) 2)]
      (if (> half 1)
        (let [start   (subs s 0 (min (count s) half))
              end-len (max 0 (dec half))
              end     (if (pos? end-len)
                        (subs s (max 0 (- (count s) end-len)))
                        "")]
          (str start "..." end))
        (subs s 0 (min (count s) (max 1 width)))))))

(defn trim-right-visible
  [s width]
  (if (<= (ansi/visible-width s) width)
    s
    (ansi/strip-ansi (ansi/truncate-to-width s width "..."))))

(defn context-style
  [fraction]
  (cond
    (and (number? fraction) (> fraction 0.9)) shared/error-style
    (and (number? fraction) (> fraction 0.7)) notify-warning-style
    :else shared/dim-style))

(defn build-footer-lines
  [state width]
  (let [model          ((:footer-model-fn state))
        path-text      (get-in model [:footer/lines :path-line] "")
        usage-parts    (get-in model [:footer/usage :parts] [])
        context-text   (get-in model [:footer/context :text] "")
        model-text     (get-in model [:footer/model :text] "")
        context-style* (context-style (get-in model [:footer/context :fraction]))
        left-parts     (mapv (fn [part]
                               (charm-style/render (if (= part context-text)
                                                     context-style*
                                                     shared/dim-style)
                                                   part))
                             usage-parts)
        left           (str/join " " left-parts)
        right0         model-text
        path-line      (charm-style/render shared/dim-style (middle-truncate path-text (max 1 width)))
        stats-line     (let [left*   (if (> (ansi/visible-width left) width)
                                       (trim-right-visible left width)
                                       left)
                             right   (charm-style/render shared/dim-style right0)
                             left-w  (ansi/visible-width left*)
                             right-w (ansi/visible-width right)
                             min-pad 2]
                         (cond
                           (<= (+ left-w min-pad right-w) width)
                           (str left* (apply str (repeat (- width left-w right-w) " ")) right)

                           (> (- width left-w min-pad) 3)
                           (let [avail       (- width left-w min-pad)
                                 right-trunc (charm-style/render shared/dim-style (trim-right-visible right0 avail))]
                             (str left*
                                  (apply str (repeat (max min-pad (- width left-w (ansi/visible-width right-trunc))) " "))
                                  right-trunc))

                           :else left*))
        status-line    (some-> (get-in model [:footer/lines :status-line])
                               (ansi/truncate-to-width width (charm-style/render shared/dim-style "...")))
        activity-line  (some-> (get-in model [:footer/lines :session-activity-line])
                               (->> (charm-style/render shared/dim-style))
                               (ansi/truncate-to-width width (charm-style/render shared/dim-style "...")))]
    (cond-> [path-line stats-line]
      status-line   (conj status-line)
      activity-line (conj activity-line))))

(defn render-footer
  [state width]
  (let [lines (build-footer-lines state width)]
    (str (str/join "\n" lines) "\n")))

(defn render-notifications [ui-snapshot]
  (when ui-snapshot
    (let [notes (vec (:visible-notifications ui-snapshot))]
      (when (seq notes)
        (str (str/join "\n"
                       (map (fn [n]
                              (let [style (case (:level n)
                                            :warning notify-warning-style
                                            :error   notify-error-style
                                            notify-info-style)]
                                (charm-style/render style (str "  " (:message n)))))
                            notes))
             "\n")))))

(defn render-dialog [dialog selected-index input-text]
  (when-let [dialog dialog]
    (case (:kind dialog)
      :confirm
      (str (charm-style/render shared/title-style (:title dialog)) "\n"
           "  " (:message dialog) "\n"
           (charm-style/render shared/dim-style "  Enter=confirm  Escape=cancel") "\n")

      :select
      (let [idx     (or selected-index 0)
            options (:options dialog)]
        (str (charm-style/render shared/title-style (:title dialog)) "\n"
             (str/join "\n"
                       (map-indexed
                        (fn [i opt]
                          (if (= i idx)
                            (str (charm-style/render shared/user-style (str "▸ " (:label opt)))
                                 (when (:description opt)
                                   (str "  " (charm-style/render shared/dim-style (:description opt)))))
                            (str "  " (:label opt))))
                        options))
             "\n"
             (charm-style/render shared/dim-style "  ↑/↓=navigate  Enter=select  Escape=cancel") "\n"))

      :input
      (str (charm-style/render shared/title-style (:title dialog)) "\n"
           "  " (or input-text "") "█" "\n"
           (charm-style/render shared/dim-style "  Enter=submit  Escape=cancel") "\n")

      "")))

(defn wrap-chunks
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

(defn wrap-text-input-view
  [input width]
  (let [{:keys [prompt value pos focused cursor-style
                prompt-style placeholder-style placeholder]} input
        prompt-str (if prompt-style
                     (charm-style/render prompt-style prompt)
                     (or prompt ""))
        prompt-w   (ansi/visible-width (or prompt ""))
        avail      (max 1 (- width prompt-w))
        indent     (apply str (repeat prompt-w \space))
        cursor-sty (or cursor-style (charm-style/style :reverse true))]
    (if (and (empty? value) placeholder (not (str/blank? placeholder)))
      (str prompt-str
           (if focused
             (str (charm-style/render cursor-sty (subs placeholder 0 1))
                  (if placeholder-style
                    (charm-style/render placeholder-style (subs placeholder 1))
                    (subs placeholder 1)))
             (if placeholder-style
               (charm-style/render placeholder-style placeholder)
               placeholder)))
      (let [text   (apply str value)
            chunks (wrap-chunks text avail)]
        (str/join
         "\n"
         (map-indexed
          (fn [i {:keys [text start end]}]
            (let [prefix  (if (zero? i) prompt-str indent)
                  is-last (= i (dec (count chunks)))
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
                       (charm-style/render cursor-sty c-char)
                       after))
                (str prefix text))))
          chunks))))))

(def ^:private autocomplete-max-visible 5)

(defn- autocomplete-window
  [candidates selected-index]
  (let [candidates     (vec candidates)
        total          (count candidates)
        selected-index (-> (or selected-index 0)
                           (max 0)
                           (min (max 0 (dec total))))
        max-start      (max 0 (- total autocomplete-max-visible))
        start          (max 0 (min selected-index max-start))
        end            (min total (+ start autocomplete-max-visible))]
    {:selected-index selected-index
     :start start
     :visible (subvec candidates start end)}))

(defn render-prompt-autocomplete
  [state width]
  (let [{:keys [candidates] raw-selected-index :selected-index} (get-in state [:prompt-input-state :autocomplete])]
    (when (seq candidates)
      (let [{:keys [start visible selected-index]} (autocomplete-window candidates raw-selected-index)
            effective-width  (max 1 (or width 1))
            selected-offset  (- selected-index start)]
        (str "\n"
             (charm-style/render shared/dim-style "Suggestions")
             "\n"
             (str/join
              "\n"
              (map-indexed
               (fn [offset {:keys [label value description]}]
                 (let [selected? (= offset selected-offset)
                       line      (-> (cond-> (str (if selected? "▸ " "  ") (or label value ""))
                                       (seq description) (str " — " description))
                                     (ansi/truncate-to-width effective-width "..."))]
                   (if selected?
                     (charm-style/render shared/user-style line)
                     line)))
               visible)))))))

(defn render-stream-thinking
  [text width]
  (render-thinking-line text width))

(defn render-stream-text
  [text width]
  (when (and text (not (str/blank? text)))
    (let [md-width   (when (and width (> width 3))
                       (- width 3))
          rendered   (or (md/render-markdown text md-width) text)
          lines      (str/split-lines rendered)
          first-line (str (charm-style/render shared/assist-style "ψ: ")
                          (first lines))
          rest-lines (map #(str "   " %) (rest lines))]
      (str (str/join "\n" (cons first-line rest-lines))
           "\n"))))

(defn- render-active-turn-item
  [state item spinner-char width]
  (case (:item-kind item)
    :thinking
    (when-let [s (render-thinking-line (:text item) width)]
      (str s "\n"))

    :text
    (render-stream-text (:text item) width)

    :tool
    (when-let [s (tool-render/render-tool-calls
                  (:tool-calls state)
                  [(:tool-id item)]
                  spinner-char
                  width
                  (boolean (:tools-expanded? state))
                  (:ui-snapshot state))]
      ;; ensure each tool row ends with exactly one newline
      (if (str/ends-with? s "\n") s (str s "\n")))

    nil))

(defn render-active-turn
  [state spinner-char width]
  (let [order    (:active-turn-order state)
        items    (:active-turn-items state)
        rendered (->> order
                      (keep #(render-active-turn-item state (get items %) spinner-char width))
                      (apply str))]
    (when-not (str/blank? rendered)
      rendered)))

(defn- repaint-marker
  "Return a zero-width CSI sequence encoding N as the parameter to 'l'
   (reset mode). Mode numbers in the private range are silently ignored by
   all terminals; JLine's AttributedString also measures them as zero columns.
   psi's strip-ansi removes them. When N changes, line 0 of the view string
   differs from JLine Display's previous-lines, triggering a full repaint
   from line 0 — necessary after the host environment redraws the terminal
   (e.g. Emacs switching away from the shell buffer and back)."
  [n]
  ;; Encode generation as offset from a base that avoids real mode numbers.
  ;; 10000+ is well outside any standardised ANSI/VT mode range.
  (str "\u001b[" (+ 10000 (mod (or n 0) 10000)) "l"))

(defn render-view
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name
                prompt-templates skills extension-summary ui-snapshot
                context-session-tree-widget context-session-tree-selected-index
                tool-calls tool-order
                active-turn-order session-selector current-session-file width
                repaint-generation]} state
        spinner-char   (nth shared/spinner-frames (mod spinner-frame (count shared/spinner-frames)))
        dialog-active? (support/has-active-dialog? state)
        has-progress?  (or (seq active-turn-order)
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
    (if (= :selecting-session phase)
      (str (repaint-marker repaint-generation)
           (render-banner model-name prompt-templates skills extension-summary term-width)
           "\n"
           (selector-render/render-session-selector shared/dim-style render-separator session-selector current-session-file term-width (:session-selector-mode state)))
      (str (repaint-marker repaint-generation)
           (render-banner model-name prompt-templates skills extension-summary term-width)
           "\n"
           (render-messages messages term-width
                            {:tool-calls      tool-calls
                             :spinner-char    spinner-char
                             :tools-expanded? (boolean (:tools-expanded? state))
                             :ui-snapshot     ui-snapshot})
           (when context-session-tree-widget
             (str (render-context-session-tree-widget context-session-tree-widget context-session-tree-selected-index) "\n"))
           (when (= :streaming phase)
             (if has-progress?
               (str (or (render-active-turn state spinner-char term-width)
                        (tool-render/render-tool-calls tool-calls tool-order spinner-char term-width (boolean (:tools-expanded? state)) ui-snapshot)
                        "")
                    "\n")
               (str "\n" (charm-style/render shared/assist-style "ψ: ")
                    spinner-char " thinking…\n")))
           (when error
             (str "\n" (charm-style/render shared/error-style (str "[error: " error "]")) "\n"))
           (render-widgets ui-snapshot :above-editor)
           "\n"
           (render-separator term-width) "\n"
           (if dialog-active?
             (render-dialog (support/active-dialog state) (:dialog-selected-index state) (:dialog-input-text state))
             (str (wrap-text-input-view input term-width)
                  (render-prompt-autocomplete state term-width)
                  (when (= :streaming phase)
                    (str "\n"
                         (charm-style/render shared/dim-style
                                             (if progress-spinner-visible?
                                               "(Enter queues input • Esc interrupts)"
                                               (str spinner-char " waiting for response…")))))))
           "\n"
           (render-separator term-width) "\n"
           (render-widgets ui-snapshot :below-editor)
           (render-footer state term-width)
           (render-notifications ui-snapshot)))))
