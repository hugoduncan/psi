;;; psi-lifecycle.el --- Transport + buffer lifecycle helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted state initialization, RPC transport callbacks, and reconnect flow.

;;; Code:

(require 'psi-rpc)
(require 'psi-globals)

(defvar psi-emacs-command)
(defvar psi-emacs--spawn-process-function)

(declare-function make-psi-emacs-state "psi" (&rest args))
(declare-function psi-emacs--dispatch-request "psi-compose" (op params &optional callback))
(declare-function psi-emacs--frame-messages-list "psi-session-commands" (frame))
(declare-function psi-emacs--replay-session-messages "psi-session-commands" (messages))
(declare-function psi-emacs--draft-end-position "psi-compose")
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--refresh-header-line "psi-run-state")
(declare-function psi-emacs--disarm-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--set-last-error "psi-run-state" (state message))
(declare-function psi-emacs--handle-rpc-event "psi-events" (frame))
(declare-function psi-emacs--clear-last-error "psi-run-state" (state))
(declare-function psi-emacs--clear-assistant-render-state "psi-assistant-render")
(declare-function psi-emacs--clear-thinking-render-state "psi-assistant-render" (&optional clear-archived))
(declare-function psi-emacs--clear-notification-lifecycle "psi-projection" (state))
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-unregister "psi-regions" (kind id))
(declare-function psi-emacs--ensure-input-area "psi-compose")

(defun psi-emacs--initialize-state (process)
  "Create initial frontend state for PROCESS ownership."
  (make-psi-emacs-state
   :process process
   :process-state (if (and process (process-live-p process)) 'running 'starting)
   :transport-state 'disconnected
   :run-state 'idle
   :last-stream-progress-at nil
   :stream-watchdog-timer nil
   :last-error nil
   :error-line-range nil
   :assistant-in-progress nil
   :assistant-range nil
   :thinking-in-progress nil
   :thinking-range nil
   :thinking-archived-ranges nil
   :tool-rows (make-hash-table :test #'equal)
   :projection-widgets nil
   :projection-widget-specs nil
   :projection-widget-lstates nil
   :projection-widget-data (make-hash-table :test #'equal)
   :projection-statuses nil
   :projection-footer nil
   :projection-notifications nil
   :projection-notification-seq 0
   :projection-notification-timers (make-hash-table :test #'equal)
   :regions (make-hash-table :test #'equal)
   :region-seq 0
   :active-assistant-id nil
   :active-thinking-id nil
   :input-separator-marker nil
   :draft-anchor nil
   :input-history nil
   :input-history-index nil
   :input-history-stash nil
   :rpc-client nil
   :tool-output-view-mode 'collapsed
   :session-id nil
   :session-phase nil
   :session-is-streaming nil
   :session-is-compacting nil
   :session-pending-message-count 0
   :session-retry-attempt 0
   :session-model-provider nil
   :session-model-id nil
   :session-model-reasoning nil
   :session-thinking-level nil
   :session-effective-reasoning-effort nil
   :header-model-label nil
   :status-session-line nil
   :extension-command-names nil
   :prompt-templates nil
   :slash-completion-token nil
   :transcript-hydrated? nil))

(defun psi-emacs--request-initial-transcript-hydration (buffer state)
  "Request and replay initial transcript once transport is ready."
  (unless (psi-emacs-state-transcript-hydrated? state)
    (setf (psi-emacs-state-transcript-hydrated? state) t)
    (psi-emacs--dispatch-request
     "get_messages"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let ((messages (psi-emacs--frame-messages-list frame)))
               (psi-emacs--replay-session-messages messages)
               ;; Keep point in compose input area after startup hydration so
               ;; users can type immediately even when replayed messages exist.
               (goto-char (psi-emacs--draft-end-position))))))))))

