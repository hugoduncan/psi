(ns psi.tui.app.render
  (:require
   [charm.core :as charm]
   [clojure.string :as str]
   [psi.app-runtime.footer :as footer]
   [psi.tui.ansi :as ansi]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]
   [psi.tui.markdown :as md]
   [psi.tui.session-selector-render :as selector-render]
   [psi.tui.tool-render :as tool-render]))

(defn render-banner [model-name prompt-templates skills extension-summary]
  (let [visible-skills (remove :disable-model-invocation skills)
        ext-count      (:extension-count extension-summary 0)]
    (str (charm/render shared/title-style "ψ Psi Agent Session") "\n"
         (charm/render shared/dim-style (str "  Model: " model-name)) "\n"
         (when (seq prompt-templates)
           (str (charm/render shared/dim-style
                              (str "  Prompts: "
                                   (str/join ", " (map #(str "/" (:name %)) prompt-templates))))
                "\n"))
         (when (seq visible-skills)
           (str (charm/render shared/dim-style
                              (str "  Skills: "
                                   (str/join ", " (map :name visible-skills))))
                "\n"))
         (when (pos? ext-count)
           (str (charm/render shared/dim-style
                              (str "  Exts: " ext-count " loaded"))
                "\n"))
         (charm/render shared/dim-style "  ESC=interrupt  Ctrl+C=clear/quit  Ctrl+D=exit-empty") "\n")))

(def agent-title-style (charm/style :fg charm/yellow :bold true))
(def agent-head-style (charm/style :fg charm/cyan :bold true))
(def psl-title-style (charm/style :fg charm/green :bold true))

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
               [(str (charm/render agent-title-style "ψ: ⎇ Agent Result"))
                (str "   " (charm/render agent-head-style heading))]
               (when (seq body-lines)
                 (cons "   " (map #(str "   " %) body-lines)))))))

(defn render-message
  [{:keys [role text custom-type]} width]
  (case role
    :user
    (str (charm/render shared/user-style "刀: ") text)

    :assistant
    (cond
      (= "agent-result" custom-type)
      (render-agent-result text width)

      (= "plan-state-learning" custom-type)
      (str (charm/render psl-title-style "ψ: ⟳ Plan/State/Learning")
           "\n"
           "   " (or text ""))

      :else
      (let [md-width   (when (and width (> width 3))
                         (- width 3))
            rendered   (or (md/render-markdown text md-width) text)
            lines      (str/split-lines rendered)
            first-line (str (charm/render shared/assist-style "ψ: ")
                            (first lines))
            rest-lines (map #(str "   " %) (rest lines))]
        (str/join "\n" (cons first-line rest-lines))))

    (str "[" (name role) "] " text)))

(defn render-messages
  [messages width]
  (when (seq messages)
    (str (str/join "\n\n"
                   (map #(render-message % width) messages))
         "\n")))

(defn render-separator
  [width]
  (charm/render shared/sep-style (apply str (repeat (max 1 (or width 1)) "─"))))

(def clear-to-end-seq "\u001b[J")
(def clear-line-end-seq "\u001b[K")
(def clear-screen-home-seq "\u001b[2J\u001b[H")

(def notify-info-style    shared/dim-style)
(def notify-warning-style (charm/style :fg charm/yellow))
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
      (str (charm/render shared/title-style "Session Context") "\n"
           (str/join "\n"
                     (map-indexed (fn [idx line]
                                    (let [selected? (= idx selected-index)
                                          prefix    (if selected? "▸ " "  ")]
                                      (str prefix (:text line))))
                                  (:content-lines widget)))
           "\n"
           (charm/render shared/dim-style "  Ctrl+J/K navigate • Alt+Enter activate")
           "\n"))))

(def footer-query footer/footer-query)

(defn footer-data
  [state]
  (if-let [query-fn (:query-fn state)]
    (try
      (or (query-fn footer-query) {})
      (catch Exception _
        {}))
    {}))

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
  (let [d              (footer-data state)
        model          (footer/footer-model-from-data d {:cwd (:cwd state)})
        path-text      (get-in model [:footer/lines :path-line] "")
        usage-parts    (get-in model [:footer/usage :parts] [])
        context-text   (get-in model [:footer/context :text] "")
        model-text     (get-in model [:footer/model :text] "")
        context-style* (context-style (get-in model [:footer/context :fraction]))
        left-parts     (mapv (fn [part]
                               (charm/render (if (= part context-text)
                                               context-style*
                                               shared/dim-style)
                                             part))
                             usage-parts)
        left           (str/join " " left-parts)
        right0         model-text
        path-line      (charm/render shared/dim-style (middle-truncate path-text (max 1 width)))
        stats-line     (let [left*   (if (> (ansi/visible-width left) width)
                                       (trim-right-visible left width)
                                       left)
                             right   (charm/render shared/dim-style right0)
                             left-w  (ansi/visible-width left*)
                             right-w (ansi/visible-width right)
                             min-pad 2]
                         (cond
                           (<= (+ left-w min-pad right-w) width)
                           (str left* (apply str (repeat (- width left-w right-w) " ")) right)

                           (> (- width left-w min-pad) 3)
                           (let [avail       (- width left-w min-pad)
                                 right-trunc (charm/render shared/dim-style (trim-right-visible right0 avail))]
                             (str left*
                                  (apply str (repeat (max min-pad (- width left-w (ansi/visible-width right-trunc))) " "))
                                  right-trunc))

                           :else left*))
        status-line    (some-> (get-in model [:footer/lines :status-line])
                               (ansi/truncate-to-width width (charm/render shared/dim-style "...")))]
    (cond-> [path-line stats-line]
      status-line (conj status-line))))

(defn render-footer
  [state width]
  (let [lines         (build-footer-lines state width)
        cleared-lines (map #(str % clear-line-end-seq) lines)]
    (str (str/join "\n" cleared-lines) "\n")))

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
                                (charm/render style (str "  " (:message n)))))
                            notes))
             "\n")))))

