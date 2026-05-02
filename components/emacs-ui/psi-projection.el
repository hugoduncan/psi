;;; psi-projection.el --- Footer/widget/status/notification projection helpers  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted projection and notification lifecycle helpers used by psi.el.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'psi-globals)
(require 'psi-widget-projection)

(declare-function psi-emacs--event-data-get "psi-events" (data keys))
(declare-function psi-emacs--input-separator-marker-valid-p "psi-compose")
(declare-function psi-emacs--input-separator-needs-refresh-p "psi-compose")
(declare-function psi-emacs--refresh-input-separator-line "psi-compose")
(declare-function psi-emacs--draft-anchor-at-end-p "psi-compose")
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-unregister "psi-regions" (kind id))
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--set-draft-anchor-to-end "psi-compose")

(defvar psi-emacs-notification-timeout-seconds)

(defvar-local psi-emacs--upsert-projection-in-progress nil
  "Non-nil while `psi-emacs--upsert-projection-block' is executing.
Guards against re-entrant calls that can occur when large undo records cause
`undo-outer-limit-truncate' to fire `display-warning', which calls
`sit-for 0', which processes pending RPC process output mid-upsert.  Without
this guard the projection region is unregistered when the re-entrant call
arrives, causing a second projection block to be inserted and the
draft/footer boundary to be corrupted.")

(defvar psi-emacs--send-request-function)
(defvar psi-emacs--slash-command-handler-function)

(defvar psi-emacs--projection-widget-action-keymap
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "RET") #'psi-emacs--projection-activate-widget-action)
    (define-key map [mouse-1] #'psi-emacs--projection-activate-widget-action)
    map))

(defun psi-emacs--projection-action->command (action)
  "Return slash command string from ACTION map, or nil."
  (let* ((type* (and (listp action)
                     (psi-emacs--event-data-get action '(:type type))))
         (type (cond
                ((keywordp type*) (substring (symbol-name type*) 1))
                ((symbolp type*) (symbol-name type*))
                ((stringp type*) (downcase type*))
                (t nil)))
         (command (and (listp action)
                       (psi-emacs--event-data-get action '(:command command)))))
    (when (and (equal type "command")
               (stringp command)
               (not (string-empty-p (string-trim command))))
      (string-trim command))))

(defun psi-emacs--projection-tree-command-session-id (command)
  "Return session id for `/tree <id>` COMMAND, or nil when not applicable."
  (when (stringp command)
    (let* ((trimmed (string-trim command))
           (tail (and (string-prefix-p "/tree" trimmed)
                      (string-trim (string-remove-prefix "/tree" trimmed)))))
      (when (and (stringp tail)
                 (not (string-empty-p tail)))
        tail))))

(defun psi-emacs--projection-fork-command-entry-id (command)
  "Return entry id for `/fork <entry-id>` COMMAND, or nil when not applicable."
  (when (stringp command)
    (let* ((trimmed (string-trim command))
           (tail (and (string-prefix-p "/fork" trimmed)
                      (string-trim (string-remove-prefix "/fork" trimmed)))))
      (when (and (stringp tail)
                 (not (string-empty-p tail)))
        tail))))

(defun psi-emacs--projection-activate-widget-action (&optional event)
  "Activate widget action at point or mouse EVENT."
  (interactive)
  (let* ((pos (cond
               ((and event (consp event))
                (posn-point (event-end event)))
               (t (point))))
         (command (and (integerp pos)
                       (get-text-property pos 'psi-widget-command))))
    (when (and (stringp command)
               (not (string-empty-p command))
               psi-emacs--state)
      (let* ((trimmed (string-trim command))
             (slash-candidate? (and (not (string-empty-p trimmed))
                                    (string-prefix-p "/" trimmed)))
             (tree-session-id (psi-emacs--projection-tree-command-session-id command))
             (fork-entry-id (psi-emacs--projection-fork-command-entry-id command))
             (prompt-fallback
              (lambda ()
                (when (functionp psi-emacs--send-request-function)
                  (funcall psi-emacs--send-request-function
                           psi-emacs--state
                           "prompt"
                           `((:message . ,command)))))))
        (cond
         ((and fork-entry-id
               (functionp psi-emacs--send-request-function))
          (funcall psi-emacs--send-request-function
                   psi-emacs--state
                   "fork"
                   `((:entry-id . ,fork-entry-id))))
         ((and tree-session-id
               (fboundp 'psi-emacs--request-switch-session-by-id))
          (psi-emacs--request-switch-session-by-id psi-emacs--state tree-session-id))
         ((fboundp 'psi-emacs--dispatch-compose-message)
          (psi-emacs--dispatch-compose-message command))
         ((and slash-candidate?
               (functionp psi-emacs--slash-command-handler-function))
          (unless (funcall psi-emacs--slash-command-handler-function
                           psi-emacs--state
                           command)
            (funcall prompt-fallback)))
         (t
          (funcall prompt-fallback)))))))

(defun psi-emacs--projection-seq (value)
  "Normalize VALUE into a proper list sequence."
  (cond
   ((vectorp value) (append value nil))
   ((listp value) value)
   (t nil)))

(defun psi-emacs--projection-item-key (item keys)
  "Return deterministic sort/display key from ITEM over KEYS."
  (let ((value (and (listp item)
                    (psi-emacs--event-data-get item keys))))
    (if (stringp value)
        value
      (format "%s" (or value "")))))

(defun psi-emacs--projection-item-text (item)
  "Return display text for projection ITEM."
  (let ((value (and (listp item)
                    (psi-emacs--event-data-get item
                                               '(:text text :message message :value value :content content)))))
    (cond
     ((stringp value) value)
     ((null value) "")
     (t (format "%s" value)))))

(defun psi-emacs--projection-widget-content-lines (widget)
  "Return display content lines for projected WIDGET."
  (let* ((content-lines (and (listp widget)
                             (psi-emacs--event-data-get widget '(:content-lines content-lines))))
         (content (and (listp widget)
                       (psi-emacs--event-data-get widget '(:content content))))
         (content-seq (psi-emacs--projection-seq (or content-lines content)))
         (lines (and content-seq
                     (delq nil
                           (mapcar (lambda (line)
                                     (cond
                                      ((stringp line)
                                       (unless (string-empty-p line) line))
                                      ((listp line)
                                       (let ((txt (psi-emacs--event-data-get line '(:text text))))
                                         (when (and (stringp txt)
                                                    (not (string-empty-p txt)))
                                           txt)))
                                      ((null line) nil)
                                      (t (format "%s" line))))
                                   content-seq)))))
    (if content-seq
        (or lines (list ""))
      (list (psi-emacs--projection-item-text widget)))))

(defun psi-emacs--session-runtime-face (runtime-state)
  "Return face symbol for RUNTIME-STATE string."
  (pcase runtime-state
    ("waiting" 'psi-emacs-session-waiting-face)
    ("running" 'psi-emacs-session-running-face)
    ("retrying" 'psi-emacs-session-retrying-face)
    ("compacting" 'psi-emacs-session-compacting-face)
    (_ nil)))

(defun psi-emacs--propertize-session-line (txt runtime-state is-current)
  "Return TXT with session-state fragment faces applied from metadata."
  (let ((out (copy-sequence txt)))
    (when (and is-current (string-match (regexp-quote " ← current") out))
      (add-text-properties (match-beginning 0) (match-end 0)
                           '(face psi-emacs-session-current-face
                             font-lock-face psi-emacs-session-current-face)
                           out))
    (when (and (stringp runtime-state)
               (not (string-empty-p runtime-state))
               (string-match (regexp-quote (format " [%s]" runtime-state)) out))
      (let ((face (psi-emacs--session-runtime-face runtime-state)))
        (when face
          (add-text-properties (match-beginning 0) (match-end 0)
                               (list 'face face 'font-lock-face face)
                               out))))
    out))

(defun psi-emacs--projection-widget-lines (widget)
  "Render projected WIDGET as one or more deterministic content lines.

Widget metadata (placement/extension-id/widget-id) is intentionally omitted
from transcript projection rendering."
  (let* ((content-lines (and (listp widget)
                             (psi-emacs--event-data-get widget '(:content-lines content-lines))))
         (line-seq (psi-emacs--projection-seq content-lines))
         (rendered (and line-seq
                        (delq nil
                              (mapcar (lambda (line)
                                        (cond
                                         ((stringp line)
                                          (unless (string-empty-p line) line))
                                         ((listp line)
                                          (let* ((txt (psi-emacs--event-data-get line '(:text text)))
                                                 (action (psi-emacs--event-data-get line '(:action action)))
                                                 (command (psi-emacs--projection-action->command action))
                                                 (runtime-state (psi-emacs--event-data-get line '(:runtime-state runtime-state)))
                                                 (is-current (psi-emacs--event-data-get line '(:is-current is-current)))
                                                 (styled-txt (and (stringp txt)
                                                                  (not (string-empty-p txt))
                                                                  (psi-emacs--propertize-session-line txt runtime-state is-current))))
                                            (when styled-txt
                                              (if command
                                                  (let ((out (psi-emacs--projection-apply-default-face styled-txt
                                                                                                      'psi-emacs-projection-widget-face)))
                                                    (add-text-properties 0 (length out)
                                                                         '(mouse-face highlight
                                                                           follow-link t)
                                                                         out)
                                                    (add-text-properties 0 (length out)
                                                                         (list 'help-echo command
                                                                               'keymap psi-emacs--projection-widget-action-keymap
                                                                               'psi-widget-command command)
                                                                         out)
                                                    out)
                                                styled-txt))))
                                         ((null line) nil)
                                         (t (format "%s" line))))
                                      line-seq)))))
    (if line-seq
        (or rendered (list ""))
      (or (psi-emacs--projection-widget-content-lines widget)
          (list "")))))

(defun psi-emacs--projection-window-width ()
  "Return render width for footer projection in current buffer.

Prefers `window-text-width' when available (tracks visible text area in
columns). Falls back to body-width minus left/right margins for compatibility.
Returns 80 for non-visible buffers (e.g. tests/background)."
  (if-let ((win (get-buffer-window (current-buffer) t)))
      (let* ((text-width* (and (fboundp 'window-text-width)
                               (window-text-width win)))
             (text-width (if (and (integerp text-width*) (> text-width* 0))
                             text-width*
                           (let* ((body (window-body-width win))
                                  (margins (window-margins win))
                                  (left-margin (or (car margins) 0))
                                  (right-margin (or (cdr margins) 0)))
                             (- body left-margin right-margin)))))
        (max 1 text-width))
    80))

(defun psi-emacs--projection-footer-stats-line (data)
  "Return canonical footer stats line text from event DATA.

Consumes structured footer fields directly so Emacs does not need to re-parse
backend semantic text."
  (let* ((usage-parts* (psi-emacs--event-data-get data '(:usage-parts usage-parts)))
         (usage-parts (psi-emacs--projection-seq usage-parts*))
         (model-text (psi-emacs--event-data-get data '(:model-text model-text)))
         (structured-left (and usage-parts
                               (string-join
                                (delq nil
                                      (mapcar (lambda (part)
                                                (when (and (stringp part)
                                                           (not (string-empty-p part)))
                                                  part))
                                              usage-parts))
                                " ")))
         (structured-right (and (stringp model-text)
                                (not (string-empty-p model-text))
                                model-text)))
    (cond
     ((and structured-right
           structured-left
           (not (string-empty-p structured-left)))
      (let* ((width (psi-emacs--projection-window-width))
             (left-w (string-width structured-left))
             (right-w (string-width structured-right))
             (min-pad 2)
             (total (+ left-w min-pad right-w)))
        (if (<= total width)
            (concat structured-left
                    (make-string (- width left-w right-w) ?\s)
                    structured-right)
          (concat structured-left " " structured-right))))
     (structured-right structured-right)
     (structured-left structured-left)
     (t nil))))

(defun psi-emacs--projection-footer-session-activity-line (data)
  "Return a propertized footer session activity line.

Use structured DATA when present."
  (let* ((buckets* (psi-emacs--event-data-get data '(:session-activity-buckets session-activity-buckets)))
         (buckets (psi-emacs--projection-seq buckets*)))
    (if (not buckets)
        (let ((line* (psi-emacs--event-data-get data '(:session-activity-line session-activity-line))))
          (and (stringp line*)
               (not (string-empty-p line*))
               (propertize line*
                           'face 'psi-emacs-projection-footer-status-face
                           'font-lock-face 'psi-emacs-projection-footer-status-face)))
      (let ((parts
             (delq nil
                   (mapcar (lambda (bucket)
                             (let* ((state (psi-emacs--event-data-get bucket '(:state state)))
                                    (labels* (psi-emacs--event-data-get bucket '(:labels labels)))
                                    (labels (psi-emacs--projection-seq labels*))
                                    (face (psi-emacs--session-runtime-face state)))
                               (when (and (stringp state)
                                          labels)
                                 (let ((bucket-text (format "%s %s" state (string-join labels ", "))))
                                   (if face
                                       (propertize bucket-text 'face face 'font-lock-face face)
                                     bucket-text)))))
                           buckets))))
        (when parts
          (concat (propertize "sessions: "
                              'face 'psi-emacs-projection-footer-status-face
                              'font-lock-face 'psi-emacs-projection-footer-status-face)
                  (string-join parts
                               (propertize " · "
                                           'face 'psi-emacs-projection-footer-status-face
                                           'font-lock-face 'psi-emacs-projection-footer-status-face))))))))

(defun psi-emacs--projection-footer-text (data)
  "Extract deterministic footer projection text from event DATA.

Canonical payload lines are rendered as multi-line text in this order:
path-line, stats-line, session-activity-line, status-line (blank lines omitted)."
  (let* ((path-line* (psi-emacs--event-data-get data '(:path-line path-line)))
         (stats-line* (psi-emacs--projection-footer-stats-line data))
         (session-activity-line (psi-emacs--projection-footer-session-activity-line data))
         (status-line* (psi-emacs--event-data-get data '(:status-line status-line)))
         (path-line (and (stringp path-line*)
                         (not (string-empty-p path-line*))
                         (propertize path-line*
                                     'face 'psi-emacs-projection-footer-path-face
                                     'font-lock-face 'psi-emacs-projection-footer-path-face)))
         (stats-line (and (stringp stats-line*)
                          (not (string-empty-p stats-line*))
                          (propertize stats-line*
                                      'face 'psi-emacs-projection-footer-stats-face
                                      'font-lock-face 'psi-emacs-projection-footer-stats-face)))
         (status-line (and (stringp status-line*)
                           (not (string-empty-p status-line*))
                           (propertize status-line*
                                       'face 'psi-emacs-projection-footer-status-face
                                       'font-lock-face 'psi-emacs-projection-footer-status-face)))
         (canonical-lines (delq nil (list path-line stats-line session-activity-line status-line))))
    (when canonical-lines
      (string-join canonical-lines "\n"))))

(defun psi-emacs--cancel-notification-timer (state notification-id)
  "Cancel notification timer for NOTIFICATION-ID in STATE, if present."
  (when (and state notification-id)
    (let* ((timers (psi-emacs-state-projection-notification-timers state))
           (timer (and (hash-table-p timers)
                       (gethash notification-id timers))))
      (when (timerp timer)
        (cancel-timer timer))
      (when (hash-table-p timers)
        (remhash notification-id timers)))))

(defun psi-emacs--clear-notification-lifecycle (state)
  "Clear projected notification state and cancel all lifecycle timers in STATE."
  (when state
    (let ((timers (psi-emacs-state-projection-notification-timers state)))
      (when (hash-table-p timers)
        (maphash (lambda (_id timer)
                   (when (timerp timer)
                     (cancel-timer timer)))
                 timers)
        (clrhash timers)))
    (setf (psi-emacs-state-projection-notifications state) nil)
    (setf (psi-emacs-state-projection-notification-seq state) 0)))

(defun psi-emacs--projection-notification-line (notification)
  "Render deterministic line for projected NOTIFICATION item."
  (format "- [%s] %s"
          (or (plist-get notification :extension-id) "")
          (or (plist-get notification :text) "")))

(defun psi-emacs--notification-remove-by-id (state notification-id)
  "Remove projected notification by NOTIFICATION-ID from STATE and re-render."
  (when (and state notification-id)
    (let* ((notifications (or (psi-emacs-state-projection-notifications state) '()))
           (next (cl-remove-if (lambda (item)
                                 (equal (plist-get item :id) notification-id))
                               notifications)))
      (psi-emacs--cancel-notification-timer state notification-id)
      (setf (psi-emacs-state-projection-notifications state) next)
      (when (eq state psi-emacs--state)
        (psi-emacs--upsert-projection-block)))))

(defun psi-emacs--schedule-notification-dismiss (state notification-id)
  "Schedule auto-dismiss timer for NOTIFICATION-ID in STATE."
  (when (and state notification-id)
    (psi-emacs--cancel-notification-timer state notification-id)
    (let ((timer (run-at-time psi-emacs-notification-timeout-seconds nil
                              (lambda (buffer st id)
                                (when (and (buffer-live-p buffer) st)
                                  (with-current-buffer buffer
                                    (psi-emacs--notification-remove-by-id st id))))
                              (current-buffer)
                              state
                              notification-id)))
      (puthash notification-id timer
               (psi-emacs-state-projection-notification-timers state)))))

(defun psi-emacs--handle-notification-event (data)
  "Apply `ui/notification` DATA to local projection lifecycle state."
  (when psi-emacs--state
    (let* ((seq (1+ (or (psi-emacs-state-projection-notification-seq psi-emacs--state) 0)))
           (extension-id (psi-emacs--projection-item-key data '(:extension-id extension-id)))
           (text (psi-emacs--projection-item-text data))
           (notification-id (format "n-%s" seq))
           (entry (list :id notification-id :seq seq :extension-id extension-id :text text))
           (existing (or (psi-emacs-state-projection-notifications psi-emacs--state) '()))
           (next (append existing (list entry))))
      (setf (psi-emacs-state-projection-notification-seq psi-emacs--state) seq)
      ;; enforce max visible 3, keeping oldest->newest order
      (while (> (length next) 3)
        (let ((drop (car next)))
          (setq next (cdr next))
          (psi-emacs--cancel-notification-timer psi-emacs--state (plist-get drop :id))))
      (setf (psi-emacs-state-projection-notifications psi-emacs--state) next)
      (psi-emacs--schedule-notification-dismiss psi-emacs--state notification-id)
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--projection-apply-default-face (text face)
  "Apply FACE to unstyled characters in TEXT, preserving fragment faces."
  (let ((out (copy-sequence text)))
    (dotimes (i (length out))
      (when (and (null (get-text-property i 'face out))
                 (null (get-text-property i 'font-lock-face out)))
        (add-text-properties i (1+ i)
                             (list 'face face 'font-lock-face face)
                             out)))
    out))

(defun psi-emacs--projection-render-block (state)
  "Render deterministic projection block from STATE."
  (let* ((widgets (or (psi-emacs-state-projection-widgets state) '()))
         (statuses (or (psi-emacs-state-projection-statuses state) '()))
         (notifications (or (psi-emacs-state-projection-notifications state) '()))
         (footer (psi-emacs-state-projection-footer state))
         (separator-raw (make-string (psi-emacs--projection-window-width) ?─))
         (separator (propertize separator-raw
                                'face 'psi-emacs-projection-separator-face
                                'font-lock-face 'psi-emacs-projection-separator-face))
         (lines nil))
    ;; Declarative widget specs (psi.ui.widget-spec node trees)
    (let ((spec-rendered (psi-widget-projection-render-specs state)))
      (when (and (stringp spec-rendered) (not (string-empty-p spec-rendered)))
        (push spec-rendered lines)
        (push separator lines)))
    ;; Legacy flat content-lines widgets
    (when widgets
      (dolist (widget widgets)
        (dolist (widget-line (psi-emacs--projection-widget-lines widget))
          (push (psi-emacs--projection-apply-default-face widget-line
                                                         'psi-emacs-projection-widget-face)
                lines)))
      ;; Keep widget content visually isolated from statuses/footer.
      ;; Only add this divider when there are projected widgets.
      (push separator lines))
    (when statuses
      (push (propertize "Extension Statuses:"
                        'face 'psi-emacs-projection-heading-face
                        'font-lock-face 'psi-emacs-projection-heading-face)
            lines)
      (dolist (status statuses)
        (let ((extension-id (psi-emacs--projection-item-key status '(:extension-id extension-id)))
              (text (psi-emacs--projection-item-text status)))
          (push (concat "- ["
                        (propertize extension-id
                                    'face 'psi-emacs-projection-extension-id-face
                                    'font-lock-face 'psi-emacs-projection-extension-id-face)
                        "] "
                        text)
                lines))))
    (when notifications
      (push (propertize "Extension Notifications:"
                        'face 'psi-emacs-projection-heading-face
                        'font-lock-face 'psi-emacs-projection-heading-face)
            lines)
      (dolist (notification notifications)
        (let ((extension-id (or (plist-get notification :extension-id) ""))
              (text (or (plist-get notification :text) "")))
          (push (concat "- ["
                        (propertize extension-id 'face 'psi-emacs-projection-extension-id-face)
                        "] "
                        text)
                lines))))
    (when (and (stringp footer)
               (not (string-empty-p footer)))
      (push (psi-emacs--projection-apply-default-face footer
                                                     'psi-emacs-projection-footer-face)
            lines))
    (if lines
        ;; Blank line before separator keeps visual breathing room between
        ;; transcript input area and projection/footer content.
        (concat "\n"
                separator "\n"
                (string-join (nreverse lines) "\n")
                "\n")
      "")))

(defun psi-emacs--ensure-newline-before-projection-append ()
  "Ensure projection append boundary starts on a fresh line at buffer tail."
  (goto-char (point-max))
  (unless (or (bobp)
              (eq (char-before) ?\n))
    (insert "\n")))

(defun psi-emacs--upsert-projection-block ()
  "Upsert deterministic projection block from current state.

Projection is always reinserted at end-of-buffer so footer/projection content
tracks transcript growth like a terminal footer."
  (when (and psi-emacs--state
             (not psi-emacs--upsert-projection-in-progress))
    (setq psi-emacs--upsert-projection-in-progress t)
    (unwind-protect
        (progn
          ;; Keep dedicated input separator width aligned with current visible window
          ;; whenever projection is re-rendered (notably after startup hydration).
          (when (and (psi-emacs--input-separator-marker-valid-p)
                     (psi-emacs--input-separator-needs-refresh-p))
            (psi-emacs--refresh-input-separator-line))
          (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
                 (range (when-let ((bounds (psi-emacs--region-bounds 'projection 'main)))
                          (cons (copy-marker (car bounds) nil)
                                (copy-marker (cdr bounds) nil))))
                 (start (and (consp range) (car range)))
                 (end (and (consp range) (cdr range)))
                 (rendered (psi-emacs--projection-render-block psi-emacs--state)))
            (let ((inhibit-read-only t)
                  ;; Suppress undo recording for projection block changes.
                  ;; The projection block is ephemeral UI recomputed from state on
                  ;; every update; undo of its content is meaningless.  More
                  ;; critically, without this, large undo records can trigger
                  ;; `undo-outer-limit-truncate', which calls `display-warning',
                  ;; which calls `sit-for 0', which processes pending process output
                  ;; mid-upsert.  That allows re-entrant RPC events to call
                  ;; `upsert-projection-block' while the projection region is
                  ;; unregistered, corrupting the draft/footer boundary and causing
                  ;; footer content to be captured as part of the submitted prompt.
                  (buffer-undo-list t))
              ;; Remove previous projection block first.
              (when (and (markerp start)
                         (markerp end)
                         (marker-buffer start)
                         (marker-buffer end))
                ;; Snapshot projection start before deleting so draft-end-position
                ;; has a reliable backstop during the gap between unregister and
                ;; re-register (after-change hooks can fire inside delete-region).
                (setf (psi-emacs-state-last-projection-start psi-emacs--state)
                      (marker-position start))
                (save-excursion
                  (delete-region start end))
                (psi-emacs--region-unregister 'projection 'main)
                (set-marker start nil)
                (set-marker end nil))

              ;; Reinsert at current end-of-buffer.
              ;; rendered already starts with "\n" so no extra newline is needed here.
              (unless (string-empty-p rendered)
                (save-excursion
                  (goto-char (point-max))
                  (let ((new-start (copy-marker (point) nil))
                        ;; Keep insertion-type nil on end marker so appends at the
                        ;; projection tail stay outside the projection range.
                        (new-end (copy-marker (point) nil)))
                    (insert rendered)
                    (let ((render-start (marker-position new-start))
                          (render-end (point)))
                      ;; Keep projection/footer content non-editable by user input.
                      ;; Leave boundary insertion at projection start writable so
                      ;; transcript/tool appends can insert immediately before block.
                      (when (and (fboundp 'psi-emacs--mark-region-read-only)
                                 (< (1+ render-start) render-end))
                        (psi-emacs--mark-region-read-only (1+ render-start) render-end))
                      ;; After insertion, make start marker advance when user inserts at
                      ;; draft/projection boundary so draft text stays outside range.
                      (set-marker-insertion-type new-start t)
                      (set-marker new-end render-end)
                      (psi-emacs--region-register 'projection 'main new-start new-end)
                      ;; Clear the snapshot now that the live region is registered.
                      (setf (psi-emacs-state-last-projection-start psi-emacs--state)
                            nil))))))

            (when follow-anchor
              (psi-emacs--set-draft-anchor-to-end))))
      (setq psi-emacs--upsert-projection-in-progress nil))))

(provide 'psi-projection)

;;; psi-projection.el ends here
