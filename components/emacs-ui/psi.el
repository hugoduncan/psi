;;; psi.el --- Emacs process-buffer MVP scaffold  -*- lexical-binding: t; -*-

;; Copyright (C)

;;; Commentary:
;; MVP scaffold for a dedicated psi chat buffer with one owned subprocess.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'ansi-color)
(require 'psi-rpc)

(defgroup psi-emacs nil
  "psi Emacs frontend."
  :group 'applications)

(defcustom psi-emacs-command '("clojure" "-M:run" "--rpc-edn")
  "Command used to start the owned psi rpc-edn subprocess."
  :type '(repeat string)
  :group 'psi-emacs)

(defcustom psi-emacs-buffer-name "*psi*"
  "Default dedicated buffer name for psi Emacs frontend."
  :type 'string
  :group 'psi-emacs)

(defcustom psi-emacs-stream-timeout-seconds 45
  "Seconds without streaming progress before watchdog timeout triggers.

Used to detect stalled streaming runs and transition to deterministic recovery."
  :type 'number
  :group 'psi-emacs)

(defcustom psi-emacs-enable-resume-parity t
  "Enable parity-mode `/resume` routing in Emacs frontend.

When non-nil (default), `/resume` routes to parity entry points.
When nil, `/resume` uses the MVP fallback message."
  :type 'boolean
  :group 'psi-emacs)

(defcustom psi-emacs-enable-extension-ui-parity nil
  "Enable extension UI parity topic subscription in Emacs frontend.

When nil (default), subscribe to `psi-rpc-mvp-topics` only.
When non-nil, subscribe to `psi-rpc-parity-topics`."
  :type 'boolean
  :group 'psi-emacs)

(defcustom psi-emacs-notification-timeout-seconds 5
  "Seconds before a projected extension notification auto-dismisses."
  :type 'number
  :group 'psi-emacs)

(cl-defstruct psi-emacs-state
  process
  process-state
  transport-state
  run-state
  last-stream-progress-at
  stream-watchdog-timer
  last-error
  error-line-range
  assistant-in-progress
  assistant-range
  tool-rows
  projection-widgets
  projection-statuses
  projection-footer
  projection-notifications
  projection-notification-seq
  projection-notification-timers
  projection-range
  draft-anchor
  rpc-client
  tool-output-view-mode)

(defvar psi-emacs--spawn-process-function #'psi-emacs--default-spawn-process
  "Function used to spawn a psi subprocess.

The function receives one argument COMMAND (list of strings) and must return
an Emacs process object.")

(defvar psi-emacs--send-request-function #'psi-emacs--default-send-request
  "Function used by frontend commands to send RPC requests.

Called as (STATE OP PARAMS CALLBACK).")

(defvar psi-emacs--idle-slash-command-handler-function #'psi-emacs--default-handle-idle-slash-command
  "Function used to handle idle slash command input.

Called as (STATE MESSAGE). Return non-nil when MESSAGE was handled and should
not fall through to normal prompt dispatch.")

(defvar psi-emacs--state-by-buffer (make-hash-table :test #'eq)
  "Map dedicated buffers to their `psi-emacs-state'.")

(defvar-local psi-emacs--state nil
  "Buffer-local frontend state for psi chat buffers.")

(defvar-local psi-emacs--owned-process nil
  "Buffer-local owned subprocess for this dedicated psi buffer.")

(defun psi-emacs--markdown-mode-available-p ()
  "Return non-nil when `markdown-mode' is available."
  (fboundp 'markdown-mode))

(defun psi-emacs--preferred-major-mode ()
  "Return major mode symbol for dedicated psi buffer.

Prefers `markdown-mode' when available, otherwise `text-mode'."
  (if (psi-emacs--markdown-mode-available-p)
      'markdown-mode
    'text-mode))

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
   :tool-rows (make-hash-table :test #'equal)
   :projection-widgets nil
   :projection-statuses nil
   :projection-footer nil
   :projection-notifications nil
   :projection-notification-seq 0
   :projection-notification-timers (make-hash-table :test #'equal)
   :projection-range nil
   :draft-anchor nil
   :rpc-client nil
   :tool-output-view-mode 'collapsed))

(defun psi-emacs--status-string (state)
  "Return minimal status string for STATE."
  (format "psi [%s/%s/%s] tools:%s"
          (or (psi-emacs-state-transport-state state) 'unknown)
          (or (psi-emacs-state-process-state state) 'unknown)
          (or (psi-emacs-state-run-state state) 'idle)
          (or (psi-emacs-state-tool-output-view-mode state) 'collapsed)))

(defun psi-emacs--status-diagnostics-string (state)
  "Return status diagnostics string for STATE, including last-error summary."
  (let ((base (psi-emacs--status-string state))
        (last-error (psi-emacs-state-last-error state)))
    (if (and (stringp last-error)
             (not (string-empty-p last-error)))
      (format "%s\nlast-error: %s" base last-error)
      base)))

(defun psi-emacs--set-run-state (state run-state)
  "Set RUN-STATE on STATE and refresh header for the current psi buffer."
  (when state
    (setf (psi-emacs-state-run-state state) run-state)
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--error-line-text (message)
  "Render deterministic persistent error line for MESSAGE."
  (format "Error: %s\n" (or message "unknown")))

(defun psi-emacs--clear-error-line (state)
  "Remove persistent error line from current buffer for STATE."
  (when state
    (let ((range (psi-emacs-state-error-line-range state)))
      (when (and (consp range)
                 (markerp (car range))
                 (markerp (cdr range))
                 (marker-buffer (car range))
                 (marker-buffer (cdr range)))
        (save-excursion
          (delete-region (car range) (cdr range))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil)))
    (setf (psi-emacs-state-error-line-range state) nil)))

(defun psi-emacs--upsert-error-line (state message)
  "Insert or replace persistent error line for STATE with MESSAGE."
  (when state
    (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs-state-error-line-range state))
          (line-text (psi-emacs--error-line-text message)))
      (if (and (consp range)
               (markerp (car range))
               (markerp (cdr range))
               (marker-buffer (car range))
               (marker-buffer (cdr range)))
          (save-excursion
            (goto-char (car range))
            (delete-region (car range) (cdr range))
            (insert line-text)
            (set-marker (cdr range) (point)))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) t)))
            (insert line-text)
            (set-marker end (point))
            (setf (psi-emacs-state-error-line-range state) (cons start end)))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--set-last-error (state message)
  "Persist MESSAGE as STATE last error and upsert transcript error line."
  (when state
    (setf (psi-emacs-state-last-error state) message)
    (if (and (stringp message) (not (string-empty-p message)))
        (psi-emacs--upsert-error-line state message)
      (psi-emacs--clear-error-line state))
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--clear-last-error (state)
  "Clear persistent error summary/line from STATE and refresh header."
  (when state
    (setf (psi-emacs-state-last-error state) nil)
    (psi-emacs--clear-error-line state)
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--stream-watchdog-timeout-message ()
  "Return deterministic timeout message for stalled streaming."
  (format "Streaming stalled after %.0fs without progress. Aborted current run."
          psi-emacs-stream-timeout-seconds))

(defun psi-emacs--on-stream-watchdog-timeout (buffer state)
  "Watchdog timeout callback for BUFFER/STATE.

When streaming has stalled, abort once, append deterministic feedback,
and transition to `error'."
  (when (and (buffer-live-p buffer)
             state)
    (with-current-buffer buffer
      (when (and psi-emacs--state
                 (eq psi-emacs--state state)
                 (eq (psi-emacs-state-run-state state) 'streaming))
        (let ((timeout-message (psi-emacs--stream-watchdog-timeout-message)))
          (psi-emacs--disarm-stream-watchdog state)
          (psi-emacs--dispatch-request "abort" nil)
          (setf (psi-emacs-state-assistant-in-progress state) nil)
          (setf (psi-emacs-state-assistant-range state) nil)
          (psi-emacs--set-last-error state timeout-message)
          (psi-emacs--set-run-state state 'error))))))

(defun psi-emacs--disarm-stream-watchdog (state)
  "Cancel and clear the stream watchdog timer for STATE."
  (when state
    (when-let ((timer (psi-emacs-state-stream-watchdog-timer state)))
      (cancel-timer timer))
    (setf (psi-emacs-state-stream-watchdog-timer state) nil)))

(defun psi-emacs--arm-stream-watchdog (state)
  "Arm stream watchdog timer for STATE using `psi-emacs-stream-timeout-seconds'."
  (when state
    (psi-emacs--disarm-stream-watchdog state)
    (setf (psi-emacs-state-last-stream-progress-at state) (float-time))
    (setf (psi-emacs-state-stream-watchdog-timer state)
          (run-at-time psi-emacs-stream-timeout-seconds nil
                       #'psi-emacs--on-stream-watchdog-timeout
                       (current-buffer)
                       state))))

(defun psi-emacs--reset-stream-watchdog (state)
  "Record streaming progress for STATE and reset watchdog timeout window."
  (when state
    (setf (psi-emacs-state-last-stream-progress-at state) (float-time))
    (when (eq (psi-emacs-state-run-state state) 'streaming)
      (psi-emacs--arm-stream-watchdog state))))

(defun psi-emacs--refresh-header-line ()
  "Refresh minimal header-line status for current psi buffer."
  (setq-local header-line-format
              (when psi-emacs--state
                (psi-emacs--status-string psi-emacs--state))))

(defun psi-emacs--on-rpc-state-change (buffer client)
  "Apply CLIENT state changes to BUFFER-local frontend state."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when psi-emacs--state
        (setf (psi-emacs-state-process-state psi-emacs--state)
              (psi-rpc-client-process-state client))
        (setf (psi-emacs-state-transport-state psi-emacs--state)
              (psi-rpc-client-transport-state client))
        (setf (psi-emacs-state-process psi-emacs--state)
              (psi-rpc-client-process client))
        (when (and (eq (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
                   (eq (psi-rpc-client-transport-state client) 'ready))
          (psi-emacs--set-run-state psi-emacs--state 'idle))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

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

(defun psi-emacs--on-rpc-event (buffer frame)
  "Handle rpc event FRAME for BUFFER, keeping errors out of transcript."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (let ((event (alist-get :event frame nil nil #'equal))
            (data (alist-get :data frame nil nil #'equal)))
        (if (equal event "error")
            (psi-emacs--on-rpc-error
             buffer
             (or (alist-get :code data nil nil #'equal)
                 (alist-get :error-code data nil nil #'equal)
                 "rpc/error")
             (or (alist-get :message data nil nil #'equal)
                 (alist-get :error-message data nil nil #'equal)
                 "rpc error")
             frame)
          (psi-emacs--handle-rpc-event frame))))))

(defun psi-emacs--start-rpc-client (buffer)
  "Start rpc-edn client for BUFFER and wire callbacks."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (let ((client (psi-rpc-make-client
                     :on-state-change (lambda (rpc-client)
                                        (psi-emacs--on-rpc-state-change buffer rpc-client))
                     :on-event (lambda (frame)
                                 (psi-emacs--on-rpc-event buffer frame))
                     :on-rpc-error (lambda (code message-text frame)
                                     (psi-emacs--on-rpc-error buffer code message-text frame)))))
        (setf (psi-emacs-state-rpc-client psi-emacs--state) client)
        (psi-rpc-start! client
                        psi-emacs--spawn-process-function
                        psi-emacs-command
                        (if psi-emacs-enable-extension-ui-parity
                            psi-rpc-parity-topics
                          psi-rpc-mvp-topics))
        (setf (psi-emacs-state-process psi-emacs--state) (psi-rpc-client-process client))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--default-spawn-process (command)
  "Spawn psi subprocess from COMMAND.

COMMAND is a list suitable for `make-process'."
  (let* ((stderr-buffer (generate-new-buffer " *psi-rpc-stderr*"))
         (process (make-process
                   :name "psi-rpc-edn"
                   :command command
                   :buffer nil
                   :stderr stderr-buffer
                   :noquery t
                   :connection-type 'pipe)))
    (process-put process 'psi-rpc-stderr-buffer stderr-buffer)
    process))

(defun psi-emacs--default-send-request (state op params &optional callback)
  "Send OP with PARAMS using STATE rpc client, if available."
  (when-let ((client (psi-emacs-state-rpc-client state)))
    (psi-rpc-send-request! client op params callback)))

(defun psi-emacs--teardown-buffer ()
  "Stop owned subprocess and clear local/global frontend state for current buffer."
  (when (and psi-emacs--state
             (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
    (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
  (psi-emacs--disarm-stream-watchdog psi-emacs--state)
  (psi-emacs--clear-last-error psi-emacs--state)
  (when (process-live-p psi-emacs--owned-process)
    (delete-process psi-emacs--owned-process))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
    (set-marker (psi-emacs-state-draft-anchor psi-emacs--state) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-assistant-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-assistant-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-assistant-range psi-emacs--state)) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-projection-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-projection-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-projection-range psi-emacs--state)) nil))
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

(defun psi-emacs--install-buffer-lifecycle-hooks ()
  "Install local lifecycle hooks for dedicated psi buffer."
  (add-hook 'kill-buffer-hook #'psi-emacs--teardown-buffer nil t))

(defun psi-emacs--streaming-p ()
  "Return non-nil when the frontend is in streaming mode."
  (and psi-emacs--state
       (eq (psi-emacs-state-run-state psi-emacs--state) 'streaming)))

(defun psi-emacs--tail-draft-text ()
  "Return compose text from draft anchor to end-of-buffer."
  (let* ((anchor (and psi-emacs--state
                      (psi-emacs-state-draft-anchor psi-emacs--state)))
         (start (if (and (markerp anchor) (marker-buffer anchor))
                    (marker-position anchor)
                  (point-max))))
    (buffer-substring-no-properties (min start (point-max)) (point-max))))

(defun psi-emacs--composed-text ()
  "Return composed text using region-first, else tail draft."
  (if (use-region-p)
      (buffer-substring-no-properties (region-beginning) (region-end))
    (psi-emacs--tail-draft-text)))

(defun psi-emacs--draft-anchor-valid-p ()
  "Return non-nil when the current draft anchor marker is valid."
  (and psi-emacs--state
       (markerp (psi-emacs-state-draft-anchor psi-emacs--state))
       (marker-buffer (psi-emacs-state-draft-anchor psi-emacs--state))))

(defun psi-emacs--draft-anchor-at-end-p ()
  "Return non-nil when draft anchor is currently at end-of-buffer."
  (and (psi-emacs--draft-anchor-valid-p)
       (= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
          (point-max))))

(defun psi-emacs--set-draft-anchor-to-end ()
  "Move/create draft anchor at end-of-buffer."
  (when psi-emacs--state
    (let ((anchor (psi-emacs-state-draft-anchor psi-emacs--state)))
      (if (and (markerp anchor) (marker-buffer anchor))
          (set-marker anchor (point-max))
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))))))

(defun psi-emacs--consume-tail-draft (used-region-p)
  "Advance draft anchor when tail draft was consumed.

USED-REGION-P non-nil means compose came from region and anchor is untouched."
  (unless used-region-p
    (psi-emacs--set-draft-anchor-to-end)))

(defun psi-emacs--dispatch-request (op params &optional callback)
  "Dispatch OP PARAMS CALLBACK through configured request function."
  (when psi-emacs--state
    (funcall psi-emacs--send-request-function psi-emacs--state op params callback)))

(defun psi-emacs--request-frontend-exit ()
  "Request frontend exit for current psi buffer."
  (when (buffer-live-p (current-buffer))
    (kill-buffer (current-buffer))))

(defun psi-emacs--append-assistant-message (text)
  "Append assistant TEXT as a finalized transcript line."
  (psi-emacs--assistant-finalize text))

(defun psi-emacs--idle-slash-help-text ()
  "Return deterministic help text for supported idle slash commands."
  (string-join
   (list "Supported slash commands:"
         "/quit, /exit  Exit this psi buffer"
         (if psi-emacs-enable-resume-parity
             "/resume [path] Resume a prior session (selector when path omitted)"
           "/resume       Resume selector unavailable in this frontend")
         "/new          Start a fresh backend session"
         "/status       Show frontend diagnostics"
         "/help, /?     Show this help")
   "\n"))

(defun psi-emacs--reset-transcript-for-new-session ()
  "Clear transcript rendering state for a successful /new command."
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when psi-emacs--state
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--clear-last-error psi-emacs--state)
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (clrhash (psi-emacs-state-tool-rows psi-emacs--state))
    (setf (psi-emacs-state-projection-widgets psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-statuses psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-footer psi-emacs--state) nil)
    (psi-emacs--clear-notification-lifecycle psi-emacs--state)
    (when (consp (psi-emacs-state-projection-range psi-emacs--state))
      (set-marker (car (psi-emacs-state-projection-range psi-emacs--state)) nil)
      (set-marker (cdr (psi-emacs-state-projection-range psi-emacs--state)) nil))
    (setf (psi-emacs-state-projection-range psi-emacs--state) nil)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--set-run-state psi-emacs--state 'idle)
    (psi-emacs--refresh-header-line))
  (set-buffer-modified-p nil))

(defun psi-emacs--new-session-error-message (frame)
  "Return deterministic /new error text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to start a fresh backend session: %s" details)
      "Unable to start a fresh backend session.")))

(defun psi-emacs--handle-new-session-response (frame)
  "Apply /new callback FRAME effects to the current frontend buffer."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (progn
        (psi-emacs--reset-transcript-for-new-session)
        (psi-emacs--append-assistant-message "Started a fresh backend session."))
    (psi-emacs--append-assistant-message
     (psi-emacs--new-session-error-message frame))))

(defun psi-emacs--request-new-session ()
  "Request a fresh backend session for /new and render deterministic feedback."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "new_session"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (psi-emacs--handle-new-session-response frame)))))))

(defun psi-emacs--resume-args-from-message (message)
  "Extract `/resume` argument tail from MESSAGE.

Return nil for no argument. Otherwise return trimmed argument string."
  (let* ((trimmed (string-trim (or message "")))
         (tail (string-trim (string-remove-prefix "/resume" trimmed))))
    (unless (string-empty-p tail)
      tail)))

(defun psi-emacs--handle-idle-resume-default (_state _message)
  "Handle `/resume` in default MVP mode."
  (psi-emacs--append-assistant-message
   "Resume selector unavailable in this frontend."))

(defun psi-emacs--resume-session-list-query ()
  "Return canonical EQL query string for `/resume` session discovery."
  "[{:psi.session/list [:psi.session-info/path
                        :psi.session-info/name
                        :psi.session-info/first-message
                        :psi.session-info/modified]}]")

(defun psi-emacs--resume-session-list-from-query-frame (frame)
  "Extract session list vector from `query_eql` FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (result (and (listp data) (alist-get :result data nil nil #'equal)))
         (sessions (and (listp result)
                        (alist-get :psi.session/list result nil nil #'equal))))
    (cond
     ((vectorp sessions) (append sessions nil))
     ((listp sessions) sessions)
     (t nil))))

(defun psi-emacs--resume-session-description (session)
  "Return description-first label seed for SESSION."
  (let ((name (string-trim (or (alist-get :psi.session-info/name session nil nil #'equal) "")))
        (first-message (string-trim (or (alist-get :psi.session-info/first-message session nil nil #'equal) "")))
        (path (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) ""))))
    (cond
     ((not (string-empty-p name)) name)
     ((not (string-empty-p first-message)) first-message)
     ((not (string-empty-p path)) (file-name-nondirectory path))
     (t "(unnamed session)"))))

(defun psi-emacs--resume-session-modified-seconds (session)
  "Return SESSION modified timestamp as seconds since epoch.

Unreadable/missing timestamps normalize to 0."
  (let ((modified (alist-get :psi.session-info/modified session nil nil #'equal)))
    (cond
     ((numberp modified) (float modified))
     ((stringp modified)
      (or (ignore-errors (float-time (date-to-time modified))) 0.0))
     (t
      (or (ignore-errors (float-time modified)) 0.0)))))

(defun psi-emacs--resume-session-path (session)
  "Return trimmed canonical session path for SESSION, or empty string."
  (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) "")))

(defun psi-emacs--sort-resume-sessions (sessions)
  "Sort SESSIONS by modified desc (newest first), then path asc."
  (sort (copy-sequence sessions)
        (lambda (a b)
          (let ((am (psi-emacs--resume-session-modified-seconds a))
                (bm (psi-emacs--resume-session-modified-seconds b))
                (ap (psi-emacs--resume-session-path a))
                (bp (psi-emacs--resume-session-path b)))
            (if (/= am bm)
                (> am bm)
              (string< ap bp))))))

(defun psi-emacs--resume-session-candidates (sessions)
  "Build deterministic selector candidates from SESSIONS.

Returns list of cons cells (DISPLAY . CANONICAL-PATH)."
  (let ((seen (make-hash-table :test #'equal))
        (candidates nil))
    (dolist (session (psi-emacs--sort-resume-sessions sessions))
      (let ((path (psi-emacs--resume-session-path session)))
        (when (not (string-empty-p path))
          (let* ((description (psi-emacs--resume-session-description session))
                 (base (format "%s — %s" description path))
                 (count (1+ (gethash base seen 0)))
                 (label (if (= count 1)
                            base
                          (format "%s (%d)" base count))))
            (puthash base count seen)
            (push (cons label path) candidates)))))
    (nreverse candidates)))

(defun psi-emacs--resume-select-session-path (candidates)
  "Prompt for session selection from CANDIDATES.

CANDIDATES is a list of (DISPLAY . CANONICAL-PATH).
Returns canonical path string, or nil when cancelled/no selection."
  (condition-case _
      (let* ((labels (mapcar #'car candidates))
             (chosen (completing-read "Resume session: " labels nil t)))
        (when (and (stringp chosen)
                   (not (string-empty-p chosen)))
          (cdr (assoc chosen candidates))))
    (quit nil)))

(defun psi-emacs--request-resume-session-list (callback)
  "Fetch session list via `query_eql` and invoke CALLBACK with response frame."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--resume-session-list-query)))
   callback))

(defun psi-emacs--rpc-frame-success-p (frame)
  "Return non-nil when FRAME is a successful RPC response."
  (and (eq (alist-get :kind frame) :response)
       (eq (alist-get :ok frame) t)))

(defun psi-emacs--frame-messages-list (frame)
  "Extract `:messages` list from FRAME payload.

Returns a proper list in canonical order, or nil when missing/unreadable."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (messages (and (listp data)
                        (alist-get :messages data nil nil #'equal))))
    (cond
     ((vectorp messages) (append messages nil))
     ((listp messages) messages)
     (t nil))))

(defun psi-emacs--message-text-from-content (content)
  "Extract display text from message CONTENT payload."
  (cond
   ((stringp content) content)
   ((and (listp content)
         (or (alist-get :text content nil nil #'equal)
             (alist-get 'text content nil nil #'equal)))
    (or (alist-get :text content nil nil #'equal)
        (alist-get 'text content nil nil #'equal)
        ""))
   (t (psi-emacs--assistant-content->text content))))

(defun psi-emacs--message->transcript-line (message)
  "Render MESSAGE as one deterministic transcript line."
  (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                       (alist-get 'role message nil nil #'equal)
                       :assistant))
         (role (if (stringp role-raw) (intern role-raw) role-raw))
         (content (or (alist-get :content message nil nil #'equal)
                      (alist-get 'content message nil nil #'equal)))
         (text (or (alist-get :text message nil nil #'equal)
                   (alist-get 'text message nil nil #'equal)
                   (alist-get :message message nil nil #'equal)
                   (alist-get 'message message nil nil #'equal)
                   (psi-emacs--message-text-from-content content)
                   "")))
    (format "%s: %s\n"
            (if (eq role :user) "User" "Assistant")
            text)))

(defun psi-emacs--replay-session-messages (messages)
  "Replay MESSAGES into transcript in deterministic input order."
  (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p)))
    (save-excursion
      (goto-char (point-max))
      (dolist (message messages)
        (when (listp message)
          (insert (psi-emacs--message->transcript-line message)))))
    (when follow-anchor
      (psi-emacs--set-draft-anchor-to-end))))

(defun psi-emacs--request-get-messages-for-switch (state)
  "Request `get_messages` and replay transcript for switched STATE."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "get_messages"
     nil
     (lambda (messages-frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--replay-session-messages
              (psi-emacs--frame-messages-list messages-frame))
             (psi-emacs--set-run-state state 'idle)
             (psi-emacs--refresh-header-line))))))))

(defun psi-emacs--switch-session-error-message (frame)
  "Return deterministic `/resume` switch failure text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (alist-get :message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to switch session: %s" details)
      "Unable to switch session.")))

(defun psi-emacs--handle-switch-session-response (state _session-path frame)
  "Handle `switch_session` callback FRAME for STATE.

Success path clears stale transcript/render state, then requests and replays
messages for deterministic rehydration.

Failure path appends deterministic assistant-visible feedback, sets
`last-error`, and does not run success-only side effects."
  (when (and state (eq state psi-emacs--state))
    (if (psi-emacs--rpc-frame-success-p frame)
        (progn
          (psi-emacs--reset-transcript-state)
          (psi-emacs--set-run-state state 'streaming)
          (psi-emacs--request-get-messages-for-switch state))
      (let ((message (psi-emacs--switch-session-error-message frame)))
        (psi-emacs--append-assistant-message message)
        (psi-emacs--set-last-error state message)))))

(defun psi-emacs--request-switch-session (state session-path)
  "Dispatch `switch_session` for SESSION-PATH from STATE."
  (when (and state
             (stringp session-path)
             (not (string-empty-p session-path)))
    (let ((buffer (current-buffer)))
      (psi-emacs--dispatch-request
       "switch_session"
       `((:session-path . ,session-path))
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (psi-emacs--handle-switch-session-response state session-path frame)))))))))

(defun psi-emacs--handle-idle-resume-parity-no-arg (state)
  "Handle parity-mode `/resume` without explicit session path."
  (let ((buffer (current-buffer)))
    (psi-emacs--request-resume-session-list
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let* ((sessions (psi-emacs--resume-session-list-from-query-frame frame))
                    (candidates (psi-emacs--resume-session-candidates sessions))
                    (selected-path (psi-emacs--resume-select-session-path candidates)))
               (when selected-path
                 (psi-emacs--handle-idle-resume-parity-explicit-path state selected-path))))))))))

(defun psi-emacs--handle-idle-resume-parity-explicit-path (state session-path)
  "Handle parity-mode `/resume <session-path>`."
  (psi-emacs--request-switch-session state session-path))

(defun psi-emacs--handle-idle-resume-command (state message)
  "Handle `/resume` MESSAGE according to capability flags and args."
  (if (not psi-emacs-enable-resume-parity)
      (psi-emacs--handle-idle-resume-default state message)
    (let ((session-path (psi-emacs--resume-args-from-message message)))
      (if session-path
          (psi-emacs--handle-idle-resume-parity-explicit-path state session-path)
        (psi-emacs--handle-idle-resume-parity-no-arg state)))))

(defun psi-emacs--default-handle-idle-slash-command (state message)
  "Default idle slash handler.

Return non-nil when MESSAGE is handled and should not fall through to
normal prompt dispatch."
  (let* ((trimmed (string-trim (or message "")))
         (command (car (split-string trimmed "[ \t\n\r]+" t))))
    (pcase command
      ((or "/quit" "/exit")
       (psi-emacs--request-frontend-exit)
       t)
      ("/resume"
       (psi-emacs--handle-idle-resume-command state message)
       t)
      ("/new"
       (psi-emacs--request-new-session)
       t)
      ("/status"
       (psi-emacs--append-assistant-message
        (psi-emacs--status-diagnostics-string state))
       t)
      ((or "/help" "/?")
       (psi-emacs--append-assistant-message
        (psi-emacs--idle-slash-help-text))
       t)
      (_ nil))))

(defun psi-emacs--slash-command-candidate-p (message)
  "Return non-nil when MESSAGE is a slash command candidate."
  (let ((trimmed (string-trim (or message ""))))
    (and (not (string-empty-p trimmed))
         (string-prefix-p "/" trimmed))))

(defun psi-emacs--dispatch-idle-compose-message (message)
  "Dispatch idle compose MESSAGE through slash interception and fallback.

When MESSAGE is a slash command candidate, run
`psi-emacs--idle-slash-command-handler-function'. If not handled, fall through
to normal prompt dispatch."
  (let ((handled (and psi-emacs--state
                      (psi-emacs--slash-command-candidate-p message)
                      (funcall psi-emacs--idle-slash-command-handler-function
                               psi-emacs--state
                               message))))
    (unless handled
      (psi-emacs--set-run-state psi-emacs--state 'streaming)
      (psi-emacs--reset-stream-watchdog psi-emacs--state)
      (psi-emacs--dispatch-request "prompt" `((:message . ,message))))
    handled))

(defun psi-emacs-send-from-buffer (prefix)
  "Send composed text using canonical send semantics.

With PREFIX while streaming, queue override is used.
Without PREFIX while streaming, steer is used.
When idle, routes through slash interception then normal prompt fallback."
  (interactive "P")
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (progn
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state)
          (psi-emacs--dispatch-request
           "prompt_while_streaming"
           `((:message . ,message)
             (:behavior . ,(if prefix "queue" "steer")))))
      (psi-emacs--dispatch-idle-compose-message message))
    (psi-emacs--consume-tail-draft used-region-p)))

(defun psi-emacs-queue-from-buffer ()
  "Queue composed text while streaming; use idle slash dispatch when idle."
  (interactive)
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (progn
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state)
          (psi-emacs--dispatch-request
           "prompt_while_streaming"
           `((:message . ,message)
             (:behavior . "queue"))))
      (psi-emacs--dispatch-idle-compose-message message))
    (psi-emacs--consume-tail-draft used-region-p)))

(defun psi-emacs-abort ()
  "Abort active streaming request via RPC and transition to non-streaming UI state."
  (interactive)
  (psi-emacs--dispatch-request "abort" nil)
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--set-run-state psi-emacs--state 'idle)))

(defun psi-emacs--ensure-newline-before-append ()
  "Ensure appending at end starts on a new line if buffer has content."
  (goto-char (point-max))
  (unless (or (bobp)
              (eq (char-before) ?\n))
    (insert "\n")))

(defun psi-emacs--string-has-face-p (text)
  "Return non-nil if TEXT has any `face' property."
  (let ((i 0)
        (len (length text))
        (hit nil))
    (while (and (< i len) (not hit))
      (setq hit (get-text-property i 'face text))
      (setq i (1+ i)))
    hit))

(defun psi-emacs--ansi-to-face (text)
  "Return TEXT with ANSI escapes converted to Emacs face properties."
  (let* ((raw (or text ""))
         (had-ansi (string-match-p "\x1b\\[[0-9;]*m" raw))
         (converted (ansi-color-apply raw)))
    (if (and had-ansi
             (not (psi-emacs--string-has-face-p converted))
             (not (string-empty-p converted)))
        (propertize converted 'face 'ansi-color-red)
      converted)))

(defun psi-emacs--render-assistant-line (text)
  "Render assistant TEXT in canonical MVP line format."
  (concat "Assistant: " (or text "") "\n"))

(defun psi-emacs--set-assistant-line (text)
  "Create or update the single in-progress assistant line with TEXT."
  (when psi-emacs--state
    (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs-state-assistant-range psi-emacs--state)))
      (if (and (consp range)
               (markerp (car range))
               (markerp (cdr range))
               (marker-buffer (car range))
               (marker-buffer (cdr range)))
          (save-excursion
            (let ((start (car range))
                  (end (cdr range)))
              (goto-char start)
              (delete-region start end)
              (insert (psi-emacs--render-assistant-line text))
              (set-marker end (point))))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) t)))
            (insert (psi-emacs--render-assistant-line text))
            (set-marker end (point))
            (setf (psi-emacs-state-assistant-range psi-emacs--state)
                  (cons start end)))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--assistant-delta (text)
  "Apply assistant delta TEXT to the in-progress assistant block."
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (psi-emacs--reset-stream-watchdog psi-emacs--state)
    (let ((next (concat (or (psi-emacs-state-assistant-in-progress psi-emacs--state) "")
                        (or text ""))))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) next)
      (psi-emacs--set-assistant-line next))))

(defun psi-emacs--assistant-finalize (text)
  "Finalize assistant block with TEXT and clear in-progress state."
  (when psi-emacs--state
    (let* ((text* (if (and (stringp text) (string-empty-p text)) nil text))
           (final (or text* (psi-emacs-state-assistant-in-progress psi-emacs--state) "")))
      (psi-emacs--set-assistant-line final)
      (goto-char (point-max))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
      (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
      (psi-emacs--disarm-stream-watchdog psi-emacs--state)
      (psi-emacs--set-run-state psi-emacs--state 'idle))))

(defun psi-emacs--event-data-get (data keys)
  "Return first non-nil value from DATA for KEYS.

DATA is expected to be an alist map."
  (catch 'hit
    (dolist (k keys)
      (let ((v (alist-get k data nil nil #'equal)))
        (when v
          (throw 'hit v))))
    nil))

(defun psi-emacs--assistant-content->text (content)
  "Extract assistant display text from CONTENT blocks."
  (let ((blocks (cond
                 ((vectorp content) (append content nil))
                 ((listp content) content)
                 (t nil))))
    (string-join
     (delq nil
           (mapcar (lambda (block)
                     (when (listp block)
                       (let ((type (psi-emacs--event-data-get block '(:type type))))
                         (cond
                          ((or (eq type :text) (equal type "text"))
                           (psi-emacs--event-data-get block '(:text text :message message)))
                          ((or (eq type :error) (equal type "error"))
                           (when-let ((err (psi-emacs--event-data-get block
                                                                      '(:text text :message message :error-message error-message))))
                             (format "[error] %s" err)))))))
                   blocks))
     "")))

(defun psi-emacs--tool-row-header-string (tool-id stage)
  "Build collapsed header string for TOOL-ID at STAGE.

Returns a single-line string: \"Tool[<id>] <stage>\\n\"."
  (format "Tool[%s] %s\n" tool-id stage))

(defun psi-emacs--tool-row-string (tool-id stage text)
  "Build tool row string for TOOL-ID at STAGE with TEXT.

ANSI sequences in TEXT are converted to Emacs faces."
  (let ((prefix (format "Tool[%s] %s: " tool-id stage))
        (body (psi-emacs--ansi-to-face text)))
    (concat prefix body "\n")))

(defun psi-emacs--render-tool-row (tool-id stage accumulated-text view-mode)
  "Render tool row for TOOL-ID at STAGE with ACCUMULATED-TEXT using VIEW-MODE.

VIEW-MODE is `collapsed' (header-only) or `expanded' (full body).
ANSI sequences in ACCUMULATED-TEXT are converted to Emacs faces."
  (if (eq view-mode 'collapsed)
      (psi-emacs--tool-row-header-string tool-id stage)
    (psi-emacs--tool-row-string tool-id stage (or accumulated-text ""))))

(defun psi-emacs--upsert-tool-row (tool-id stage text)
  "Create or update TOOL-ID row for lifecycle STAGE with TEXT.

Always accumulates TEXT into :accumulated-text in the row state.
Renders according to the current global tool-output-view-mode."
  (when (and psi-emacs--state tool-id)
    (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
           (rows (psi-emacs-state-tool-rows psi-emacs--state))
           (view-mode (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (row (gethash tool-id rows))
           (start (plist-get row :start))
           (end (plist-get row :end))
           (prev-accumulated (or (plist-get row :accumulated-text) ""))
           (new-text (or text ""))
           ;; Accumulate: append new text to existing accumulated text
           (accumulated (concat prev-accumulated new-text))
           (rendered (psi-emacs--render-tool-row tool-id stage accumulated view-mode)))
      (if (and (markerp start)
               (markerp end)
               (marker-buffer start)
               (marker-buffer end))
          (save-excursion
            (goto-char start)
            (delete-region start end)
            (insert rendered)
            (set-marker end (point))
            (puthash tool-id (list :id tool-id
                                   :stage stage
                                   :text text
                                   :accumulated-text accumulated
                                   :start start
                                   :end end)
                     rows))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((new-start (copy-marker (point) nil))
                (new-end (copy-marker (point) t)))
            (insert rendered)
            (set-marker new-end (point))
            (puthash tool-id (list :id tool-id
                                   :stage stage
                                   :text text
                                   :accumulated-text accumulated
                                   :start new-start
                                   :end new-end)
                     rows))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

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

(defun psi-emacs--projection-sort-widgets (widgets)
  "Return WIDGETS sorted by [placement, extension-id, widget-id]."
  (sort (copy-sequence widgets)
        (lambda (a b)
          (let ((ap (psi-emacs--projection-item-key a '(:placement placement)))
                (bp (psi-emacs--projection-item-key b '(:placement placement)))
                (ae (psi-emacs--projection-item-key a '(:extension-id extension-id :extensionId extensionId)))
                (be (psi-emacs--projection-item-key b '(:extension-id extension-id :extensionId extensionId)))
                (aw (psi-emacs--projection-item-key a '(:widget-id widget-id :widgetId widgetId)))
                (bw (psi-emacs--projection-item-key b '(:widget-id widget-id :widgetId widgetId))))
            (cond
             ((string< ap bp) t)
             ((string< bp ap) nil)
             ((string< ae be) t)
             ((string< be ae) nil)
             (t (string< aw bw)))))))

(defun psi-emacs--projection-sort-statuses (statuses)
  "Return STATUSES sorted by extension-id."
  (sort (copy-sequence statuses)
        (lambda (a b)
          (string< (psi-emacs--projection-item-key a '(:extension-id extension-id :extensionId extensionId))
                   (psi-emacs--projection-item-key b '(:extension-id extension-id :extensionId extensionId))))))

(defun psi-emacs--projection-footer-text (data)
  "Extract deterministic footer projection text from event DATA."
  (let* ((path-line (psi-emacs--event-data-get data '(:path-line path-line :pathLine pathLine)))
         (stats-line (psi-emacs--event-data-get data '(:stats-line stats-line :statsLine statsLine)))
         (status-line (psi-emacs--event-data-get data '(:status-line status-line :statusLine statusLine)))
         (canonical-lines (delq nil
                                (mapcar (lambda (line)
                                          (when (and (stringp line)
                                                     (not (string-empty-p line)))
                                            line))
                                        (list path-line stats-line status-line)))))
    (if canonical-lines
        (string-join canonical-lines " | ")
      (let ((value (psi-emacs--event-data-get data
                                              '(:text text :message message :footer footer :content content))))
        (cond
         ((stringp value) value)
         ((null value) nil)
         (t (format "%s" value)))))))

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
           (extension-id (psi-emacs--projection-item-key data '(:extension-id extension-id :extensionId extensionId)))
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

(defun psi-emacs--projection-render-block (state)
  "Render deterministic projection block from STATE."
  (let ((widgets (or (psi-emacs-state-projection-widgets state) '()))
        (statuses (or (psi-emacs-state-projection-statuses state) '()))
        (notifications (or (psi-emacs-state-projection-notifications state) '()))
        (footer (psi-emacs-state-projection-footer state))
        (lines nil))
    (when widgets
      (push "Extension Widgets:" lines)
      (dolist (widget widgets)
        (push (format "- [%s/%s/%s] %s"
                      (psi-emacs--projection-item-key widget '(:placement placement))
                      (psi-emacs--projection-item-key widget '(:extension-id extension-id :extensionId extensionId))
                      (psi-emacs--projection-item-key widget '(:widget-id widget-id :widgetId widgetId))
                      (psi-emacs--projection-item-text widget))
              lines)))
    (when statuses
      (push "Extension Statuses:" lines)
      (dolist (status statuses)
        (push (format "- [%s] %s"
                      (psi-emacs--projection-item-key status '(:extension-id extension-id :extensionId extensionId))
                      (psi-emacs--projection-item-text status))
              lines)))
    (when notifications
      (push "Extension Notifications:" lines)
      (dolist (notification notifications)
        (push (psi-emacs--projection-notification-line notification) lines)))
    (when (and (stringp footer)
               (not (string-empty-p footer)))
      (push (format "Footer: %s" footer) lines))
    (if lines
        (concat (string-join (nreverse lines) "\n") "\n")
      "")))

(defun psi-emacs--upsert-projection-block ()
  "Upsert deterministic projection block from current state."
  (when psi-emacs--state
    (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
           (range (psi-emacs-state-projection-range psi-emacs--state))
           (start (and (consp range) (car range)))
           (end (and (consp range) (cdr range)))
           (rendered (psi-emacs--projection-render-block psi-emacs--state)))
      (if (and (markerp start)
               (markerp end)
               (marker-buffer start)
               (marker-buffer end))
          (save-excursion
            (goto-char start)
            (delete-region start end)
            (if (string-empty-p rendered)
                (progn
                  (set-marker start nil)
                  (set-marker end nil)
                  (setf (psi-emacs-state-projection-range psi-emacs--state) nil))
              (insert rendered)
              (set-marker end (point))))
        (unless (string-empty-p rendered)
          (save-excursion
            (psi-emacs--ensure-newline-before-append)
            (let ((new-start (copy-marker (point) nil))
                  (new-end (copy-marker (point) t)))
              (insert rendered)
              (set-marker new-end (point))
              (setf (psi-emacs-state-projection-range psi-emacs--state)
                    (cons new-start new-end))))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--dialog-result-normalize (value)
  "Normalize dialog VALUE to the shared RPC result domain."
  (cond
   ((or (null value) (booleanp value) (stringp value)) value)
   (t (format "%s" value))))

(defun psi-emacs--dialog-send-resolve (dialog-id result)
  "Send `resolve_dialog` for DIALOG-ID with RESULT."
  (psi-emacs--dispatch-request
   "resolve_dialog"
   `((:dialog-id . ,dialog-id)
     (:result . ,(psi-emacs--dialog-result-normalize result)))))

(defun psi-emacs--dialog-send-cancel (dialog-id)
  "Send `cancel_dialog` for DIALOG-ID."
  (psi-emacs--dispatch-request
   "cancel_dialog"
   `((:dialog-id . ,dialog-id))))

(defun psi-emacs--dialog-prompt-text (data fallback)
  "Return deterministic prompt text from DATA, else FALLBACK."
  (let ((value (psi-emacs--event-data-get data
                                          '(:prompt prompt :message message :text text :title title))))
    (if (and (stringp value)
             (not (string-empty-p value)))
        value
      fallback)))

(defun psi-emacs--dialog-option-candidates (options)
  "Convert OPTIONS into selector candidates of (LABEL . VALUE)."
  (let ((candidates nil))
    (dolist (item (psi-emacs--projection-seq options))
      (when (listp item)
        (let ((label (psi-emacs--event-data-get item '(:label label :name name :text text)))
              (value (psi-emacs--event-data-get item '(:value value :id id))))
          (when (and (stringp label)
                     (not (string-empty-p label)))
            (push (cons label (psi-emacs--dialog-result-normalize value))
                  candidates)))))
    (nreverse candidates)))

(defun psi-emacs--handle-dialog-requested (data)
  "Handle `ui/dialog-requested` DATA with one deterministic response op."
  (let* ((dialog-id (psi-emacs--event-data-get data '(:dialog-id dialog-id :dialogId dialogId)))
         (kind* (psi-emacs--event-data-get data '(:kind kind)))
         (kind (if (symbolp kind*) (symbol-name kind*) kind*)))
    (if (not (and (stringp dialog-id) (not (string-empty-p dialog-id))))
        (psi-emacs--append-assistant-message
         "Ignored malformed ui/dialog-requested event: missing dialog-id.")
      (condition-case _
          (pcase kind
            ("confirm"
             (let ((answer (y-or-n-p
                            (format "%s "
                                    (psi-emacs--dialog-prompt-text data "Confirm?")))))
               (psi-emacs--dialog-send-resolve dialog-id answer)))
            ("select"
             (let* ((candidates (psi-emacs--dialog-option-candidates
                                 (or (psi-emacs--event-data-get data '(:options options))
                                     (psi-emacs--event-data-get data '(:items items)))))
                    (labels (mapcar #'car candidates)))
               (if (null candidates)
                   (psi-emacs--dialog-send-cancel dialog-id)
                 (let* ((choice (completing-read
                                 (format "%s "
                                         (psi-emacs--dialog-prompt-text data "Select an option:"))
                                 labels nil t))
                        (selected (assoc choice candidates)))
                   (if selected
                       (psi-emacs--dialog-send-resolve dialog-id (cdr selected))
                     (psi-emacs--dialog-send-cancel dialog-id))))))
            ("input"
             (let ((answer (read-string
                            (format "%s "
                                    (psi-emacs--dialog-prompt-text data "Input:")))))
               (psi-emacs--dialog-send-resolve dialog-id answer)))
            (_
             (psi-emacs--dialog-send-cancel dialog-id)))
        (quit
         (psi-emacs--dialog-send-cancel dialog-id))))))

(defun psi-emacs--handle-rpc-event (frame)
  "Handle inbound rpc-edn event FRAME for transcript rendering."
  (let* ((event (alist-get :event frame nil nil #'equal))
         (data (or (alist-get :data frame nil nil #'equal) '())))
    (pcase event
      ("assistant/delta"
       (psi-emacs--assistant-delta
        (or (psi-emacs--event-data-get data '(:text text :delta delta)) "")))
      ("assistant/message"
       (psi-emacs--assistant-finalize
        (or (psi-emacs--event-data-get data '(:text text :message message))
            (psi-emacs--assistant-content->text
             (psi-emacs--event-data-get data '(:content content))))))
      ((or "tool/start" "tool/delta" "tool/executing" "tool/update" "tool/result")
       (let ((tool-id (psi-emacs--event-data-get data
                                                 '(:tool-id tool-id :toolCallId toolCallId :tool-call-id tool-call-id :id id)))
             (text (or (psi-emacs--event-data-get data
                                                  '(:text text :output output :delta delta :message message :result-text result-text))
                       ""))
             (stage (replace-regexp-in-string "^tool/" "" event)))
         (psi-emacs--reset-stream-watchdog psi-emacs--state)
         (psi-emacs--upsert-tool-row tool-id stage text)))
      ("ui/dialog-requested"
       (psi-emacs--handle-dialog-requested data))
      ("ui/widgets-updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-widgets psi-emacs--state)
               (psi-emacs--projection-sort-widgets
                (psi-emacs--projection-seq
                 (or (psi-emacs--event-data-get data '(:widgets widgets))
                     (psi-emacs--event-data-get data '(:items items))))))
         (psi-emacs--upsert-projection-block)))
      ("ui/status-updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-statuses psi-emacs--state)
               (psi-emacs--projection-sort-statuses
                (psi-emacs--projection-seq
                 (or (psi-emacs--event-data-get data '(:statuses statuses))
                     (psi-emacs--event-data-get data '(:items items))))))
         (psi-emacs--upsert-projection-block)))
      ("ui/notification"
       (psi-emacs--handle-notification-event data))
      ("footer/updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-footer psi-emacs--state)
               (psi-emacs--projection-footer-text data))
         (psi-emacs--upsert-projection-block)))
      (_ nil))))

(defun psi-emacs--buffer-modified-p ()
  "Return non-nil when current buffer has pending user edits."
  (buffer-modified-p))

(defun psi-emacs--confirm-reconnect-p ()
  "Return non-nil when reconnect should proceed."
  (or (not (psi-emacs--buffer-modified-p))
      (yes-or-no-p "Buffer has unsaved edits. Reconnect and clear buffer? ")))

(defun psi-emacs--reset-transcript-state ()
  "Clear transcript buffer and reset in-buffer rendering state.

Resets tool-output-view-mode to default `collapsed' so that after a
reconnect the user starts with the default collapsed view."
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when psi-emacs--state
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--clear-last-error psi-emacs--state)
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (clrhash (psi-emacs-state-tool-rows psi-emacs--state))
    (setf (psi-emacs-state-projection-widgets psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-statuses psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-footer psi-emacs--state) nil)
    (psi-emacs--clear-notification-lifecycle psi-emacs--state)
    (when (consp (psi-emacs-state-projection-range psi-emacs--state))
      (set-marker (car (psi-emacs-state-projection-range psi-emacs--state)) nil)
      (set-marker (cdr (psi-emacs-state-projection-range psi-emacs--state)) nil))
    (setf (psi-emacs-state-projection-range psi-emacs--state) nil)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'collapsed)
    (psi-emacs--set-run-state psi-emacs--state 'idle)
    (psi-emacs--refresh-header-line))
  (set-buffer-modified-p nil))

(defun psi-emacs-toggle-tool-output-view ()
  "Toggle global tool-output view mode between collapsed and expanded.

In collapsed mode only the header line of each tool row is shown.
In expanded mode the full body output of each tool row is shown.
The toggle applies to all existing tool rows and future tool rows.
This command is valid even when no tool rows exist."
  (interactive)
  (when psi-emacs--state
    (let* ((current (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (new-mode (if (eq current 'collapsed) 'expanded 'collapsed))
           (rows (psi-emacs-state-tool-rows psi-emacs--state)))
      (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) new-mode)
      ;; Re-render all existing tool rows with the new mode
      (maphash (lambda (tool-id row)
                 (let ((stage (plist-get row :stage))
                       (accumulated (plist-get row :accumulated-text))
                       (start (plist-get row :start))
                       (end (plist-get row :end)))
                   (when (and (markerp start)
                              (markerp end)
                              (marker-buffer start)
                              (marker-buffer end))
                     (let ((rendered (psi-emacs--render-tool-row
                                      tool-id stage accumulated new-mode)))
                       (save-excursion
                         (goto-char start)
                         (delete-region start end)
                         (insert rendered)
                         (set-marker end (point)))))))
               rows)
      (psi-emacs--refresh-header-line))))

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
        (delete-process psi-emacs--owned-process))
      (psi-emacs--reset-transcript-state)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'starting)
      (psi-emacs--set-run-state psi-emacs--state 'reconnecting)
      (psi-emacs--refresh-header-line)
      (psi-emacs--start-rpc-client (current-buffer)))))

(define-derived-mode psi-emacs-mode text-mode "psi"
  "Major mode for dedicated psi chat buffer.

The transcript remains editable in MVP."
  (setq-local buffer-read-only nil))

(let ((map psi-emacs-mode-map))
  (define-key map (kbd "RET") #'newline)
  (define-key map (kbd "C-c RET") #'psi-emacs-send-from-buffer)
  (define-key map (kbd "C-c C-q") #'psi-emacs-queue-from-buffer)
  (define-key map (kbd "C-c C-k") #'psi-emacs-abort)
  (define-key map (kbd "C-c C-r") #'psi-emacs-reconnect)
  (define-key map (kbd "C-c C-t") #'psi-emacs-toggle-tool-output-view))

(defun psi-emacs-open-buffer (&optional buffer-name)
  "Open and initialize dedicated psi chat buffer BUFFER-NAME.

Creates/uses one dedicated buffer with one owned subprocess and initializes
MVP frontend state boundaries."
  (let ((buffer (get-buffer-create (or buffer-name psi-emacs-buffer-name))))
    (with-current-buffer buffer
      (let ((mode (psi-emacs--preferred-major-mode)))
        (funcall mode))
      (unless (derived-mode-p 'psi-emacs-mode)
        (psi-emacs-mode))
      (psi-emacs--install-buffer-lifecycle-hooks)
      (unless psi-emacs--state
        (setq psi-emacs--state (psi-emacs--initialize-state nil))
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))
        (puthash buffer psi-emacs--state psi-emacs--state-by-buffer)
        (psi-emacs--refresh-header-line)
        (psi-emacs--start-rpc-client buffer)))
    buffer))

;;;###autoload
(defun psi-emacs-start ()
  "Start psi frontend in its dedicated process buffer."
  (interactive)
  (pop-to-buffer (psi-emacs-open-buffer)))

(defun psi-emacs-state-for-buffer (buffer)
  "Return frontend state tracked for BUFFER, or nil."
  (gethash buffer psi-emacs--state-by-buffer))

(provide 'psi)

;;; psi.el ends here
