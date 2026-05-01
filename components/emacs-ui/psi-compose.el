;;; psi-compose.el --- Compose input + send/queue/abort flow for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted compose input area + send routing used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defvar psi-emacs--send-request-function)

(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--set-last-error "psi-run-state" (state message))
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--apply-prefix-overlay "psi-assistant-render" (line-start prefix face))
(declare-function psi-emacs--assistant-finalize "psi-assistant-render" (text))
(declare-function psi-emacs--dispatch-compose-message "psi-session-commands" (message &optional behavior))
(declare-function psi-emacs--clear-assistant-render-state "psi-assistant-render")
(declare-function psi-emacs--clear-thinking-line "psi-assistant-render")
(declare-function psi-emacs--disarm-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--ordered-completing-read "psi-events" (prompt candidates &optional default))

(defvar psi-emacs--allow-protected-input-edit nil
  "Non-nil while psi performs internal rewrites of the editable input area.")

(defconst psi-emacs--startup-banner-line "ψ"
  "Deterministic startup banner line rendered before transcript content.")

(defun psi-emacs--ensure-startup-banner ()
  "Ensure the transcript starts with the deterministic psi startup banner.

Banner is rendered as a standalone first line (`ψ`)."
  (let ((inhibit-read-only t))
    (save-excursion
      (goto-char (point-min))
      (cond
       ((= (point-min) (point-max))
        (insert psi-emacs--startup-banner-line "\n")
        (psi-emacs--mark-region-read-only (point-min) (point)))
       ((save-excursion
          (goto-char (point-min))
          (looking-at-p (concat (regexp-quote psi-emacs--startup-banner-line) "\\n")))
        nil)
       (t
        (insert psi-emacs--startup-banner-line "\n")
        (psi-emacs--mark-region-read-only (point-min) (point)))))))

