;;; psi-events.el --- RPC event decoding/dispatch for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted event payload helpers + inbound event routing used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)
(require 'psi-widget-projection)

(declare-function psi-emacs--projection-footer-text "psi-projection" (data))
(declare-function psi-emacs--handle-notification-event "psi-projection" (data))
(declare-function psi-emacs--handle-dialog-requested "psi-dialogs" (data))
(declare-function psi-emacs--upsert-tool-row "psi-tool-rows" (tool-id stage text &optional tool-name arguments parsed-args is-error details))
(declare-function psi-emacs--assistant-before-tool-event "psi-assistant-render")
(declare-function psi-emacs--replay-session-messages "psi-session-commands" (messages))
(declare-function psi-emacs--reset-transcript-state "psi-lifecycle" (&optional preserve-tool-output-view-mode))
(declare-function psi-emacs--assistant-thinking-delta "psi-assistant-render" (text))
(declare-function psi-emacs--disarm-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--archive-thinking-line "psi-assistant-render")
(declare-function psi-emacs--assistant-finalize "psi-assistant-render" (text))
(declare-function psi-emacs--assistant-content->text "psi-assistant-render" (content))
(declare-function psi-emacs--assistant-delta "psi-assistant-render" (text))
(declare-function psi-emacs--dispatch-request "psi-compose" (op params &optional callback))
(declare-function psi-emacs--append-assistant-message "psi-compose" (text))
(declare-function psi-emacs--apply-slash-completion-data "psi-session-commands" (names templates))
(declare-function psi-emacs--refresh-slash-completion-data "psi-session-commands")
(declare-function psi-emacs--request-switch-session-by-id "psi-session-commands" (state session-id))
(declare-function psi-emacs--request-frontend-exit "psi-session-commands")
(declare-function psi-emacs--upsert-projection-block "psi-projection")
(declare-function psi-emacs--projection-seq "psi-projection" (value))
(declare-function psi-emacs--refresh-header-line "psi-run-state")
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--reset-stream-watchdog "psi-run-state" (state))