(defun psi-emacs--on-rpc-state-change (buffer client)
  "Apply CLIENT state changes to BUFFER-local frontend state."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when psi-emacs--state
        (let ((active-client (psi-emacs-state-rpc-client psi-emacs--state)))
          ;; Ignore late callbacks from stale clients after reconnect/restart.
          (when (or (null active-client)
                    (eq active-client client))
            (setf (psi-emacs-state-rpc-client psi-emacs--state) client)
            (setf (psi-emacs-state-process-state psi-emacs--state)
                  (psi-rpc-client-process-state client))
            (setf (psi-emacs-state-transport-state psi-emacs--state)
                  (psi-rpc-client-transport-state client))
            (setf (psi-emacs-state-process psi-emacs--state)
                  (psi-rpc-client-process client))
            (when (and (eq (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
                       (eq (psi-rpc-client-transport-state client) 'ready))
              (psi-emacs--set-run-state psi-emacs--state 'idle))
            (when (eq (psi-rpc-client-transport-state client) 'ready)
              (let ((hydrated-before (psi-emacs-state-transcript-hydrated? psi-emacs--state)))
                (psi-emacs--request-initial-transcript-hydration buffer psi-emacs--state)
                (when (and (not hydrated-before)
                           (fboundp 'psi-emacs--refresh-slash-completion-data))
                  (psi-emacs--refresh-slash-completion-data))))
            (setq psi-emacs--owned-process (psi-rpc-client-process client))
            (psi-emacs--refresh-header-line)))))))

(defun psi-emacs--on-rpc-error (buffer code message-text frame)
  "Surface RPC error in minibuffer only for BUFFER."
  (ignore frame)
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when psi-emacs--state
        (let ((summary (format "%s: %s" code message-text)))
          (psi-emacs--disarm-stream-watchdog psi-emacs--state)
          (psi-emacs--set-last-error psi-emacs--state summary)
          (psi-emacs--set-run-state psi-emacs--state 'error))))
    (message "psi rpc error [%s]: %s" code message-text)))

(defun psi-emacs--rpc-client-active-for-buffer-p (client)
  "Return non-nil when CLIENT is the current active rpc client for this buffer."
  (let ((active-client (and psi-emacs--state
                            (psi-emacs-state-rpc-client psi-emacs--state))))
    (or (null client)
        (null active-client)
        (eq active-client client))))

(defun psi-emacs--defer-rpc-event (buffer frame &optional client)
  "Handle prompt-opening rpc FRAME on the next idle turn for BUFFER.

CLIENT, when non-nil, must still be the active rpc client for BUFFER when the
idle callback runs. This keeps minibuffer/UI prompting out of the process
filter path and anchors prompting to the psi window/frame when one is visible."
  (let ((state psi-emacs--state))
    (run-with-idle-timer
     0 nil
     (lambda ()
       (when (buffer-live-p buffer)
         (let* ((window (get-buffer-window buffer t))
                (frame* (and (window-live-p window) (window-frame window))))
           (with-current-buffer buffer
             (when (and (eq state psi-emacs--state)
                        (psi-emacs--rpc-client-active-for-buffer-p client))
               (condition-case err
                   (cond
                    ((and (frame-live-p frame*) (window-live-p window))
                     (with-selected-frame frame*
                       (select-frame-set-input-focus frame*)
                       (with-selected-window window
                         (psi-emacs--handle-rpc-event frame))))
                    ((window-live-p window)
                     (with-selected-window window
                       (psi-emacs--handle-rpc-event frame)))
                    (t
                     (psi-emacs--handle-rpc-event frame)))
                 (error
                  (message "psi deferred rpc event failed [%s]: %s"
                           (alist-get :event frame nil nil #'equal)
                           (error-message-string err))))))))))))

(defun psi-emacs--on-rpc-event (buffer frame &optional client)
  "Handle rpc event FRAME for BUFFER, keeping errors out of transcript.

CLIENT, when non-nil, must still be the active rpc client for BUFFER."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when (psi-emacs--rpc-client-active-for-buffer-p client)
        (let ((event (alist-get :event frame nil nil #'equal))
              (data (alist-get :data frame nil nil #'equal)))
          (cond
           ((equal event "error")
            (psi-emacs--on-rpc-error
             buffer
             (or (alist-get :code data nil nil #'equal)
                 (alist-get :error-code data nil nil #'equal)
                 "rpc/error")
             (or (alist-get :message data nil nil #'equal)
                 (alist-get :error-message data nil nil #'equal)
                 "rpc error")
             frame))
           ((member event '("ui/dialog-requested" "ui/frontend-action-requested"))
            (psi-emacs--defer-rpc-event buffer frame client))
           (t
            (psi-emacs--handle-rpc-event frame))))))))

(defun psi-emacs--start-rpc-client (buffer)
  "Start rpc-edn client for BUFFER and wire callbacks."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (let (client)
        (setq client
              (psi-rpc-make-client
               :on-state-change (lambda (rpc-client)
                                  (psi-emacs--on-rpc-state-change buffer rpc-client))
               :on-event (lambda (frame)
                           (psi-emacs--on-rpc-event buffer frame client))
               :on-rpc-error (lambda (code message-text frame)
                               (psi-emacs--on-rpc-error buffer code message-text frame))))
        (setf (psi-emacs-state-rpc-client psi-emacs--state) client)
        (psi-rpc-start! client
                        psi-emacs--spawn-process-function
                        psi-emacs-command
                        psi-rpc-default-topics)
        (setf (psi-emacs-state-process psi-emacs--state) (psi-rpc-client-process client))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--next-rpc-process-name ()
  "Return a unique process name for a psi rpc subprocess."
  (let ((base "psi-rpc-edn")
        (index 1)
        candidate)
    (setq candidate base)
    (while (get-process candidate)
      (setq candidate (format "%s<%d>" base index)
            index (1+ index)))
    candidate))

(defun psi-emacs--default-spawn-process (command)
  "Spawn psi subprocess from COMMAND.

COMMAND is a list suitable for `make-process'."
  (let* ((stderr-buffer (generate-new-buffer " *psi-rpc-stderr*"))
         (process (make-process
                   :name (psi-emacs--next-rpc-process-name)
                   :command command
                   :buffer nil
                   :stderr stderr-buffer
                   :noquery t
                   :connection-type 'pipe)))
    (process-put process 'psi-rpc-stderr-buffer stderr-buffer)
    process))

(defun psi-emacs--default-send-request (state op params &optional callback)
  "Send OP with PARAMS using STATE rpc client, if available.

Returns non-nil when request dispatch was accepted by transport."
  (when-let ((client (psi-emacs-state-rpc-client state)))
    (let ((id (psi-rpc-send-request! client op params callback)))
      (and (stringp id)
           (not (string-empty-p id))))))

(defun psi-emacs--teardown-buffer ()
  "Stop owned subprocess and clear local/global frontend state for current buffer."
  ;; Remove window-configuration hook first so upsert-projection-block cannot
  ;; fire against a partially-torn-down buffer (markers already cleared).
  (remove-hook 'window-configuration-change-hook
               #'psi-emacs--handle-window-configuration-change t)
  (when (and psi-emacs--state
             (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
    (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
  (psi-emacs--disarm-stream-watchdog psi-emacs--state)
  (psi-emacs--clear-last-error psi-emacs--state)
  (when (process-live-p psi-emacs--owned-process)
    (process-put psi-emacs--owned-process 'psi-rpc-stop-requested t)
    (delete-process psi-emacs--owned-process))
  (when (and psi-emacs--state
             (fboundp 'psi-emacs--regions-clear))
    (psi-emacs--regions-clear))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
    (set-marker (psi-emacs-state-draft-anchor psi-emacs--state) nil))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-input-separator-marker psi-emacs--state)))
    (set-marker (psi-emacs-state-input-separator-marker psi-emacs--state) nil))
  (when psi-emacs--state
    (psi-emacs--clear-assistant-render-state)
    (psi-emacs--clear-thinking-render-state t))
  (psi-emacs--clear-notification-lifecycle psi-emacs--state)
  (when psi-emacs--state
    (maphash (lambda (_ row)
               (when (markerp (plist-get row :start))
                 (set-marker (plist-get row :start) nil))
               (when (markerp (plist-get row :end))
                 (set-marker (plist-get row :end) nil)))
             (psi-emacs-state-tool-rows psi-emacs--state)))
  (remhash (current-buffer) psi-emacs--state-by-buffer)
  (setq-local header-line-format nil)
  (setq psi-emacs--owned-process nil)
  (setq psi-emacs--state nil))

(defun psi-emacs--handle-window-configuration-change ()
  "Refresh width-sensitive separators after window configuration changes."
  (when psi-emacs--state
    ;; Keep input separator healthy + width-aligned even if marker drifted.
    (when (fboundp 'psi-emacs--ensure-input-area)
      (save-excursion
        (psi-emacs--ensure-input-area)))
    ;; Re-render projection block when present so footer/projection separator
    ;; widths track the current visible window after resize/split changes.
    (when (and (fboundp 'psi-emacs--upsert-projection-block)
               (psi-emacs--region-bounds 'projection 'main))
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--live-owned-process ()
  "Return live psi process for current buffer, or nil."
  (let ((process (or psi-emacs--owned-process
                     (and psi-emacs--state
                          (psi-emacs-state-process psi-emacs--state)))))
    (and (processp process)
         (process-live-p process)
         process)))

(defun psi-emacs--confirm-kill-buffer-p ()
  "Return non-nil when killing the current psi buffer may proceed.

In interactive sessions, prompt before killing a buffer that owns a live psi
process. In batch/noninteractive usage (for example ERT), always allow kill."
  (if noninteractive
      t
    (if-let ((process (psi-emacs--live-owned-process)))
        (yes-or-no-p
         (format "psi has a running process (%s). Kill buffer and stop it? "
                 (process-name process)))
      t)))

(defun psi-emacs--install-buffer-lifecycle-hooks ()
  "Install local lifecycle hooks for dedicated psi buffer."
  (add-hook 'kill-buffer-query-functions #'psi-emacs--confirm-kill-buffer-p nil t)
  (add-hook 'kill-buffer-hook #'psi-emacs--teardown-buffer nil t)
  (add-hook 'window-configuration-change-hook
            #'psi-emacs--handle-window-configuration-change
            nil t))

(defun psi-emacs--refresh-buffer-lifecycle-hooks ()
  "Ensure lifecycle hooks are installed in all live psi mode buffers.

Useful after reloading lifecycle code into an existing Emacs session where
buffers were created before new hook registrations existed."
  (dolist (buffer (buffer-list))
    (when (buffer-live-p buffer)
      (with-current-buffer buffer
        (when (derived-mode-p 'psi-emacs-mode)
          (psi-emacs--install-buffer-lifecycle-hooks))))))

(defun psi-emacs--buffer-modified-p ()
  "Return non-nil when current buffer has pending user edits."
  (buffer-modified-p))

(defun psi-emacs--confirm-reconnect-p ()
  "Return non-nil when reconnect should proceed."
  (or (not (psi-emacs--buffer-modified-p))
      (yes-or-no-p "Buffer has unsaved edits. Reconnect and clear buffer? ")))

(defun psi-emacs--reset-transcript-state (&optional preserve-tool-output-view-mode)
  "Clear transcript buffer and reset in-buffer rendering state.

When PRESERVE-TOOL-OUTPUT-VIEW-MODE is non-nil, keep the current
`tool-output-view-mode' (used by /new). Otherwise reset to default
`collapsed' (used by reconnect)."
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when (fboundp 'psi-emacs--ensure-startup-banner)
    (psi-emacs--ensure-startup-banner))
  (when psi-emacs--state
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--clear-last-error psi-emacs--state)
    (psi-emacs--clear-assistant-render-state)
    (psi-emacs--clear-thinking-render-state t)
    (when (fboundp 'psi-emacs--regions-clear)
      (psi-emacs--regions-clear))
    (clrhash (psi-emacs-state-tool-rows psi-emacs--state))
    (setf (psi-emacs-state-projection-widgets psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-statuses psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-footer psi-emacs--state) nil)
    (psi-emacs--clear-notification-lifecycle psi-emacs--state)
    (when-let ((projection-id (and psi-emacs--state
                                   (psi-emacs--region-bounds 'projection 'main)
                                   'main)))
      (psi-emacs--region-unregister 'projection projection-id))
    (when (markerp (psi-emacs-state-input-separator-marker psi-emacs--state))
      (set-marker (psi-emacs-state-input-separator-marker psi-emacs--state) nil))
    (setf (psi-emacs-state-input-separator-marker psi-emacs--state) nil)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (setf (psi-emacs-state-input-history-index psi-emacs--state) nil)
    (setf (psi-emacs-state-input-history-stash psi-emacs--state) nil)
    (unless preserve-tool-output-view-mode
      (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'collapsed))
    (setf (psi-emacs-state-session-id psi-emacs--state) nil)
    (setf (psi-emacs-state-session-phase psi-emacs--state) nil)
    (setf (psi-emacs-state-session-is-streaming psi-emacs--state) nil)
    (setf (psi-emacs-state-session-is-compacting psi-emacs--state) nil)
    (setf (psi-emacs-state-session-pending-message-count psi-emacs--state) 0)
    (setf (psi-emacs-state-session-retry-attempt psi-emacs--state) 0)
    (setf (psi-emacs-state-session-model-provider psi-emacs--state) nil)
    (setf (psi-emacs-state-session-model-id psi-emacs--state) nil)
    (setf (psi-emacs-state-session-model-reasoning psi-emacs--state) nil)
    (setf (psi-emacs-state-session-thinking-level psi-emacs--state) nil)
    (setf (psi-emacs-state-session-effective-reasoning-effort psi-emacs--state) nil)
    (setf (psi-emacs-state-header-model-label psi-emacs--state) nil)
    (setf (psi-emacs-state-status-session-line psi-emacs--state) nil)
    (setf (psi-emacs-state-extension-command-names psi-emacs--state) nil)
    (setf (psi-emacs-state-transcript-hydrated? psi-emacs--state) nil)
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (psi-emacs--set-run-state psi-emacs--state 'idle)
    (psi-emacs--refresh-header-line))
  (set-buffer-modified-p nil))

(defun psi-emacs-reconnect ()
  "Manually reconnect by clearing transcript and starting a fresh rpc session."
  (interactive)
  (if (not psi-emacs--state)
      (user-error "psi buffer is not initialized")
    (when (psi-emacs--confirm-reconnect-p)
      (when (and (psi-emacs-state-rpc-client psi-emacs--state)
                 (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
        (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
      (when (process-live-p psi-emacs--owned-process)
        (process-put psi-emacs--owned-process 'psi-rpc-stop-requested t)
        (delete-process psi-emacs--owned-process))
      (psi-emacs--reset-transcript-state)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'starting)
      (psi-emacs--set-run-state psi-emacs--state 'reconnecting)
      (psi-emacs--refresh-header-line)
      (psi-emacs--start-rpc-client (current-buffer)))))

(provide 'psi-lifecycle)

;;; psi-lifecycle.el ends here
