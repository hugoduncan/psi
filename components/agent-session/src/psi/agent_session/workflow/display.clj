(ns psi.agent-session.workflow.display
  "Shared helpers for built-in workflow display/read-model rendering.")

(defn merged-display
  "Merge workflow display maps, preferring public read-model fields for the
   user-facing display surface while preserving the fallback action line from
   the private/runtime display model."
  [display public]
  {:top-line       (or (:top-line public) (:top-line display))
   :detail-line    (or (:detail-line public) (:detail-line display))
   :question-lines (vec (or (:question-lines public)
                            (:question-lines display)
                            []))
   :action-line    (or (:action-line public) (:action-line display))})

(defn display-lines
  "Turn a merged workflow display map into ordered render lines.

   `decorate-top-line` may wrap the top line as a string or richer map for UI
   consumers that attach actions to the headline."
  [{:keys [top-line detail-line question-lines action-line]}
   & {:keys [decorate-top-line]
      :or   {decorate-top-line identity}}]
  (cond-> [(decorate-top-line top-line)]
    (seq (str detail-line)) (conj detail-line)
    (seq question-lines) (into question-lines)
    (some? action-line) (conj action-line)))

(defn line->text
  "Project one rendered workflow line to plain text for CLI/list consumers."
  [line]
  (if (map? line)
    (:text line)
    line))

(defn text-lines
  "Project rendered workflow lines to plain text, preserving order."
  [lines]
  (map line->text lines))
