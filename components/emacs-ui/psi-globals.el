;;; psi-globals.el --- Shared dynamic declarations for psi frontend modules  -*- lexical-binding: t; -*-

;;; Commentary:
;; Forward declarations used to keep byte-compilation warnings low across
;; split frontend modules.

;;; Code:

(require 'cl-lib)

(defgroup psi-emacs nil
  "psi Emacs frontend."
  :group 'applications)

(defcustom psi-emacs-command '("psi" "--rpc-edn")
  "Command used to start the owned psi rpc-edn subprocess."
  :type '(repeat string)
  :safe (lambda (value)
          (and (listp value)
               (cl-every #'stringp value)))
  :group 'psi-emacs)

(defcustom psi-emacs-buffer-name "*psi*"
  "Default dedicated buffer name for psi Emacs frontend."
  :type 'string
  :group 'psi-emacs)

(defcustom psi-emacs-working-directory nil
  "Working directory used to launch the psi subprocess.

When nil, psi uses the `default-directory' where `psi-emacs-start' (or
`psi-emacs-open-buffer') is invoked."
  :type '(choice (const :tag "Use invocation directory" nil)
                 directory)
  :group 'psi-emacs)

(defcustom psi-emacs-stream-timeout-seconds 600
  "Seconds without streaming progress before watchdog timeout triggers.

Used to detect stalled streaming runs and transition to deterministic recovery."
  :type 'number
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
  thinking-in-progress
  thinking-range
  thinking-archived-ranges
  tool-rows
  projection-widgets
  projection-widget-specs
  projection-widget-lstates
  projection-widget-data
  projection-statuses
  projection-footer
  projection-notifications
  projection-notification-seq
  projection-notification-timers
  regions
  region-seq
  active-assistant-id
  active-thinking-id
  input-separator-marker
  last-projection-start
  draft-anchor
  input-history
  input-history-index
  input-history-stash
  rpc-client
  tool-output-view-mode
  session-id
  session-phase
  session-is-streaming
  session-is-compacting
  session-pending-message-count
  session-retry-attempt
  session-interrupt-pending
  session-model-provider
  session-model-id
  session-model-reasoning
  session-thinking-level
  session-effective-reasoning-effort
  header-model-label
  status-session-line
  extension-command-names
  prompt-templates
  slash-completion-token
  transcript-hydrated?
  context-snapshot
  pending-frontend-action)

(defvar psi-emacs--state-by-buffer (make-hash-table :test #'eq)
  "Map dedicated buffers to their `psi-emacs-state'.")

(defvar-local psi-emacs--state nil
  "Buffer-local frontend state for psi chat buffers.")

(defvar-local psi-emacs--owned-process nil
  "Buffer-local owned subprocess for this dedicated psi buffer.")

(defvar psi-emacs--send-request-function)
(defvar psi-emacs--spawn-process-function)
(defvar psi-emacs--slash-command-handler-function)
(defvar psi-emacs-mode-map)
(declare-function psi-emacs--clear-thinking-line "psi-assistant-render")
(declare-function psi-emacs--archive-thinking-line "psi-assistant-render")
(declare-function psi-emacs--assistant-before-tool-event "psi-assistant-render")
(declare-function psi-emacs--assistant-start-marker "psi-assistant-render")
(declare-function psi-emacs--assistant-end-marker "psi-assistant-render")
(declare-function psi-emacs--clear-assistant-render-state "psi-assistant-render")
(declare-function psi-emacs--clear-thinking-render-state "psi-assistant-render" (&optional clear-archived))
(declare-function psi-emacs--assistant-delta "psi-assistant-render" (text))
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-find-by-property "psi-regions" (kind id &optional start end))
(declare-function psi-emacs--region-unregister "psi-regions" (kind id))
(declare-function psi-emacs--regions-clear "psi-regions")
(declare-function psi-emacs--next-region-id "psi-regions" (kind))
(declare-function psi-emacs--ensure-input-area "psi-compose")
(declare-function psi-emacs--ensure-startup-banner "psi-compose")
(declare-function psi-emacs--mark-region-read-only "psi-compose" (start end))
(declare-function psi-emacs--input-range "psi-compose")
(declare-function psi-emacs--draft-end-position "psi-compose")
(declare-function psi-emacs--draft-anchor-at-end-p "psi-compose")
(declare-function psi-emacs--set-draft-anchor-to-end "psi-compose")
(declare-function psi-emacs--input-separator-marker-cache "psi-compose")
(declare-function psi-emacs--input-separator-marker-valid-p "psi-compose")
(declare-function psi-emacs--input-separator-needs-refresh-p "psi-compose")
(declare-function psi-emacs--refresh-input-separator-line "psi-compose")
(declare-function psi-emacs--input-separator-current-width "psi-compose")
(declare-function psi-emacs--ensure-newline-before-append "psi-compose")
(declare-function psi-emacs--transcript-append-position "psi-compose")
(declare-function psi-emacs-previous-input "psi-compose")
(declare-function psi-emacs-next-input "psi-compose")
(declare-function psi-emacs-send-from-buffer "psi-compose" (prefix))
(declare-function psi-emacs-queue-from-buffer "psi-compose")
(declare-function psi-emacs-interrupt "psi-compose")
(declare-function psi-emacs-abort "psi-compose")
(declare-function psi-emacs--upsert-projection-block "psi-projection")
(declare-function psi-emacs--projection-widget-content-lines "psi-projection" (widget))
(declare-function psi-emacs--projection-seq "psi-projection" (value))
(declare-function psi-emacs--projection-footer-text "psi-projection" (data))
(declare-function psi-emacs--clear-notification-lifecycle "psi-projection" (state))
(declare-function psi-emacs--handle-notification-event "psi-projection" (data))
(declare-function psi-emacs--handle-window-configuration-change "psi-lifecycle")
(declare-function psi-emacs--request-frontend-exit "psi-session-commands")
(declare-function psi-emacs--request-switch-session-by-id "psi-session-commands" (state session-id))
(declare-function psi-emacs--resume-session-candidates "psi-session-commands" (sessions))
(declare-function psi-emacs--tree-session-candidates "psi-session-commands" (slots active-id))
(declare-function psi-emacs-set-model "psi-session-commands" (&optional provider model-id))
(declare-function psi-emacs-cycle-model-next "psi-session-commands")
(declare-function psi-emacs-cycle-model-prev "psi-session-commands")
(declare-function psi-emacs-set-thinking-level "psi-session-commands" (level))
(declare-function psi-emacs-cycle-thinking-level "psi-session-commands")
(declare-function psi-emacs--frame-messages-list "psi-session-commands" (frame))
(declare-function psi-emacs--default-handle-slash-command "psi-session-commands" (state message))
(declare-function psi-emacs--dispatch-compose-message "psi-session-commands" (message &optional behavior))
(declare-function psi-emacs--ordered-completing-read "psi-events" (prompt candidates &optional default))
(declare-function psi-emacs--event-data-get "psi-events" (data keys))
(declare-function psi-emacs--replay-session-messages "psi-session-commands" (messages))
(declare-function psi-emacs--session-tree-line-label "psi-events" (slot))
(declare-function psi-emacs--append-assistant-message "psi-compose" (text))
(declare-function psi-emacs--focus-input-area "psi-entry" (&optional buffer window))
(declare-function psi-emacs--handle-dialog-requested "psi-dialogs" (data))
(declare-function psi-emacs--reset-transcript-state "psi-lifecycle" (&optional preserve-tool-output-view-mode))

(declare-function psi-emacs--initialize-state "psi-lifecycle" (process))
(declare-function psi-emacs--start-rpc-client "psi-lifecycle" (buffer))
(declare-function psi-emacs--install-buffer-lifecycle-hooks "psi-lifecycle")
(declare-function psi-emacs-reconnect "psi-lifecycle")
(declare-function psi-emacs--default-spawn-process "psi-lifecycle" (command))
(declare-function psi-emacs--default-send-request "psi-lifecycle" (state op params &optional callback))
(declare-function psi-emacs--refresh-header-line "psi-run-state")
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--set-last-error "psi-run-state" (state message))
(declare-function psi-emacs--clear-last-error "psi-run-state" (state))
(declare-function psi-emacs--status-diagnostics-string "psi-run-state" (state))
(declare-function psi-emacs--disarm-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--reset-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs-toggle-tool-output-view "psi-tool-rows")

(declare-function markdown-mode "markdown-mode")
(declare-function markdown-fontify-region "markdown-mode" (begin end))

(provide 'psi-globals)

;;; psi-globals.el ends here