(defun psi-emacs--event-data-get (data keys)
  "Return first non-nil value from DATA for KEYS.

DATA is expected to be an alist map."
  (catch 'hit
    (dolist (k keys)
      (let ((v (alist-get k data nil nil #'equal)))
        (when v
          (throw 'hit v))))
    nil))

(defun psi-emacs--session-normalize-text (value)
  "Normalize VALUE to a compact text representation, or nil when blank."
  (let ((text (cond
               ((null value) nil)
               ((stringp value) value)
               ((keywordp value) (string-remove-prefix ":" (symbol-name value)))
               ((symbolp value) (symbol-name value))
               (t (format "%s" value)))))
    (when (and (stringp text)
               (not (string-empty-p (string-trim text))))
      (string-trim text))))

(defun psi-emacs--non-blank-text (value)
  "Return VALUE when it is a non-blank string; otherwise nil."
  (when (and (stringp value)
             (not (string-empty-p (string-trim value))))
    value))

(defun psi-emacs--event-session-matches-current-p (data)
  "Return non-nil when event DATA targets the current frontend session.

If frontend state has no known session id yet, allow the event so bootstrap
payloads can seed state before the first canonical session-targeted update."
  (let ((event-session-id (psi-emacs--session-normalize-text
                           (psi-emacs--event-data-get data '(:session-id session-id))))
        (current-session-id (and psi-emacs--state
                                 (psi-emacs--session-normalize-text
                                  (psi-emacs-state-session-id psi-emacs--state)))))
    (or (null current-session-id)
        (equal event-session-id current-session-id))))

(defun psi-emacs--slash-completion-data-changed-p (data)
  "Return non-nil when session update DATA carries changed slash completion state."
  (when psi-emacs--state
    (let* ((raw-command-names (psi-emacs--event-data-get data '(:extension-command-names extension-command-names)))
           (raw-templates (psi-emacs--event-data-get data '(:prompt-templates prompt-templates)))
           (has-command-names (not (null raw-command-names)))
           (has-templates (not (null raw-templates)))
           (command-names (cond
                           ((vectorp raw-command-names) (append raw-command-names nil))
                           ((listp raw-command-names) raw-command-names)
                           (t nil)))
           (templates (cond
                       ((vectorp raw-templates) (append raw-templates nil))
                       ((listp raw-templates) raw-templates)
                       (t nil)))
           (next-token (and (or has-command-names has-templates)
                            (list :commands (mapcar (lambda (name)
                                                      (string-trim (format "%s" (or name ""))))
                                                    (or command-names []))
                                  :templates (mapcar (lambda (tpl)
                                                       (list (psi-emacs--non-blank-text
                                                              (psi-emacs--event-data-get tpl '(:name name)))
                                                             (psi-emacs--non-blank-text
                                                              (psi-emacs--event-data-get tpl '(:description description)))))
                                                     (or templates [])))))
           (current-token (psi-emacs-state-slash-completion-token psi-emacs--state)))
      (when next-token
        (unless (equal current-token next-token)
          (when (fboundp 'psi-emacs--apply-slash-completion-data)
            (psi-emacs--apply-slash-completion-data command-names templates))
          t)))))

(defun psi-emacs--handle-session-updated-event (data)
  "Project `session/updated` DATA into frontend session/header state."
  (when (and psi-emacs--state
             (psi-emacs--event-session-matches-current-p data))
    (psi-emacs--slash-completion-data-changed-p data)
    (let* ((session-id (psi-emacs--session-normalize-text
                        (psi-emacs--event-data-get data '(:session-id session-id))))
           (phase (psi-emacs--session-normalize-text
                   (psi-emacs--event-data-get data '(:phase phase))))
           (is-streaming (not (null (psi-emacs--event-data-get data
                                                                '(:is-streaming is-streaming)))))
           (is-compacting (not (null (psi-emacs--event-data-get data
                                                                 '(:is-compacting is-compacting)))))
           (pending (or (psi-emacs--event-data-get data
                                                   '(:pending-message-count pending-message-count))
                        0))
           (retry (or (psi-emacs--event-data-get data
                                                 '(:retry-attempt retry-attempt))
                      0))
           (interrupt-pending (not (null (psi-emacs--event-data-get data
                                                                     '(:interrupt-pending interrupt-pending)))))
           (model-provider (psi-emacs--session-normalize-text
                            (psi-emacs--event-data-get data
                                                       '(:model-provider model-provider))))
           (model-id (psi-emacs--session-normalize-text
                      (psi-emacs--event-data-get data
                                                 '(:model-id model-id))))
           (model-reasoning (not (null (psi-emacs--event-data-get data
                                                                   '(:model-reasoning model-reasoning)))))
           (thinking-level (psi-emacs--session-normalize-text
                            (psi-emacs--event-data-get data
                                                       '(:thinking-level thinking-level))))
           (effective-reasoning-effort (psi-emacs--session-normalize-text
                                        (psi-emacs--event-data-get data
                                                                   '(:effective-reasoning-effort effective-reasoning-effort))))
           (header-model-label (psi-emacs--session-normalize-text
                                (psi-emacs--event-data-get data
                                                           '(:header-model-label header-model-label)))))
      (setf (psi-emacs-state-session-id psi-emacs--state) session-id)
      (setf (psi-emacs-state-session-phase psi-emacs--state) phase)
      (setf (psi-emacs-state-session-is-streaming psi-emacs--state) is-streaming)
      (setf (psi-emacs-state-session-is-compacting psi-emacs--state) is-compacting)
      (setf (psi-emacs-state-session-pending-message-count psi-emacs--state) pending)
      (setf (psi-emacs-state-session-retry-attempt psi-emacs--state) retry)
      (setf (psi-emacs-state-session-interrupt-pending psi-emacs--state) interrupt-pending)
      (setf (psi-emacs-state-session-model-provider psi-emacs--state) model-provider)
      (setf (psi-emacs-state-session-model-id psi-emacs--state) model-id)
      (setf (psi-emacs-state-session-model-reasoning psi-emacs--state) model-reasoning)
      (setf (psi-emacs-state-session-thinking-level psi-emacs--state) thinking-level)
      (setf (psi-emacs-state-session-effective-reasoning-effort psi-emacs--state)
            effective-reasoning-effort)
      (setf (psi-emacs-state-header-model-label psi-emacs--state) header-model-label)
      (setf (psi-emacs-state-status-session-line psi-emacs--state)
            (psi-emacs--session-normalize-text
             (psi-emacs--event-data-get data
                                        '(:status-session-line status-session-line))))
      (unless (memq (psi-emacs-state-run-state psi-emacs--state) '(error reconnecting))
        (psi-emacs--set-run-state
         psi-emacs--state
         (cond
          ((and is-streaming interrupt-pending) 'interrupt_pending)
          (is-streaming                         'streaming)
          (t                                    'idle))))
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--session-short-id (slot)
  "Return compact 8-char session id prefix for SLOT, or empty string."
  (let ((id (or (psi-emacs--event-data-get slot '(:id id
                                                  :session-id session-id))
                "")))
    (substring id 0 (min 8 (length id)))))

(defun psi-emacs--session-explicit-display-name (slot)
  "Return explicit display/name for SLOT, or nil when absent."
  (let ((name (psi-emacs--event-data-get slot '(:display-name display-name
                                                :session-display-name session-display-name
                                                :name name
                                                :session-name session-name))))
    (when (and (stringp name) (not (string-empty-p (string-trim name))))
      (string-trim name))))

(defun psi-emacs--session-display-name (slot)
  "Return display name for session SLOT map.

Supports both event slots (`:id`/`:name`) and backend command payloads
(`:session-id`/`:session-name`). Prefers explicit display/name fields, else
falls back to `(session <8-char id prefix>)`."
  (or (psi-emacs--session-explicit-display-name slot)
      (format "(session %s)" (psi-emacs--session-short-id slot))))

(defun psi-emacs--session-tree-line-label (slot)
  "Return the backend-provided canonical label for a session tree SLOT."
  (psi-emacs--event-data-get slot '(:label label)))

(defun psi-emacs--session-tree-widget-p (widget)
  "Return non-nil when WIDGET is the backend-owned session tree widget."
  (and (equal (psi-emacs--event-data-get widget '(:extension-id extension-id))
              "psi-session")
       (equal (psi-emacs--event-data-get widget '(:widget-id widget-id))
              "session-tree")))

(defun psi-emacs--projection-widgets-with-session-tree (widgets session-tree-widget)
  "Return WIDGETS with SESSION-TREE-WIDGET prepended and deduplicated.

This preserves the backend-owned session tree across unrelated widget refreshes
while keeping `context/updated` authoritative for whether the tree is present."
  (let ((others (seq-remove #'psi-emacs--session-tree-widget-p (or widgets []))))
    (if session-tree-widget
        (cons session-tree-widget others)
      others)))

(defun psi-emacs--handle-context-updated-event (data)
  "Handle `context/updated` DATA: store snapshot and refresh session tree widget."
  (when psi-emacs--state
    (let* ((active-id (psi-emacs--event-data-get data '(:active-session-id active-session-id)))
           (slots     (append (psi-emacs--event-data-get data '(:sessions sessions)) nil))
           (snapshot  `((:active-session-id . ,active-id) (:sessions . ,slots)))
           (widget    (psi-emacs--event-data-get data '(:session-tree-widget session-tree-widget)))
           (content-lines (psi-emacs--projection-seq
                           (and (listp widget)
                                (psi-emacs--event-data-get widget '(:content-lines content-lines)))))
           (session-tree-widget (and (listp widget)
                                     (> (length slots) 1)
                                     content-lines
                                     widget))
           (updated   (psi-emacs--projection-widgets-with-session-tree
                       (psi-emacs-state-projection-widgets psi-emacs--state)
                       session-tree-widget)))
      (setf (psi-emacs-state-context-snapshot psi-emacs--state) snapshot)
      (setf (psi-emacs-state-projection-widgets psi-emacs--state) updated)
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--handle-command-result-event (data)
  "Handle backend `command-result` event DATA."
  (let ((type (psi-emacs--event-data-get data '(:type type))))
    (pcase type
      ("quit"
       (psi-emacs--request-frontend-exit))
      ("session_switch"
       (let ((sid (psi-emacs--event-data-get data '(:session-id session-id))))
         (when (and (stringp sid) (not (string-empty-p sid)))
           (psi-emacs--request-switch-session-by-id psi-emacs--state sid))))
      (_
       (let ((message (psi-emacs--event-data-get data '(:message message))))
         (when (and (stringp message) (not (string-empty-p message)))
           (psi-emacs--append-assistant-message message))))))
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'idle)))

(defun psi-emacs--ordered-completing-read (prompt candidates &optional default)
  "Return selection from CANDIDATES while preserving their given order.

This disables completion UI sort hooks for ordered backend-owned lists such as
`/tree`, where alphabetical resorting destroys structural meaning."
  (let ((completion-extra-properties
         (append completion-extra-properties
                 '(:display-sort-function identity
                   :cycle-sort-function identity))))
    (completing-read prompt candidates nil t nil nil default)))

(defun psi-emacs--frontend-action-key (ui-action action-name)
  "Return canonical frontend action key from UI-ACTION or ACTION-NAME."
  (let ((key (or (psi-emacs--event-data-get ui-action '(:ui/action-name ui/action-name))
                 action-name)))
    (cond
     ((equal key "select-resume-session") :select-resume-session)
     ((equal key "select-session") :select-session)
     ((equal key "select-model") :select-model)
     ((equal key "select-thinking-level") :select-thinking-level)
     (t key))))

(defun psi-emacs--frontend-action-ui-candidates (ui-action)
  "Return completion candidates from canonical UI-ACTION items, or nil."
  (let ((items (append (or (psi-emacs--event-data-get ui-action '(:ui/items ui/items)) []) nil)))
    (when items
      (mapcar (lambda (item)
                (let ((label (psi-emacs--event-data-get item '(:ui.item/label ui.item/label)))
                      (value (psi-emacs--event-data-get item '(:ui.item/value ui.item/value))))
                  (cons label value)))
              items))))

(defun psi-emacs--frontend-action-map-candidates (_action-key _payload ui-action)
  "Return completing-read alist candidates from canonical UI-ACTION items."
  (psi-emacs--frontend-action-ui-candidates ui-action))

(defun psi-emacs--handle-frontend-action-requested (data)
  "Handle backend `ui/frontend-action-requested` event DATA."
  (when psi-emacs--state
    (setf (psi-emacs-state-pending-frontend-action psi-emacs--state) data)
    (let* ((ui-action (or (psi-emacs--event-data-get data '(:ui/action ui/action)) '()))
           (action-key (psi-emacs--frontend-action-key ui-action nil))
           (submitted-action-name (cond
                                   ((keywordp action-key) (substring (symbol-name action-key) 1))
                                   ((symbolp action-key) (symbol-name action-key))
                                   ((stringp action-key) action-key)
                                   (t nil)))
           (request-id (psi-emacs--event-data-get data '(:request-id request-id)))
           (prompt (or (psi-emacs--event-data-get ui-action '(:ui/prompt ui/prompt))
                       (psi-emacs--event-data-get data '(:prompt prompt))
                       "Select:"))
           (payload '())
           (candidates (psi-emacs--frontend-action-map-candidates action-key payload ui-action))
           (selected (condition-case nil
                         (when candidates
                           (let ((label (psi-emacs--ordered-completing-read
                                         (concat prompt " ")
                                         candidates)))
                             (cdr (assoc label candidates))))
                       (quit :cancelled))))
      (cond
       ((eq selected :cancelled)
        (psi-emacs--dispatch-request
         "frontend_action_result"
         `((:request-id . ,request-id)
           (:action-name . ,submitted-action-name)
           (:ui/action . ,ui-action)
           (:status . "cancelled")
           (:value . nil))))
       (selected
        (psi-emacs--dispatch-request
         "frontend_action_result"
         `((:request-id . ,request-id)
           (:action-name . ,submitted-action-name)
           (:ui/action . ,ui-action)
           (:status . "submitted")
           (:value . ,selected))))))
    (setf (psi-emacs-state-pending-frontend-action psi-emacs--state) nil)))

(defun psi-emacs--handle-rpc-event (frame)
  "Handle inbound rpc-edn event FRAME for transcript rendering."
  (let ((inhibit-read-only t))
    (let* ((event (alist-get :event frame nil nil #'equal))
           (data (or (alist-get :data frame nil nil #'equal) '())))
      (pcase event
      ("assistant/delta"
       (when (psi-emacs--event-session-matches-current-p data)
         (psi-emacs--assistant-delta
          (or (psi-emacs--event-data-get data '(:text text :delta delta)) ""))))
      ("assistant/message"
       (when (psi-emacs--event-session-matches-current-p data)
         (let* ((explicit-text (psi-emacs--non-blank-text
                                (psi-emacs--event-data-get data '(:text text :message message))))
                (content-text  (psi-emacs--non-blank-text
                                (psi-emacs--assistant-content->text
                                 (psi-emacs--event-data-get data '(:content content)))))
                (error-text    (psi-emacs--non-blank-text
                                (psi-emacs--event-data-get data
                                                           '(:error-message error-message
                                                             :error_message error_message))))
                (final-text    (or explicit-text
                                   content-text
                                   (and error-text (format "[error] %s" error-text))))
                (has-streamed-text (and psi-emacs--state
                                        (psi-emacs--non-blank-text
                                         (psi-emacs-state-assistant-in-progress psi-emacs--state)))))
           (if (or final-text has-streamed-text)
               (psi-emacs--assistant-finalize final-text)
             ;; Empty assistant/message payloads (tool-only or provider-edge events)
             ;; should not render a blank `ψ:` line.
             ;; Archive (not clear) any live thinking block — it is part of the
             ;; transcript and must remain visible even when no reply text follows.
             (when psi-emacs--state
               (psi-emacs--archive-thinking-line)
               (psi-emacs--disarm-stream-watchdog psi-emacs--state)
               (psi-emacs--set-run-state psi-emacs--state 'idle))))))
      ("assistant/thinking-delta"
       (when (psi-emacs--event-session-matches-current-p data)
         (psi-emacs--assistant-thinking-delta
          (or (psi-emacs--event-data-get data '(:text text :delta delta)) ""))))
      ("session/resumed"
       ;; Session transitions (/new, /tree, /resume) emit resumed/rehydrated as the
       ;; canonical clear+rebuild lifecycle. Clear stale transcript immediately so
       ;; previous session content disappears before replayed messages arrive.
       (when psi-emacs--state
         (let* ((resumed-session-id
                 (psi-emacs--session-normalize-text
                  (psi-emacs--event-data-get data '(:session-id session-id))))
                (saved-footer (psi-emacs-state-projection-footer psi-emacs--state))
                (preserve-tool-view (not (eq (psi-emacs-state-tool-output-view-mode psi-emacs--state)
                                             'collapsed)))
                (inhibit-read-only t))
           (psi-emacs--reset-transcript-state preserve-tool-view)
           ;; Keep selected-session identity pinned immediately after transcript reset.
           ;; Without this, cross-session stream events can leak through while
           ;; `session-id` is nil between resumed → updated/rehydrated frames.
           (when resumed-session-id
             (setf (psi-emacs-state-session-id psi-emacs--state) resumed-session-id))
           (when saved-footer
             (setf (psi-emacs-state-projection-footer psi-emacs--state) saved-footer)
             (psi-emacs--upsert-projection-block))
           (when (fboundp 'psi-emacs--focus-input-area)
             (psi-emacs--focus-input-area (current-buffer))))))
      ("session/rehydrated"
       (when (and psi-emacs--state
                  (psi-emacs--event-session-matches-current-p data))
         (let ((messages (or (psi-emacs--event-data-get data '(:messages messages)) '()))
               (inhibit-read-only t))
           (psi-emacs--replay-session-messages
            (cond
             ((vectorp messages) (append messages nil))
             ((listp messages) messages)
             (t nil))))
         (when (fboundp 'psi-emacs--focus-input-area)
           (psi-emacs--focus-input-area (current-buffer)))))
      ("session/updated"
       (psi-emacs--handle-session-updated-event data))
      ("context/updated"
       (psi-emacs--handle-context-updated-event data))
      ((or "tool/start" "tool/executing" "tool/update" "tool/result")
       (when (psi-emacs--event-session-matches-current-p data)
         (psi-emacs--assistant-before-tool-event)
         (let* ((tool-id (psi-emacs--event-data-get data
                                                    '(:tool-id tool-id :tool-call-id tool-call-id :id id)))
                (tool-name (psi-emacs--event-data-get data
                                                      '(:tool-name tool-name :name name)))
                (arguments (psi-emacs--event-data-get data '(:arguments arguments)))
                (parsed-args (psi-emacs--event-data-get data '(:parsed-args parsed-args)))
                (is-error (psi-emacs--event-data-get data '(:is-error is-error)))
                (details (psi-emacs--event-data-get data '(:details details)))
                (stage (replace-regexp-in-string "^tool/" "" event))
                (raw-text (or (psi-emacs--event-data-get data
                                                         '(:result-text result-text :text text :output output :delta delta :message message))
                              ""))
                (body-text raw-text))
           (psi-emacs--reset-stream-watchdog psi-emacs--state)
           (psi-emacs--upsert-tool-row tool-id stage body-text tool-name arguments parsed-args is-error details))))
      ("command-result"
       (psi-emacs--handle-command-result-event data))
      ("ui/dialog-requested"
       (psi-emacs--handle-dialog-requested data))
      ("ui/frontend-action-requested"
       (psi-emacs--handle-frontend-action-requested data))
      ("ui/widgets-updated"
       (when psi-emacs--state
         (let* ((widgets (psi-emacs--projection-seq
                          (or (psi-emacs--event-data-get data '(:widgets widgets))
                              (psi-emacs--event-data-get data '(:items items)))))
                (session-tree-widget (seq-find #'psi-emacs--session-tree-widget-p
                                               (psi-emacs-state-projection-widgets psi-emacs--state))))
           (setf (psi-emacs-state-projection-widgets psi-emacs--state)
                 (psi-emacs--projection-widgets-with-session-tree widgets session-tree-widget))
           (psi-emacs--upsert-projection-block))))
      ("ui/widget-specs-updated"
       (when psi-emacs--state
         (psi-widget-projection-request-specs)))
      ("ui/status-updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-statuses psi-emacs--state)
               (psi-emacs--projection-seq
                (or (psi-emacs--event-data-get data '(:statuses statuses))
                    (psi-emacs--event-data-get data '(:items items)))))
         (psi-emacs--upsert-projection-block)))
      ("ui/notification"
       (psi-emacs--handle-notification-event data))
      ("footer/updated"
       (when (and psi-emacs--state
                  (psi-emacs--event-session-matches-current-p data))
         (setf (psi-emacs-state-projection-footer psi-emacs--state)
               (psi-emacs--projection-footer-text data))
         (psi-emacs--upsert-projection-block)))
      (_ nil))
      ;; Dispatch every event to widget subscription handler
      (psi-widget-projection-handle-event event data))))

(provide 'psi-events)

;;; psi-events.el ends here
