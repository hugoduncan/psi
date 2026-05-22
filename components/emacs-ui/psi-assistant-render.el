;;; psi-assistant-render.el --- Assistant streaming render helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted assistant stream/finalize helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(declare-function psi-emacs--next-region-id "psi-regions" (kind))
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-unregister "psi-regions" (kind id))
(declare-function psi-emacs--draft-anchor-at-end-p "psi-compose")
(declare-function psi-emacs--mark-region-read-only "psi-compose" (start end))
(declare-function psi-emacs--ensure-newline-before-append "psi-compose")
(declare-function psi-emacs--set-draft-anchor-to-end "psi-compose")
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--reset-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--input-start-position "psi-compose")
(declare-function psi-emacs--draft-end-position "psi-compose")
(declare-function psi-emacs--disarm-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--transcript-append-position "psi-compose")
(declare-function psi-emacs--event-data-get "psi-events" (data keys))

(defconst psi-emacs--assistant-line-prefix "ψ: "
  "Prefix rendered for assistant transcript lines.")

(defconst psi-emacs--thinking-line-prefix "· "
  "Prefix rendered for assistant thinking transcript lines.")

(defconst psi-emacs--assistant-stream-verbatim-property 'psi-emacs-stream-verbatim
  "Text property used to mark in-progress assistant content as verbatim.")

