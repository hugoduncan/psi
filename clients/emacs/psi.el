;;; psi.el --- Emacs process-buffer MVP scaffold  -*- lexical-binding: t; -*-

;; Copyright (C)

;;; Commentary:
;; MVP scaffold for a dedicated psi chat buffer with one owned subprocess.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
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
  pending-requests
  assistant-in-progress
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
   :process-state (if (process-live-p process) 'running 'starting)
   :transport-state 'disconnected
   :pending-requests (make-hash-table :test #'equal)
   :assistant-in-progress nil
   :tool-rows (make-hash-table :test #'equal)
   :draft-anchor nil
   :rpc-client nil))

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
  (when (process-live-p psi-emacs--owned-process)
    (delete-process psi-emacs--owned-process))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
    (set-marker (psi-emacs-state-draft-anchor psi-emacs--state) nil))
  (remhash (current-buffer) psi-emacs--state-by-buffer)
  (setq psi-emacs--owned-process nil)
  (setq psi-emacs--state nil))

(defun psi-emacs--install-buffer-lifecycle-hooks ()
  "Install local lifecycle hooks for dedicated psi buffer."
  (add-hook 'kill-buffer-hook #'psi-emacs--teardown-buffer nil t))

(defun psi-emacs--ensure-owned-process ()
  "Ensure current dedicated buffer has exactly one owned subprocess."
  (unless (process-live-p psi-emacs--owned-process)
    (setq psi-emacs--owned-process
          (funcall psi-emacs--spawn-process-function psi-emacs-command)))
  psi-emacs--owned-process)

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
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)))

(defun psi-emacs-reconnect ()
  "Reconnect frontend (implemented in task #96)."
  (interactive)
  (user-error "Reconnect flow not implemented yet"))

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
      (let ((process (psi-emacs--ensure-owned-process)))
        (setq psi-emacs--state (psi-emacs--initialize-state process))
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))
        (puthash buffer psi-emacs--state psi-emacs--state-by-buffer)))
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