(defn render-dialog [dialog selected-index input-text]
  (when-let [dialog dialog]
    (case (:kind dialog)
      :confirm
      (str (charm/render shared/title-style (:title dialog)) "\n"
           "  " (:message dialog) "\n"
           (charm/render shared/dim-style "  Enter=confirm  Escape=cancel") "\n")

      :select
      (let [idx     (or selected-index 0)
            options (:options dialog)]
        (str (charm/render shared/title-style (:title dialog)) "\n"
             (str/join "\n"
                       (map-indexed
                        (fn [i opt]
                          (if (= i idx)
                            (str (charm/render shared/user-style (str "▸ " (:label opt)))
                                 (when (:description opt)
                                   (str "  " (charm/render shared/dim-style (:description opt)))))
                            (str "  " (:label opt))))
                        options))
             "\n"
             (charm/render shared/dim-style "  ↑/↓=navigate  Enter=select  Escape=cancel") "\n"))

      :input
      (str (charm/render shared/title-style (:title dialog)) "\n"
           "  " (or input-text "") "█" "\n"
           (charm/render shared/dim-style "  Enter=submit  Escape=cancel") "\n")

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
                     (charm/render prompt-style prompt)
                     (or prompt ""))
        prompt-w   (ansi/visible-width (or prompt ""))
        avail      (max 1 (- width prompt-w))
        indent     (apply str (repeat prompt-w \space))
        cursor-sty (or cursor-style (charm/style :reverse true))]
    (if (and (empty? value) placeholder (not (str/blank? placeholder)))
      (str prompt-str
           (if focused
             (str (charm/render cursor-sty (subs placeholder 0 1))
                  (if placeholder-style
                    (charm/render placeholder-style (subs placeholder 1))
                    (subs placeholder 1)))
             (if placeholder-style
               (charm/render placeholder-style placeholder)
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
                       (charm/render cursor-sty c-char)
                       after))
                (str prefix text))))
          chunks))))))

(defn render-stream-thinking
  [text]
  (when (and text (not (str/blank? text)))
    (str (charm/render shared/dim-style (str "ψ⋯ " text)) "\n")))

(defn render-stream-text
  [text width]
  (when (and text (not (str/blank? text)))
    (let [md-width   (when (and width (> width 3))
                       (- width 3))
          rendered   (or (md/render-markdown text md-width) text)
          lines      (str/split-lines rendered)
          first-line (str (charm/render shared/assist-style "ψ: ")
                          (first lines))
          rest-lines (map #(str "   " %) (rest lines))]
      (str (str/join "\n" (cons first-line rest-lines))
           "\n"))))

(defn render-tool-snapshot
  [snapshot spinner-char width tools-expanded? ui-snapshot tool-id]
  (when snapshot
    (tool-render/render-tool-calls {tool-id (assoc snapshot :expanded? tools-expanded?)}
                                   [tool-id]
                                   spinner-char
                                   width
                                   tools-expanded?
                                   ui-snapshot)))

(defn render-active-turn-event
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

(defn render-active-turn
  [state spinner-char width]
  (let [events   (:active-turn-events state)
        rendered (->> events
                      (keep #(render-active-turn-event state % spinner-char width))
                      (apply str))]
    (when-not (str/blank? rendered)
      rendered)))

(defn render-view
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name
                prompt-templates skills extension-summary ui-snapshot
                context-session-tree-widget context-session-tree-selected-index
                tool-calls tool-order
                active-turn-order active-turn-events session-selector current-session-file width force-clear?]} state
        spinner-char   (nth shared/spinner-frames (mod spinner-frame (count shared/spinner-frames)))
        dialog-active? (support/has-active-dialog? state)
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
       (str (render-banner model-name prompt-templates skills extension-summary)
            "\n"
            (selector-render/render-session-selector shared/dim-style render-separator session-selector current-session-file term-width (:session-selector-mode state))
            clear-to-end-seq)
       (str (render-banner model-name prompt-templates skills extension-summary)
            "\n"
            (render-messages messages term-width)
            (when context-session-tree-widget
              (str (render-context-session-tree-widget context-session-tree-widget context-session-tree-selected-index) "\n"))
            (when (= :streaming phase)
              (if has-progress?
                (str (or (render-active-turn state spinner-char term-width)
                         (tool-render/render-tool-calls tool-calls tool-order spinner-char term-width (boolean (:tools-expanded? state)) ui-snapshot)
                         "")
                     "\n")
                (str "\n" (charm/render shared/assist-style "ψ: ")
                     spinner-char " thinking…\n")))
            (when error
              (str "\n" (charm/render shared/error-style (str "[error: " error "]")) "\n"))
            (render-widgets ui-snapshot :above-editor)
            "\n"
            (render-separator term-width) "\n"
            (if dialog-active?
              (render-dialog (support/active-dialog state) (:dialog-selected-index state) (:dialog-input-text state))
              (str (wrap-text-input-view input term-width)
                   (when (= :streaming phase)
                     (str "\n"
                          (charm/render shared/dim-style
                                        (if progress-spinner-visible?
                                          "(Enter queues input • Esc interrupts)"
                                          (str spinner-char " waiting for response…")))
                          clear-line-end-seq))))
            "\n"
            (render-separator term-width) "\n"
            (render-widgets ui-snapshot :below-editor)
            (render-footer state term-width)
            (render-notifications ui-snapshot)
            clear-to-end-seq)))))