(defun psi-emacs--assistant-active-id (&optional ensure)
  "Return active assistant region id, creating one when ENSURE is non-nil."
  (or (and psi-emacs--state
           (psi-emacs-state-active-assistant-id psi-emacs--state))
      (when (and ensure psi-emacs--state)
        (let ((id (psi-emacs--next-region-id 'assistant)))
          (setf (psi-emacs-state-active-assistant-id psi-emacs--state) id)
          id))))

(defun psi-emacs--thinking-active-id (&optional ensure)
  "Return active thinking region id, creating one when ENSURE is non-nil."
  (or (and psi-emacs--state
           (psi-emacs-state-active-thinking-id psi-emacs--state))
      (when (and ensure psi-emacs--state)
        (let ((id (psi-emacs--next-region-id 'thinking)))
          (setf (psi-emacs-state-active-thinking-id psi-emacs--state) id)
          id))))

(defun psi-emacs--sync-assistant-region (start end)
  "Register assistant region identity for START..END."
  (when-let ((id (psi-emacs--assistant-active-id t)))
    (psi-emacs--region-register 'assistant id start end)))

(defun psi-emacs--sync-thinking-region (start end)
  "Register thinking region identity for START..END."
  (when-let ((id (psi-emacs--thinking-active-id t)))
    (psi-emacs--region-register 'thinking id start end)))

(defun psi-emacs--assistant-range-live-p (range)
  "Return non-nil when assistant RANGE markers are live in a buffer."
  (and (consp range)
       (markerp (car range))
       (markerp (cdr range))
       (marker-buffer (car range))
       (marker-buffer (cdr range))))

(defun psi-emacs--assistant-range-current ()
  "Return live assistant range, recovering from region properties when needed."
  (let ((range (psi-emacs--assistant-range-cache)))
    (if (psi-emacs--assistant-range-live-p range)
        range
      (when-let* ((id (psi-emacs--assistant-active-id nil))
                  (bounds (psi-emacs--region-bounds 'assistant id))
                  (start (car bounds))
                  (end (cdr bounds)))
        (let ((restored (cons (copy-marker start nil)
                              (copy-marker end nil))))
          (psi-emacs--set-assistant-range-cache restored)
          restored)))))

(defun psi-emacs--thinking-range-cache ()
  "Return compatibility marker cache for the active thinking region."
  (when psi-emacs--state
    (psi-emacs-state-thinking-range psi-emacs--state)))

(defun psi-emacs--set-thinking-range-cache (range)
  "Store compatibility marker RANGE cache for the active thinking region."
  (when psi-emacs--state
    (setf (psi-emacs-state-thinking-range psi-emacs--state) range)))

(defun psi-emacs--thinking-range-current ()
  "Return live thinking range, recovering from region properties when needed."
  (let ((range (psi-emacs--thinking-range-cache)))
    (if (psi-emacs--assistant-range-live-p range)
        range
      (when-let* ((id (psi-emacs--thinking-active-id nil))
                  (bounds (psi-emacs--region-bounds 'thinking id))
                  (start (car bounds))
                  (end (cdr bounds)))
        (let ((restored (cons (copy-marker start nil)
                              (copy-marker end nil))))
          (psi-emacs--set-thinking-range-cache restored)
          restored)))))

(defun psi-emacs--assistant-start-marker ()
  "Return live assistant start marker.

Recover from region properties when needed."
  (when-let ((range (psi-emacs--assistant-range-current)))
    (when (psi-emacs--assistant-range-live-p range)
      (car range))))

(defun psi-emacs--assistant-end-marker ()
  "Return live assistant end marker, recovering from region properties when needed."
  (when-let ((range (psi-emacs--assistant-range-current)))
    (when (psi-emacs--assistant-range-live-p range)
      (cdr range))))

(defun psi-emacs--clear-marker-range (range)
  "Clear marker RANGE and return nil."
  (when (and (consp range) (markerp (car range)))
    (set-marker (car range) nil))
  (when (and (consp range) (markerp (cdr range)))
    (set-marker (cdr range) nil))
  nil)

(defun psi-emacs--assistant-range-cache ()
  "Return compatibility marker cache for the active assistant region."
  (when psi-emacs--state
    (psi-emacs-state-assistant-range psi-emacs--state)))

(defun psi-emacs--set-assistant-range-cache (range)
  "Store compatibility marker RANGE cache for the active assistant region."
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-range psi-emacs--state) range)))

(defun psi-emacs--clear-assistant-render-state ()
  "Clear transient assistant render state without editing transcript text."
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (when-let ((id (psi-emacs-state-active-assistant-id psi-emacs--state)))
      (psi-emacs--region-unregister 'assistant id))
    (setf (psi-emacs-state-active-assistant-id psi-emacs--state) nil)
    (psi-emacs--set-assistant-range-cache
     (psi-emacs--clear-marker-range
      (psi-emacs--assistant-range-cache)))))

(defun psi-emacs--clear-thinking-render-state (&optional clear-archived)
  "Clear transient thinking render state without editing transcript text.

When CLEAR-ARCHIVED is non-nil, also clear archived thinking marker ranges."
  (when psi-emacs--state
    (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil)
    (when-let ((id (psi-emacs-state-active-thinking-id psi-emacs--state)))
      (psi-emacs--region-unregister 'thinking id))
    (setf (psi-emacs-state-active-thinking-id psi-emacs--state) nil)
    (psi-emacs--set-thinking-range-cache
     (psi-emacs--clear-marker-range
      (psi-emacs--thinking-range-cache)))
    (when clear-archived
      (dolist (range (psi-emacs-state-thinking-archived-ranges psi-emacs--state))
        (psi-emacs--clear-marker-range range))
      (setf (psi-emacs-state-thinking-archived-ranges psi-emacs--state) nil))))

(defun psi-emacs--render-assistant-line (text)
  "Render assistant TEXT in canonical line format."
  (concat psi-emacs--assistant-line-prefix (or text "") "\n"))

(defun psi-emacs--render-thinking-line (text)
  "Render assistant thinking TEXT in canonical line format."
  (concat psi-emacs--thinking-line-prefix (or text "") "\n"))

(defun psi-emacs--apply-prefix-overlay (line-start prefix face)
  "Apply high-priority FACE overlay for PREFIX at LINE-START.

The overlay must exclude insertions at both boundaries. Tool rows and other
transcript text are often inserted immediately before or after an existing
`ψ:`/`User:` prefix. If boundary insertions are considered inside the overlay,
the prefix face leaks onto unrelated later text."
  (let ((line-end (line-end-position))
        (prefix-end (+ line-start (length prefix))))
    (when (<= prefix-end line-end)
      (remove-overlays line-start prefix-end 'category 'psi-emacs-prefix)
      (let ((ov (make-overlay line-start prefix-end nil t nil)))
        (overlay-put ov 'category 'psi-emacs-prefix)
        (overlay-put ov 'face face)
        (overlay-put ov 'priority 1000)
        (overlay-put ov 'evaporate t)))))

(defun psi-emacs--assistant-content-range (range)
  "Return assistant content (not prefix/newline) region for RANGE."
  (when (psi-emacs--assistant-range-live-p range)
    (let* ((start (marker-position (car range)))
           (end (marker-position (cdr range)))
           (content-start (+ start (length psi-emacs--assistant-line-prefix)))
           (content-end (if (and (> end content-start)
                                 (eq (char-before end) ?\n))
                            (1- end)
                          end)))
      (cons content-start (max content-start content-end)))))

(defun psi-emacs--apply-assistant-stream-verbatim-range (start end)
  "Apply stream-time assistant verbatim properties to START..END."
  (when (< start end)
    (let ((inhibit-read-only t))
      (add-text-properties
       start
       end
       (list 'face 'default
             'font-lock-face nil
             psi-emacs--assistant-stream-verbatim-property t)))))

(defun psi-emacs--set-assistant-stream-verbatim (range)
  "Apply verbatim display properties to in-progress assistant RANGE."
  (when-let ((content-range (psi-emacs--assistant-content-range range)))
    (psi-emacs--apply-assistant-stream-verbatim-range (car content-range)
                                                      (cdr content-range))))

(defun psi-emacs--clear-assistant-stream-verbatim (range)
  "Remove verbatim display properties from assistant RANGE."
  (when-let ((content-range (psi-emacs--assistant-content-range range)))
    (let ((start (car content-range))
          (end (cdr content-range)))
      (when (< start end)
        (let ((inhibit-read-only t))
          (remove-list-of-text-properties
           start
           end
           (list 'face
                 'font-lock-face
                 psi-emacs--assistant-stream-verbatim-property)))))))

(defun psi-emacs--apply-finalized-assistant-markdown (start end)
  "Apply markdown/font-lock processing to finalized assistant text START..END."
  (when (< start end)
    (condition-case _
        (cond
         ((fboundp 'markdown-fontify-region)
          (markdown-fontify-region start end))
         ((fboundp 'font-lock-flush)
          (font-lock-flush start end)
          (when (fboundp 'font-lock-ensure)
            (font-lock-ensure start end))))
      (error nil))))

(defun psi-emacs--process-finalized-assistant-range (range)
  "Finalize assistant RANGE rendering.

Clears stream-time verbatim properties, then applies markdown processing."
  (when (psi-emacs--assistant-range-live-p range)
    (psi-emacs--clear-assistant-stream-verbatim range)
    (when-let ((content-range (psi-emacs--assistant-content-range range)))
      (psi-emacs--apply-finalized-assistant-markdown (car content-range)
                                                     (cdr content-range)))))

(defun psi-emacs--assistant-line-content-text (range)
  "Return current assistant content text for live RANGE."
  (when-let ((content-range (psi-emacs--assistant-content-range range)))
    (buffer-substring-no-properties (car content-range) (cdr content-range))))

(defun psi-emacs--redraw-assistant-line (range text stream-verbatim)
  "Redraw live assistant RANGE with complete TEXT.

When STREAM-VERBATIM is non-nil, apply stream-time properties over the
newly-rendered assistant content.  This helper is the narrow full-redraw
instrumentation seam for streaming tests."
  (let ((start (car range))
        (end (cdr range)))
    (let ((inhibit-read-only t))
      (goto-char start)
      (delete-region start end)
      (insert (psi-emacs--render-assistant-line text))
      (psi-emacs--mark-region-read-only start (point))
      (set-marker end (point))
      (psi-emacs--sync-assistant-region start end))
    (when stream-verbatim
      (psi-emacs--set-assistant-stream-verbatim range))))

(defun psi-emacs--create-assistant-line (text stream-verbatim)
  "Create a live assistant line containing TEXT.

When STREAM-VERBATIM is non-nil, apply stream-time properties to the
created assistant content."
  (psi-emacs--ensure-newline-before-append)
  (let ((start (copy-marker (point) nil))
        ;; Non-advancing end marker: tool rows inserted immediately after
        ;; this assistant line must not cause the end marker to drift past
        ;; them, which would make delete-region swallow the tool row on the
        ;; next streaming delta.
        (end (copy-marker (point) nil)))
    (let ((inhibit-read-only t))
      (insert (psi-emacs--render-assistant-line text))
      (psi-emacs--mark-region-read-only start (point)))
    (set-marker end (point))
    (let ((range (cons start end)))
      (psi-emacs--set-assistant-range-cache range)
      (psi-emacs--sync-assistant-region start end)
      (when stream-verbatim
        (psi-emacs--set-assistant-stream-verbatim range))
      range)))

(defun psi-emacs--append-assistant-line-suffix (range suffix stream-verbatim)
  "Append SUFFIX to live assistant RANGE.

When STREAM-VERBATIM is non-nil, apply stream-time properties only to the
inserted suffix range."
  (when (not (string-empty-p suffix))
    (let* ((content-range (psi-emacs--assistant-content-range range))
           (insert-pos (cdr content-range)))
      (goto-char insert-pos)
      (let ((inhibit-read-only t))
        (insert suffix)
        (psi-emacs--mark-region-read-only insert-pos (point))
        (set-marker (cdr range) (1+ (point)))
        (psi-emacs--sync-assistant-region (car range) (cdr range)))
      (when stream-verbatim
        (psi-emacs--apply-assistant-stream-verbatim-range insert-pos (point))))))

(defun psi-emacs--set-assistant-line (text &optional stream-verbatim)
  "Create or update the single assistant line with TEXT.

When STREAM-VERBATIM is non-nil, keep the assistant content visually verbatim
while streaming; markdown processing is deferred until finalization."
  (when psi-emacs--state
    (let ((buffer-undo-list (if stream-verbatim t buffer-undo-list))
          (follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs--assistant-range-current)))
      (if (psi-emacs--assistant-range-live-p range)
          (save-excursion
            (let ((current (psi-emacs--assistant-line-content-text range)))
              (if (and current (string-prefix-p current (or text "")))
                  (psi-emacs--append-assistant-line-suffix
                   range
                   (substring (or text "") (length current))
                   stream-verbatim)
                (progn
                  (psi-emacs--redraw-assistant-line range text stream-verbatim)
                  (goto-char (marker-position (car range)))
                  (psi-emacs--apply-prefix-overlay
                   (line-beginning-position)
                   psi-emacs--assistant-line-prefix
                   'psi-emacs-assistant-reply-face)))))
        (save-excursion
          (setq range (psi-emacs--create-assistant-line text stream-verbatim))
          (goto-char (marker-position (car range)))
          (psi-emacs--apply-prefix-overlay
           (line-beginning-position)
           psi-emacs--assistant-line-prefix
           'psi-emacs-assistant-reply-face)))
      (unless stream-verbatim
        (psi-emacs--clear-assistant-stream-verbatim
         (psi-emacs--assistant-range-current)))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--thinking-content-range (range)
  "Return thinking content (not prefix/newline) region for RANGE."
  (when (psi-emacs--assistant-range-live-p range)
    (let* ((start (marker-position (car range)))
           (end (marker-position (cdr range)))
           (content-start (+ start (length psi-emacs--thinking-line-prefix)))
           (content-end (if (and (> end content-start)
                                 (eq (char-before end) ?\n))
                            (1- end)
                          end)))
      (cons content-start (max content-start content-end)))))

(defun psi-emacs--thinking-line-content-text (range)
  "Return current thinking content text for live RANGE."
  (when-let ((content-range (psi-emacs--thinking-content-range range)))
    (buffer-substring-no-properties (car content-range) (cdr content-range))))

(defun psi-emacs--redraw-thinking-line (range text)
  "Redraw live thinking RANGE with complete TEXT.

This helper is the narrow full-redraw instrumentation seam for streaming
tests."
  (let ((start (car range))
        (end (cdr range)))
    (let ((inhibit-read-only t))
      (goto-char start)
      (delete-region start end)
      (insert (psi-emacs--render-thinking-line text))
      (psi-emacs--mark-region-read-only start (point))
      (set-marker end (point))
      (psi-emacs--sync-thinking-region start end))))

(defun psi-emacs--create-thinking-line (text)
  "Create a live thinking line containing TEXT."
  (let* ((assistant-start-marker (psi-emacs--assistant-start-marker)))
    (if assistant-start-marker
        (progn
          (goto-char (marker-position assistant-start-marker))
          (unless (or (bobp) (eq (char-before) ?\n))
            (let ((inhibit-read-only t)) (insert "\n")))
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) nil)))
            (let ((inhibit-read-only t))
              (insert (psi-emacs--render-thinking-line text))
              (psi-emacs--mark-region-read-only start (point)))
            (set-marker end (point))
            (let ((range (cons start end)))
              (psi-emacs--set-thinking-range-cache range)
              (psi-emacs--sync-thinking-region start end)
              (set-marker assistant-start-marker (point))
              range)))
      (psi-emacs--ensure-newline-before-append)
      (let ((start (copy-marker (point) nil))
            (end (copy-marker (point) nil)))
        (let ((inhibit-read-only t))
          (insert (psi-emacs--render-thinking-line text))
          (psi-emacs--mark-region-read-only start (point)))
        (set-marker end (point))
        (let ((range (cons start end)))
          (psi-emacs--set-thinking-range-cache range)
          (psi-emacs--sync-thinking-region start end)
          range)))))

(defun psi-emacs--append-thinking-line-suffix (range suffix)
  "Append SUFFIX to live thinking RANGE."
  (when (not (string-empty-p suffix))
    (let* ((content-range (psi-emacs--thinking-content-range range))
           (insert-pos (cdr content-range)))
      (goto-char insert-pos)
      (let ((inhibit-read-only t))
        (insert suffix)
        (psi-emacs--mark-region-read-only insert-pos (point))
        (set-marker (cdr range) (1+ (point)))
        (psi-emacs--sync-thinking-region (car range) (cdr range))))))

(defun psi-emacs--set-thinking-line (text)
  "Create or update the single assistant thinking line with TEXT."
  (when psi-emacs--state
    (let ((buffer-undo-list t)
          (follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs--thinking-range-current)))
      (if (psi-emacs--assistant-range-live-p range)
          (save-excursion
            (let ((current (psi-emacs--thinking-line-content-text range)))
              (if (and current (string-prefix-p current (or text "")))
                  (psi-emacs--append-thinking-line-suffix
                   range
                   (substring (or text "") (length current)))
                (progn
                  (psi-emacs--redraw-thinking-line range text)
                  (goto-char (marker-position (car range)))
                  (psi-emacs--apply-prefix-overlay
                   (line-beginning-position)
                   psi-emacs--thinking-line-prefix
                   'psi-emacs-assistant-thinking-face)))))
        (save-excursion
          (setq range (psi-emacs--create-thinking-line text))
          (goto-char (marker-position (car range)))
          (psi-emacs--apply-prefix-overlay
           (line-beginning-position)
           psi-emacs--thinking-line-prefix
           'psi-emacs-assistant-thinking-face)))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--archive-thinking-line ()
  "Freeze current thinking line as transcript history and clear in-progress state."
  (when psi-emacs--state
    (let ((range (psi-emacs--thinking-range-current)))
      (when (psi-emacs--assistant-range-live-p range)
        (let ((archived (cons (copy-marker (marker-position (car range)) nil)
                              (copy-marker (marker-position (cdr range)) nil))))
          (setf (psi-emacs-state-thinking-archived-ranges psi-emacs--state)
                (append (psi-emacs-state-thinking-archived-ranges psi-emacs--state)
                        (list archived)))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil))
      (when-let ((id (psi-emacs-state-active-thinking-id psi-emacs--state)))
        (psi-emacs--region-unregister 'thinking id))
      (setf (psi-emacs-state-active-thinking-id psi-emacs--state) nil)
      (psi-emacs--set-thinking-range-cache nil)
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil))))

(defun psi-emacs--clear-thinking-line ()
  "Remove any in-progress assistant thinking line and clear thinking state."
  (when psi-emacs--state
    (let ((range (psi-emacs--thinking-range-current)))
      (when (psi-emacs--assistant-range-live-p range)
        (save-excursion
          (let ((inhibit-read-only t))
            (delete-region (car range) (cdr range)))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil))
      (when-let ((id (psi-emacs-state-active-thinking-id psi-emacs--state)))
        (psi-emacs--region-unregister 'thinking id))
      (setf (psi-emacs-state-active-thinking-id psi-emacs--state) nil)
      (psi-emacs--set-thinking-range-cache nil)
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil))))

(defun psi-emacs--common-prefix-length (a b)
  "Return length of common prefix shared by strings A and B."
  (let* ((a* (or a ""))
         (b* (or b ""))
         (limit (min (length a*) (length b*)))
         (idx 0))
    (while (and (< idx limit)
                (eq (aref a* idx) (aref b* idx)))
      (setq idx (1+ idx)))
    idx))

(defun psi-emacs--merge-assistant-stream-text (current incoming)
  "Merge CURRENT in-progress text with INCOMING stream payload.

Supports both payload styles:
- cumulative snapshots (incoming replaces current when it extends current)
- incremental deltas (incoming appends)

Also tolerates snapshot updates that differ near the previous tail
(e.g. trailing newline churn while content grows)."
  (let ((current* (or current ""))
        (incoming* (or incoming "")))
    (cond
     ((string-empty-p incoming*) current*)
     ((string-empty-p current*) incoming*)
     ((string-prefix-p current* incoming*)
      incoming*)
     (t
      (let* ((cur-len (length current*))
             (in-len (length incoming*))
             (cp (psi-emacs--common-prefix-length current* incoming*))
             (tail-close? (and (> in-len cur-len)
                               (>= cp (max 1 (1- cur-len))))))
        (if tail-close?
            incoming*
          (concat current* incoming*)))))))

(defun psi-emacs--assistant-delta (text)
  "Apply assistant delta TEXT to the in-progress assistant block."
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (psi-emacs--reset-stream-watchdog psi-emacs--state)
    (let ((next (psi-emacs--merge-assistant-stream-text
                 (psi-emacs-state-assistant-in-progress psi-emacs--state)
                 text)))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) next)
      (psi-emacs--set-assistant-line next t))))

(defun psi-emacs--assistant-thinking-delta (text)
  "Apply assistant thinking TEXT to the in-progress thinking block.

The backend emits the full accumulated thinking text so far (not an
incremental delta), so we store and render TEXT directly without
appending to any prior local state."
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (psi-emacs--reset-stream-watchdog psi-emacs--state)
    (let ((next (or text "")))
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) next)
      (psi-emacs--set-thinking-line next))))

(defun psi-emacs--freeze-assistant-line ()
  "Freeze a live in-progress assistant line at the tool boundary.

When streaming text exists before a tool call, the text belongs to
the current turn and must remain visible in the transcript.  Freezing
it decouples the buffer range from the in-progress streaming state:
the text stays but `assistant-in-progress' is cleared so the next
`text-delta' creates a fresh `ψ:' line rather than concatenating with
stale pre-tool text.

Applies markdown finalization to the frozen range and unregisters
the active assistant region identity (a fresh id will be assigned on
the next streaming delta)."
  (when psi-emacs--state
    (let* ((range (psi-emacs--assistant-range-current))
           (range-live (psi-emacs--assistant-range-live-p range))
           (in-progress (psi-emacs-state-assistant-in-progress psi-emacs--state))
           (has-text (and (stringp in-progress)
                          (not (string-empty-p in-progress)))))
      (when (and has-text range-live)
        ;; Finalize markdown rendering on the frozen range.
        (psi-emacs--process-finalized-assistant-range range)
        ;; Unregister region identity so the next streaming delta allocates
        ;; a fresh id and range — preventing the next text-delta from updating
        ;; the stale frozen line.
        (when-let ((id (psi-emacs-state-active-assistant-id psi-emacs--state)))
          (psi-emacs--region-unregister 'assistant id))
        (setf (psi-emacs-state-active-assistant-id psi-emacs--state) nil)
        (psi-emacs--set-assistant-range-cache nil)
        (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)))))

(defun psi-emacs--assistant-before-tool-event ()
  "Prepare transcript before rendering a tool lifecycle event.

When thinking text is in progress, split before tool output so subsequent
thinking deltas render as a fresh block after the tool output.

When assistant streaming text is in progress (model wrote text before
calling a tool), freeze it as a permanent transcript line so the next
turn's text-delta creates a fresh `ψ:' line rather than concatenating
with the stale pre-tool text.

Normally this archives the live thinking line. If marker state drifted and
only `thinking-in-progress' text remains, clear that stale in-progress text
so the next thinking delta still starts a fresh block."
  (when psi-emacs--state
    ;; Freeze any live assistant text before the tool boundary.
    (psi-emacs--freeze-assistant-line)
    (let* ((thinking-text (or (psi-emacs-state-thinking-in-progress psi-emacs--state) ""))
           (has-thinking-text (not (string-empty-p thinking-text)))
           (range-live (psi-emacs--assistant-range-live-p
                        (psi-emacs--thinking-range-current))))
      (when has-thinking-text
        (if range-live
            (psi-emacs--archive-thinking-line)
          (let ((range (psi-emacs--thinking-range-current)))
            (when (and (consp range) (markerp (car range)))
              (set-marker (car range) nil))
            (when (and (consp range) (markerp (cdr range)))
              (set-marker (cdr range) nil)))
          (when-let ((id (psi-emacs-state-active-thinking-id psi-emacs--state)))
            (psi-emacs--region-unregister 'thinking id))
          (setf (psi-emacs-state-active-thinking-id psi-emacs--state) nil)
          (psi-emacs--set-thinking-range-cache nil)
          (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil))))))

(defun psi-emacs--assistant-finalize (text)
  "Finalize assistant block with TEXT and clear in-progress state.

Appends a blank line after the finalized reply to visually separate it
from the next user prompt."
  (when psi-emacs--state
    (let* ((text* (if (and (stringp text) (string-empty-p text)) nil text))
           (final (or text* (psi-emacs-state-assistant-in-progress psi-emacs--state) ""))
           ;; Capture cursor intent before any transcript mutations.
           ;; Transcript inserts above the separator shift absolute positions,
           ;; so we record offset-from-input-start rather than absolute point.
           ;; follow-input: user has cursor somewhere in the input area.
           ;; input-offset: position relative to input-start (restored afterward).
           (input-start-before (psi-emacs--input-start-position))
           (follow-input (and input-start-before
                              (>= (point) input-start-before)
                              (<= (point) (psi-emacs--draft-end-position))))
           (input-offset (and follow-input (- (point) input-start-before))))
      (psi-emacs--set-assistant-line final nil)
      (psi-emacs--process-finalized-assistant-range
       (psi-emacs--assistant-range-current))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
      (when-let ((id (psi-emacs-state-active-assistant-id psi-emacs--state)))
        (psi-emacs--region-unregister 'assistant id))
      (setf (psi-emacs-state-active-assistant-id psi-emacs--state) nil)
      (psi-emacs--set-assistant-range-cache nil)
      ;; Archive (not clear) any live thinking block — it is part of the
      ;; transcript and must remain visible after the reply is finalized.
      (psi-emacs--archive-thinking-line)
      (psi-emacs--disarm-stream-watchdog psi-emacs--state)
      (psi-emacs--set-run-state psi-emacs--state 'idle)
      ;; Blank line after reply — visual breathing room before next user prompt.
      (save-excursion
        (psi-emacs--ensure-newline-before-append)
        (let ((inhibit-read-only t))
          (insert "\n")))
      (if follow-input
          ;; Restore cursor to the same relative position within the input area.
          ;; The input text is unchanged; only the transcript above it grew.
          (let* ((input-start-after (psi-emacs--input-start-position))
                 (input-end-after (psi-emacs--draft-end-position))
                 (restored (+ input-start-after (or input-offset 0))))
            (goto-char (max input-start-after (min restored input-end-after))))
        (goto-char (psi-emacs--transcript-append-position))))))

(defun psi-emacs--assistant-content-kind (value)
  "Return normalized kind/type token for VALUE, or nil."
  (let ((raw (and (listp value)
                  (psi-emacs--event-data-get value '(:type type :kind kind)))))
    (cond
     ((keywordp raw) raw)
     ((symbolp raw) raw)
     ((stringp raw) (intern (downcase raw)))
     (t nil))))

(defun psi-emacs--assistant-content-map-p (value)
  "Return non-nil when VALUE looks like a content alist map."
  (and (listp value)
       (or (assoc :kind value)
           (assoc 'kind value)
           (assoc :type value)
           (assoc 'type value)
           (assoc :blocks value)
           (assoc 'blocks value)
           (assoc :content value)
           (assoc 'content value))))

(defun psi-emacs--assistant-content-blocks (content)
  "Return CONTENT as a list of block maps when possible."
  (cond
   ((vectorp content) (append content nil))
   ((psi-emacs--assistant-content-map-p content)
    (let ((kind (psi-emacs--assistant-content-kind content)))
      (cond
       ((memq kind '(:structured structured))
        (psi-emacs--assistant-content-blocks
         (psi-emacs--event-data-get content '(:blocks blocks))))
       ((memq kind '(:text text :error error))
        (list content))
       ((psi-emacs--event-data-get content '(:content content))
        (psi-emacs--assistant-content-blocks
         (psi-emacs--event-data-get content '(:content content))))
       (t nil))))
   ((listp content) content)
   (t nil)))

(defun psi-emacs--assistant-block->text (block)
  "Render one assistant BLOCK as transcript text, or nil."
  (when (listp block)
    (let ((kind (psi-emacs--assistant-content-kind block)))
      (cond
       ((memq kind '(:text text))
        (psi-emacs--event-data-get block '(:text text :message message)))
       ((memq kind '(:error error))
        (when-let ((err (psi-emacs--event-data-get block
                                                   '(:text text :message message :error-message error-message))))
          (format "[error] %s" err)))
       ((memq kind '(:structured structured))
        (psi-emacs--assistant-content->text block))
       (t nil)))))

(defun psi-emacs--assistant-content->text (content)
  "Extract assistant display text from CONTENT blocks.

Supports canonical block vectors and structured content maps
(`:kind :structured :blocks [...]`)."
  (cond
   ((stringp content)
    content)

   ((psi-emacs--assistant-content-map-p content)
    (let ((kind (psi-emacs--assistant-content-kind content)))
      (cond
       ((memq kind '(:text text))
        (or (psi-emacs--event-data-get content '(:text text :message message)) ""))

       ((memq kind '(:structured structured))
        (psi-emacs--assistant-content->text
         (psi-emacs--event-data-get content '(:blocks blocks))))

       ((psi-emacs--event-data-get content '(:content content))
        (psi-emacs--assistant-content->text
         (psi-emacs--event-data-get content '(:content content))))

       (t
        (string-join
         (delq nil
               (mapcar #'psi-emacs--assistant-block->text
                       (or (psi-emacs--assistant-content-blocks content) '())))
         "")))))

   (t
    (string-join
     (delq nil
           (mapcar #'psi-emacs--assistant-block->text
                   (or (psi-emacs--assistant-content-blocks content) '())))
     ""))))

(provide 'psi-assistant-render)

;;; psi-assistant-render.el ends here
