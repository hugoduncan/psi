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

(cl-defstruct psi-emacs-state
  process
  process-state
  transport-state
  assistant-in-progress
  assistant-range
  tool-rows
  draft-anchor
  rpc-client)

(defvar psi-emacs--spawn-process-function #'psi-emacs--default-spawn-process
  "Function used to spawn a psi subprocess.

The function receives one argument COMMAND (list of strings) and must return
an Emacs process object.")

(defvar psi-emacs--send-request-function #'psi-emacs--default-send-request
  "Function used by frontend commands to send RPC requests.

Called as (STATE OP PARAMS CALLBACK).")

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
   :assistant-in-progress nil
   :assistant-range nil
   :tool-rows (make-hash-table :test #'equal)
   :draft-anchor nil
   :rpc-client nil))

(defun psi-emacs--status-string (state)
  "Return minimal status string for STATE."
  (format "psi [%s/%s]"
          (or (psi-emacs-state-transport-state state) 'unknown)
          (or (psi-emacs-state-process-state state) 'unknown)))

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
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--on-rpc-error (buffer code message-text frame)
  "Surface RPC error in minibuffer only for BUFFER."
  (ignore frame)
  (when (buffer-live-p buffer)
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
        (psi-rpc-start! client psi-emacs--spawn-process-function psi-emacs-command)
        (setf (psi-emacs-state-process psi-emacs--state) (psi-rpc-client-process client))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--default-spawn-process (command)
  "Spawn psi subprocess from COMMAND.

COMMAND is a list suitable for `make-process'."
  (make-process
   :name "psi-rpc-edn"
   :command command
   :buffer nil
   :noquery t
   :connection-type 'pipe))

(defun psi-emacs--default-send-request (state op params &optional callback)
  "Send OP with PARAMS using STATE rpc client, if available."
  (when-let ((client (psi-emacs-state-rpc-client state)))
    (psi-rpc-send-request! client op params callback)))

(defun psi-emacs--teardown-buffer ()
  "Stop owned subprocess and clear local/global frontend state for current buffer."
  (when (and psi-emacs--state
             (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
    (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
  (when (process-live-p psi-emacs--owned-process)
    (delete-process psi-emacs--owned-process))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
    (set-marker (psi-emacs-state-draft-anchor psi-emacs--state) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-assistant-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-assistant-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-assistant-range psi-emacs--state)) nil))
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
  (when psi-emacs--state
    (let ((text (psi-emacs-state-assistant-in-progress psi-emacs--state)))
      (and (stringp text)
           (not (string-empty-p text))))))

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

(defun psi-emacs--dispatch-request (op params &optional callback)
  "Dispatch OP PARAMS CALLBACK through configured request function."
  (when psi-emacs--state
    (funcall psi-emacs--send-request-function psi-emacs--state op params callback)))

(defun psi-emacs-send-from-buffer (prefix)
  "Send composed text using canonical send semantics.

With PREFIX while streaming, queue override is used.
Without PREFIX while streaming, steer is used.
When idle, sends as normal prompt."
  (interactive "P")
  (let ((message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (psi-emacs--dispatch-request
         "prompt_while_streaming"
         `((:message . ,message)
           (:behavior . ,(if prefix "queue" "steer"))))
      (psi-emacs--dispatch-request "prompt" `((:message . ,message))))))

(defun psi-emacs-queue-from-buffer ()
  "Queue composed text while streaming; fallback to normal send when idle."
  (interactive)
  (let ((message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (psi-emacs--dispatch-request
         "prompt_while_streaming"
         `((:message . ,message)
           (:behavior . "queue")))
      (psi-emacs--dispatch-request "prompt" `((:message . ,message))))))

(defun psi-emacs-abort ()
  "Abort active streaming request via RPC and transition to non-streaming UI state."
  (interactive)
  (psi-emacs--dispatch-request "abort" nil)
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)))

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
    (let ((range (psi-emacs-state-assistant-range psi-emacs--state)))
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
                  (cons start end))))))))

(defun psi-emacs--assistant-delta (text)
  "Apply assistant delta TEXT to the in-progress assistant block."
  (when psi-emacs--state
    (let ((next (concat (or (psi-emacs-state-assistant-in-progress psi-emacs--state) "")
                        (or text ""))))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) next)
      (psi-emacs--set-assistant-line next))))

(defun psi-emacs--assistant-finalize (text)
  "Finalize assistant block with TEXT and clear in-progress state."
  (when psi-emacs--state
    (let ((final (or text (psi-emacs-state-assistant-in-progress psi-emacs--state) "")))
      (psi-emacs--set-assistant-line final)
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
      (setf (psi-emacs-state-assistant-range psi-emacs--state) nil))))

(defun psi-emacs--event-data-get (data keys)
  "Return first non-nil value from DATA for KEYS.

DATA is expected to be an alist map."
  (catch 'hit
    (dolist (k keys)
      (let ((v (alist-get k data nil nil #'equal)))
        (when v
          (throw 'hit v))))
    nil))

(defun psi-emacs--tool-row-string (tool-id stage text)
  "Build tool row string for TOOL-ID at STAGE with TEXT.

ANSI sequences in TEXT are converted to Emacs faces."
  (let ((prefix (format "Tool[%s] %s: " tool-id stage))
        (body (psi-emacs--ansi-to-face text)))
    (concat prefix body "\n")))

(defun psi-emacs--upsert-tool-row (tool-id stage text)
  "Create or update TOOL-ID row for lifecycle STAGE with TEXT."
  (when (and psi-emacs--state tool-id)
    (let* ((rows (psi-emacs-state-tool-rows psi-emacs--state))
           (row (gethash tool-id rows))
           (start (plist-get row :start))
           (end (plist-get row :end))
           (rendered (psi-emacs--tool-row-string tool-id stage (or text ""))))
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
                                   :start new-start
                                   :end new-end)
                     rows)))))))

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
        (or (psi-emacs--event-data-get data '(:text text :message message)) "")))
      ((or "tool/start" "tool/delta" "tool/executing" "tool/update" "tool/result")
       (let ((tool-id (psi-emacs--event-data-get data
                                                 '(:toolCallId toolCallId :tool-call-id tool-call-id :id id)))
             (text (or (psi-emacs--event-data-get data
                                                  '(:text text :output output :delta delta :message message))
                       ""))
             (stage (replace-regexp-in-string "^tool/" "" event)))
         (psi-emacs--upsert-tool-row tool-id stage text)))
      (_ nil))))

(defun psi-emacs--buffer-modified-p ()
  "Return non-nil when current buffer has pending user edits."
  (buffer-modified-p))

(defun psi-emacs--confirm-reconnect-p ()
  "Return non-nil when reconnect should proceed."
  (or (not (psi-emacs--buffer-modified-p))
      (yes-or-no-p "Buffer has unsaved edits. Reconnect and clear buffer? ")))

(defun psi-emacs--reset-transcript-state ()
  "Clear transcript buffer and reset in-buffer rendering state."  
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (clrhash (psi-emacs-state-tool-rows psi-emacs--state))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil)))
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
        (delete-process psi-emacs--owned-process))
      (psi-emacs--reset-transcript-state)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'starting)
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
  (define-key map (kbd "C-c C-r") #'psi-emacs-reconnect))

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