(defun psi-emacs--mark-region-read-only (start end)
  "Mark transcript/output region START..END as read-only.

Use non-sticky end protection only, so insertion at a writable boundary just
before a protected region stays editable (notably the compose area immediately
before the projection/footer block)."
  (when (< start end)
    (let ((inhibit-read-only t)
          (inhibit-modification-hooks t)
          (psi-emacs--allow-protected-input-edit t))
      (add-text-properties start end '(read-only t rear-nonsticky (read-only))))))

(defun psi-emacs--input-read-only-filter (beg end)
  "Prevent edits outside compose input region between BEG and END.

Programmatic frontend updates can bypass this guard by binding
`inhibit-read-only' or `psi-emacs--allow-protected-input-edit' during
internal input rewrites."
  (unless (or inhibit-read-only
              psi-emacs--allow-protected-input-edit)
    (let* ((range (psi-emacs--input-range))
           (start (car range))
           (finish (cdr range)))
      (unless (and (>= beg start)
                   (<= end finish))
        (signal 'text-read-only nil)))))

(defun psi-emacs--clear-read-only-props-in-input-range (&optional start end)
  "Remove read-only-related text properties from input area overlap START..END.

When START/END are nil, scrub the full current input range."
  (when psi-emacs--state
    (let* ((range (psi-emacs--input-range))
           (input-start (car range))
           (input-end (cdr range))
           (from (max input-start (or start input-start)))
           (to (min input-end (or end input-end))))
      (when (< from to)
        (let ((inhibit-read-only t)
              (inhibit-modification-hooks t)
              (psi-emacs--allow-protected-input-edit t))
          (remove-text-properties from
                                  to
                                  '(read-only nil
                                    front-sticky nil
                                    rear-nonsticky nil)))))))

(defun psi-emacs--input-after-change-scrub (beg end _len)
  "Keep the editable input area free of inherited read-only text properties."
  (unless (or inhibit-read-only
              psi-emacs--allow-protected-input-edit)
    (psi-emacs--clear-read-only-props-in-input-range beg end)))

(defun psi-emacs--install-input-read-only-guard ()
  "Install compose input-only edit guard in current psi buffer."
  (add-hook 'before-change-functions #'psi-emacs--input-read-only-filter nil t)
  (add-hook 'after-change-functions #'psi-emacs--input-after-change-scrub nil t))

(defun psi-emacs--streaming-p ()
  "Return non-nil when the frontend is in streaming mode."
  (and psi-emacs--state
       (eq (psi-emacs-state-run-state psi-emacs--state) 'streaming)))

(defun psi-emacs--draft-end-position ()
  "Return end position of editable draft/input region.

When a projection/footer block is present, draft ends at projection start.
When the projection region is absent or stale (e.g. during the narrow window
between unregister and re-register in `psi-emacs--upsert-projection-block'),
falls back to the last known projection start position stored on state before
resorting to `point-max'.  This prevents footer content from being captured
as part of the composed input."
  (or (when-let ((bounds (and psi-emacs--state
                              (psi-emacs--region-bounds 'projection 'main))))
        (car bounds))
      (and psi-emacs--state
           (psi-emacs-state-last-projection-start psi-emacs--state))
      (point-max)))

(defun psi-emacs--input-separator-marker-cache ()
  "Return compatibility marker cache for the input separator region."
  (when psi-emacs--state
    (psi-emacs-state-input-separator-marker psi-emacs--state)))

(defun psi-emacs--set-input-separator-marker-cache (marker)
  "Store compatibility MARKER cache for the input separator region."
  (when psi-emacs--state
    (setf (psi-emacs-state-input-separator-marker psi-emacs--state) marker)))

(defun psi-emacs--input-separator-marker-valid-p ()
  "Return non-nil when the input separator marker is live.

The marker must point to the separator line start.
When marker state drifts, attempt property-backed recovery first."
  (when psi-emacs--state
    (when-let ((bounds (psi-emacs--region-bounds 'input-separator 'main)))
      (let ((marker (psi-emacs--input-separator-marker-cache)))
        (unless (and (markerp marker) (marker-buffer marker))
          (setq marker (copy-marker (car bounds) t))
          (set-marker-insertion-type marker t)
          (psi-emacs--set-input-separator-marker-cache marker))
        (set-marker marker (car bounds))))
    (and (markerp (psi-emacs--input-separator-marker-cache))
         (marker-buffer (psi-emacs--input-separator-marker-cache))
         (let ((pos (marker-position (psi-emacs--input-separator-marker-cache))))
           (and (<= pos (point-max))
                (save-excursion
                  (goto-char pos)
                  (= pos (line-beginning-position)))
                (eq (char-after pos) ?─))))))

(defun psi-emacs--input-separator-position ()
  "Return buffer position of input separator marker, or nil."
  (when (psi-emacs--input-separator-marker-valid-p)
    (marker-position (psi-emacs--input-separator-marker-cache))))

(defun psi-emacs--input-separator-draft-start-position ()
  "Return first editable draft position after separator line, or nil."
  (when (psi-emacs--input-separator-marker-valid-p)
    (save-excursion
      (goto-char (marker-position (psi-emacs--input-separator-marker-cache)))
      (let ((line-end (line-end-position)))
        (if (< line-end (point-max))
            (1+ line-end)
          line-end)))))

(defun psi-emacs--input-separator-width ()
  "Return separator render width for input area."
  (if (fboundp 'psi-emacs--projection-window-width)
      (psi-emacs--projection-window-width)
    80))

(defun psi-emacs--input-separator-current-width ()
  "Return current rendered width of input separator line, or nil."
  (when (psi-emacs--input-separator-marker-valid-p)
    (save-excursion
      (goto-char (marker-position (psi-emacs--input-separator-marker-cache)))
      (- (line-end-position) (line-beginning-position)))))

(defun psi-emacs--input-separator-needs-refresh-p ()
  "Return non-nil when input separator width does not match current window width."
  (let ((current (psi-emacs--input-separator-current-width))
        (desired (max 1 (psi-emacs--input-separator-width))))
    (and (integerp current)
         (/= current desired))))

(defun psi-emacs--input-separator-line ()
  "Return rendered horizontal separator line for input area."
  (concat (propertize (make-string (max 1 (psi-emacs--input-separator-width)) ?─)
                      'face 'psi-emacs-projection-separator-face
                      'font-lock-face 'psi-emacs-projection-separator-face)
          "\n"))

(defun psi-emacs--refresh-input-separator-line ()
  "Refresh existing input separator line to current window width."
  (when (psi-emacs--input-separator-marker-valid-p)
    (let* ((separator-marker (psi-emacs--input-separator-marker-cache))
           (separator-start (marker-position separator-marker))
           (line (psi-emacs--input-separator-line)))
      (save-excursion
        (goto-char separator-start)
        (let ((inhibit-read-only t)
              (inhibit-modification-hooks t)
              (psi-emacs--allow-protected-input-edit t)
              (bol (line-beginning-position))
              (eol (line-end-position)))
          (delete-region bol (min (point-max) (1+ eol)))
          (goto-char bol)
          (insert line)
          (set-marker separator-marker bol)
          (psi-emacs--region-register 'input-separator
                                      'main
                                      bol
                                      (max (1+ bol) (line-end-position)))
          (when (> bol (point-min))
            (psi-emacs--mark-region-read-only (1- bol) bol)))))))

(defun psi-emacs--draft-anchor-valid-p ()
  "Return non-nil when the current draft anchor marker is valid."
  (and psi-emacs--state
       (markerp (psi-emacs-state-draft-anchor psi-emacs--state))
       (marker-buffer (psi-emacs-state-draft-anchor psi-emacs--state))))

(defun psi-emacs--input-start-position ()
  "Return dedicated input start position."
  (or (psi-emacs--input-separator-draft-start-position)
      (let ((anchor (and psi-emacs--state
                         (psi-emacs-state-draft-anchor psi-emacs--state))))
        (if (and (markerp anchor)
                 (marker-buffer anchor))
            (marker-position anchor)
          (psi-emacs--draft-end-position)))))

(defun psi-emacs--ensure-input-area ()
  "Ensure a dedicated input area exists before projection/footer content."
  (when psi-emacs--state
    (psi-emacs--install-input-read-only-guard)
    (let ((inhibit-read-only t)
          (inhibit-modification-hooks t)
          (psi-emacs--allow-protected-input-edit t))
      (cond
       ((psi-emacs--input-separator-marker-valid-p)
        (let ((separator-pos (psi-emacs--input-separator-position)))
          (when (and (integerp separator-pos)
                     (> separator-pos (point-min)))
            (psi-emacs--mark-region-read-only (1- separator-pos) separator-pos)))
        (when (psi-emacs--input-separator-needs-refresh-p)
          (psi-emacs--refresh-input-separator-line)))
       (t
        (let* ((input-start (psi-emacs--input-start-position))
               (input-end (psi-emacs--draft-end-position))
               (point-in-input? (and (>= (point) input-start)
                                     (<= (point) input-end)))
               (point-offset (and point-in-input? (- (point) input-start)))
               (existing-input (buffer-substring-no-properties input-start input-end)))
          (save-excursion
            (goto-char input-start)
            (delete-region input-start input-end)
            (unless (or (bobp)
                        (eq (char-before) ?\n))
              (let ((newline-start (point)))
                (insert "\n")
                (psi-emacs--mark-region-read-only newline-start (point))))
            (let ((separator-start (point)))
              (insert (psi-emacs--input-separator-line))
              (let ((separator-marker (copy-marker separator-start t)))
                (set-marker-insertion-type separator-marker t)
                (psi-emacs--set-input-separator-marker-cache separator-marker)
                (psi-emacs--region-register 'input-separator
                                            'main
                                            separator-start
                                            (max (1+ separator-start) (1- (point))))
                (when (> separator-start (point-min))
                  (psi-emacs--mark-region-read-only (1- separator-start) separator-start)))
              (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                    (copy-marker (point) nil))
              (insert existing-input)))
          (psi-emacs--clear-read-only-props-in-input-range)
          (when point-in-input?
            (goto-char (+ (psi-emacs--input-start-position)
                          (min (or point-offset 0)
                               (length existing-input)))))))))))

(defun psi-emacs--input-range ()
  "Return dedicated input range as (START . END)."
  (let ((start (psi-emacs--input-start-position))
        (end (psi-emacs--draft-end-position)))
    (cons (min start end) (max start end))))

(defun psi-emacs--tail-draft-text ()
  "Return compose text from dedicated input area."
  (let* ((range (psi-emacs--input-range))
         (start (car range))
         (end (cdr range)))
    (buffer-substring-no-properties start end)))

(defun psi-emacs--composed-text ()
  "Return composed text using region-first, else dedicated input text."
  (if (use-region-p)
      (buffer-substring-no-properties (region-beginning) (region-end))
    (psi-emacs--tail-draft-text)))

(defun psi-emacs--draft-anchor-at-end-p ()
  "Return non-nil when point is currently in input-follow mode."
  (if (psi-emacs--input-separator-marker-valid-p)
      (let* ((range (psi-emacs--input-range))
             (start (car range))
             (end (cdr range))
             (pt (point)))
        (and (<= start pt) (<= pt end)))
    (and (psi-emacs--draft-anchor-valid-p)
         (= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
            (psi-emacs--draft-end-position)))))

(defun psi-emacs--set-draft-anchor-to-end ()
  "Normalize draft anchor marker boundary.

When input area exists, anchor stays at input start. Otherwise, anchor tracks
legacy draft end behavior."
  (when psi-emacs--state
    (let ((anchor (psi-emacs-state-draft-anchor psi-emacs--state))
          (target-pos (if (psi-emacs--input-separator-marker-valid-p)
                          (psi-emacs--input-start-position)
                        (psi-emacs--draft-end-position))))
      (if (and (markerp anchor) (marker-buffer anchor))
          (set-marker anchor target-pos)
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker target-pos nil))))))

(defun psi-emacs--history-reset-navigation ()
  "Reset transient input history navigation state."
  (when psi-emacs--state
    (setf (psi-emacs-state-input-history-index psi-emacs--state) nil)
    (setf (psi-emacs-state-input-history-stash psi-emacs--state) nil)))

(defun psi-emacs--history-record-input (text)
  "Record TEXT in input history when non-empty."
  (when psi-emacs--state
    (let ((text* (or text ""))
          (history (or (psi-emacs-state-input-history psi-emacs--state) '())))
      (when (not (string-empty-p text*))
        (unless (equal text* (car history))
          (setf (psi-emacs-state-input-history psi-emacs--state)
                (cons text* history))))
      (psi-emacs--history-reset-navigation))))

(defun psi-emacs--history-entry-at (idx)
  "Return input history entry at IDX, or nil."
  (let ((history (and psi-emacs--state
                      (psi-emacs-state-input-history psi-emacs--state))))
    (when (and (listp history)
               (integerp idx)
               (>= idx 0)
               (< idx (length history)))
      (nth idx history))))

(defun psi-emacs--replace-input-text (text)
  "Replace dedicated input area content with TEXT and place point at end."
  (psi-emacs--ensure-input-area)
  (let* ((range (psi-emacs--input-range))
         (start (car range))
         (end (cdr range))
         (text* (or text "")))
    (save-excursion
      (let ((inhibit-read-only t)
            (inhibit-modification-hooks t)
            (psi-emacs--allow-protected-input-edit t))
        (goto-char start)
        (delete-region start end)
        (insert text*)
        (psi-emacs--clear-read-only-props-in-input-range start (+ start (length text*)))))
    (goto-char (+ start (length text*)))
    (psi-emacs--set-draft-anchor-to-end)))

(defun psi-emacs-search-input-history ()
  "Search input history via completing-read and populate the input area.

Opens a minibuffer completion over all entries in the current buffer's input
history.  Selecting an entry stashes the current draft (as `M-p' does),
replaces the input area with the selected text, and resets the navigation
index to nil so subsequent `M-p'/`M-n' steps from the top of history.

`C-g' or empty-string submission cancels without modifying the input area or
navigation state."
  (interactive)
  (unless psi-emacs--state
    (user-error "psi buffer is not initialized"))
  (let ((history (or (psi-emacs-state-input-history psi-emacs--state) '())))
    (unless (consp history)
      (user-error "No previous inputs"))
    ;; Use ordered-completing-read to preserve recency order; bare completing-read
    ;; allows UIs such as vertico/ivy to re-sort alphabetically.
    (let ((chosen (psi-emacs--ordered-completing-read "Previous input: " history)))
      ;; REQUIRE-MATCH is t inside ordered-completing-read, so chosen is always a
      ;; member of history.  The empty-string guard defends against completion
      ;; frameworks that return "" on cancel despite REQUIRE-MATCH.
      (when (and chosen (not (string-empty-p chosen)))
        (setf (psi-emacs-state-input-history-stash psi-emacs--state)
              (psi-emacs--tail-draft-text))
        (setf (psi-emacs-state-input-history-index psi-emacs--state) nil)
        (psi-emacs--replace-input-text chosen)))))

(defun psi-emacs-previous-input ()
  "Navigate to older input history entry in dedicated input area."
  (interactive)
  (unless psi-emacs--state
    (user-error "psi buffer is not initialized"))
  (let* ((history (or (psi-emacs-state-input-history psi-emacs--state) '()))
         (max-idx (1- (length history)))
         (idx (psi-emacs-state-input-history-index psi-emacs--state)))
    (when (< max-idx 0)
      (user-error "No previous inputs"))
    (unless (integerp idx)
      (setf (psi-emacs-state-input-history-stash psi-emacs--state)
            (psi-emacs--tail-draft-text))
      (setq idx -1))
    (let ((next-idx (min max-idx (1+ idx))))
      (setf (psi-emacs-state-input-history-index psi-emacs--state) next-idx)
      (psi-emacs--replace-input-text (or (psi-emacs--history-entry-at next-idx) "")))))

(defun psi-emacs-next-input ()
  "Navigate to newer input history entry in dedicated input area."
  (interactive)
  (unless psi-emacs--state
    (user-error "psi buffer is not initialized"))
  (let ((idx (psi-emacs-state-input-history-index psi-emacs--state)))
    (unless (integerp idx)
      (user-error "No newer input"))
    (if (> idx 0)
        (let ((next-idx (1- idx)))
          (setf (psi-emacs-state-input-history-index psi-emacs--state) next-idx)
          (psi-emacs--replace-input-text (or (psi-emacs--history-entry-at next-idx) "")))
      (let ((stash (or (psi-emacs-state-input-history-stash psi-emacs--state) "")))
        (psi-emacs--history-reset-navigation)
        (psi-emacs--replace-input-text stash)))))

(defun psi-emacs--clear-input ()
  "Clear dedicated input area and keep point in input."
  (if (psi-emacs--input-separator-marker-valid-p)
      (psi-emacs--replace-input-text "")
    (psi-emacs--set-draft-anchor-to-end)))

(defun psi-emacs--consume-tail-draft (used-region-p)
  "Advance/clear draft after successful compose dispatch.

USED-REGION-P non-nil means compose came from region,
so input text is untouched."
  (unless used-region-p
    (psi-emacs--clear-input)))

(defun psi-emacs--transcript-append-position ()
  "Return insertion position for transcript/tool/error output.

Output is inserted immediately above the dedicated input separator."
  (or (psi-emacs--input-separator-position)
      (psi-emacs--draft-end-position)))

(defun psi-emacs--transport-ready-for-request-p (state)
  "Return non-nil when STATE can dispatch a frontend RPC request.

When no rpc client is attached yet (e.g. isolated tests), dispatch is allowed.
When a client is present, transport must be `ready'."
  (or (null (psi-emacs-state-rpc-client state))
      (eq (psi-emacs-state-transport-state state) 'ready)))

(defun psi-emacs--transport-not-ready-message (state op)
  "Return deterministic request rejection message for STATE and OP."
  (format "Cannot run `%s`: transport is %s. Reconnect with C-c C-r."
          op
          (or (psi-emacs-state-transport-state state) 'unknown)))

(defun psi-emacs--request-dispatch-failed-message (op)
  "Return deterministic dispatch failure message for OP."
  (format "Cannot run `%s`: request dispatch failed. Reconnect with C-c C-r." op))

(defun psi-emacs--dispatch-request (op params &optional callback)
  "Dispatch OP PARAMS CALLBACK through configured request function.

Returns non-nil when the request was dispatched."
  (when psi-emacs--state
    (if (psi-emacs--transport-ready-for-request-p psi-emacs--state)
        (let ((sent? (funcall psi-emacs--send-request-function
                              psi-emacs--state
                              op
                              params
                              callback)))
          (if (or sent? callback)
              t
            (let ((message (psi-emacs--request-dispatch-failed-message op)))
              (psi-emacs--set-last-error psi-emacs--state message)
              (psi-emacs--set-run-state psi-emacs--state 'error)
              (message "psi: %s" message)
              nil)))
      (let ((message (psi-emacs--transport-not-ready-message psi-emacs--state op)))
        (psi-emacs--set-last-error psi-emacs--state message)
        (psi-emacs--set-run-state psi-emacs--state 'error)
        (message "psi: %s" message)
        nil))))

(defun psi-emacs--request-frontend-exit ()
  "Request frontend exit for current psi buffer."
  (when (buffer-live-p (current-buffer))
    (kill-buffer (current-buffer))))

(defun psi-emacs--append-user-message-to-transcript (text)
  "Append sent user TEXT above dedicated input separator."
  (let ((text* (or text "")))
    (when (not (string-empty-p text*))
      (save-excursion
        (psi-emacs--ensure-newline-before-append)
        (let ((line-start (point)))
          (let ((inhibit-read-only t))
            (insert (format "User: %s\n" text*))
            (psi-emacs--mark-region-read-only line-start (point)))
          (goto-char line-start)
          (psi-emacs--apply-prefix-overlay line-start "User: " 'psi-emacs-user-prompt-face))))))

(defun psi-emacs--append-assistant-message (text)
  "Append assistant TEXT as a finalized transcript line."
  (psi-emacs--assistant-finalize text))

(defun psi-emacs--consume-dispatched-input (used-region-p message &optional local-only)
  "Apply post-dispatch input lifecycle for USED-REGION-P and MESSAGE.

When LOCAL-ONLY is non-nil, input is still consumed but transcript copy/history
entry are skipped (local command interception path)."
  (when (and psi-emacs--state
             (buffer-live-p (current-buffer)))
    (unless local-only
      (psi-emacs--history-record-input message)
      (psi-emacs--append-user-message-to-transcript message))
    (psi-emacs--consume-tail-draft used-region-p)
    ;; Keep dedicated input boundary resilient after submit cycles.
    ;; Marker drift can make the separator vanish until some later refresh.
    (psi-emacs--ensure-input-area)))

(defun psi-emacs-send-from-buffer (prefix)
  "Send composed text using canonical send semantics.

Slash-prefixed input is always routed to backend `command`.
With PREFIX, non-slash streaming input uses queue behavior.
Without PREFIX, non-slash streaming input uses steer behavior."
  (interactive "P")
  (when (and psi-emacs--state
             (not (psi-emacs--input-separator-marker-valid-p)))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position)))
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text))
         (local-only nil)
         (result (psi-emacs--dispatch-compose-message message (if prefix "queue" "steer")))
         (dispatched? (plist-get result :dispatched?)))
    (setq local-only (plist-get result :local-only?))
    (when dispatched?
      (psi-emacs--consume-dispatched-input used-region-p message local-only))))

(defun psi-emacs-queue-from-buffer ()
  "Queue composed text; slash-prefixed input still uses backend `command`."
  (interactive)
  (when (and psi-emacs--state
             (not (psi-emacs--input-separator-marker-valid-p)))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position)))
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text))
         (local-only nil)
         (result (psi-emacs--dispatch-compose-message message "queue"))
         (dispatched? (plist-get result :dispatched?)))
    (setq local-only (plist-get result :local-only?))
    (when dispatched?
      (psi-emacs--consume-dispatched-input used-region-p message local-only))))

(defun psi-emacs--restore-dropped-steering-to-draft (dropped-text)
  "Prepend DROPPED-TEXT to the current input draft area."
  (when (and dropped-text (not (string-empty-p dropped-text)))
    (let ((inhibit-read-only t))
      (save-excursion
        (goto-char (psi-emacs--input-start-position))
        (insert dropped-text "\n")))))

(defun psi-emacs-interrupt ()
  "Request deferred interrupt at the next turn boundary.

When streaming, marks the interrupt as pending and transitions run-state
to `interrupt_pending'.  The agent finishes its current turn then stops.
When idle, this is a silent no-op."
  (interactive)
  (when (and psi-emacs--state
             (eq (psi-emacs-state-run-state psi-emacs--state) 'streaming))
    (let ((buffer (current-buffer))
          (state psi-emacs--state))
      ;; Optimistically transition to interrupt_pending immediately
      (psi-emacs--set-run-state state 'interrupt_pending)
      (psi-emacs--dispatch-request
       "interrupt" nil
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (when (and (eq (alist-get :kind frame) :response)
                          (eq (alist-get :ok frame) t))
                 (let* ((data (alist-get :data frame))
                        (pending (alist-get :pending data))
                        (dropped (alist-get :dropped-steering-text data)))
                   (when pending
                     (psi-emacs--restore-dropped-steering-to-draft dropped))))))))))))


(defun psi-emacs-abort ()
  "Abort active streaming request via RPC and transition to non-streaming UI state."
  (interactive)
  (psi-emacs--dispatch-request "abort" nil)
  (when psi-emacs--state
    (psi-emacs--clear-assistant-render-state)
    (psi-emacs--clear-thinking-line)
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--set-run-state psi-emacs--state 'idle)))

(defun psi-emacs--ensure-newline-before-append ()
  "Ensure transcript append boundary starts on a fresh line.

Content appends immediately above the dedicated input separator."
  (let ((inhibit-read-only t))
    (goto-char (psi-emacs--transcript-append-position))
    (unless (or (bobp)
                (eq (char-before) ?\n))
      (insert "\n"))))

(provide 'psi-compose)

;;; psi-compose.el ends here
