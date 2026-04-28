;;; psi-mode.el --- Major mode + keymap for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted mode bootstrap helpers and keybindings used by psi.el.

;;; Code:

(require 'psi-globals)
(require 'psi-completion)

(defvar psi-emacs-mode-map)

(declare-function markdown-mode "markdown-mode")
(declare-function psi-emacs--install-input-read-only-guard "psi-compose")
(declare-function psi-emacs-previous-input "psi-compose")
(declare-function psi-emacs-next-input "psi-compose")
(declare-function psi-emacs-search-input-history "psi-compose")
(declare-function psi-emacs-send-from-buffer "psi-compose" (prefix))
(declare-function psi-emacs-queue-from-buffer "psi-compose")
(declare-function psi-emacs-interrupt "psi-compose")
(declare-function psi-emacs-abort "psi-compose")
(declare-function psi-emacs-reconnect "psi-lifecycle")
(declare-function psi-emacs-toggle-tool-output-view "psi-tool-rows")
(declare-function psi-emacs-set-model "psi-session-commands" (&optional provider model-id))
(declare-function psi-emacs-cycle-model-next "psi-session-commands")
(declare-function psi-emacs-cycle-model-prev "psi-session-commands")
(declare-function psi-emacs-set-thinking-level "psi-session-commands" (level))
(declare-function psi-emacs-cycle-thinking-level "psi-session-commands")

(defface psi-emacs-user-prompt-face
  '((t :inherit font-lock-keyword-face :weight bold))
  "Face for `User:' transcript prompt prefixes."
  :group 'psi-emacs)

(defface psi-emacs-assistant-reply-face
  '((t :inherit font-lock-function-name-face :weight ultra-bold))
  "Face for `ψ:' transcript reply prefixes."
  :group 'psi-emacs)

(defface psi-emacs-assistant-thinking-face
  '((t :inherit shadow :slant italic))
  "Face for incremental assistant thinking transcript lines."
  :group 'psi-emacs)

(defface psi-emacs-tool-call-face
  '((t :inherit font-lock-builtin-face))
  "Face for tool call summary text."
  :group 'psi-emacs)

(defface psi-emacs-tool-pending-face
  '((t :inherit warning))
  "Face for pending tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-running-face
  '((t :inherit mode-line-emphasis))
  "Face for running tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-success-face
  '((t :inherit success))
  "Face for successful tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-error-face
  '((t :inherit error))
  "Face for errored tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-output-face
  '((t :inherit shadow))
  "Face for de-emphasized tool output body text."
  :group 'psi-emacs)

(defface psi-emacs-error-line-face
  '((t :inherit error))
  "Face for persistent transcript error lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-separator-face
  '((t :inherit shadow))
  "Face for projection separator lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-heading-face
  '((t :inherit font-lock-doc-face :weight bold))
  "Face for projection section headings."
  :group 'psi-emacs)

(defface psi-emacs-projection-extension-id-face
  '((t :inherit font-lock-constant-face))
  "Face for projection extension-id labels."
  :group 'psi-emacs)

(defface psi-emacs-projection-widget-face
  '((t :inherit default))
  "Face for projection widget content lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-face
  '((t :inherit shadow))
  "Face for projection footer summary lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-path-face
  '((t :inherit font-lock-string-face))
  "Face for projection footer path lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-stats-face
  '((t :inherit shadow))
  "Face for projection footer stats lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-status-face
  '((t :inherit mode-line-emphasis))
  "Face for projection footer status lines."
  :group 'psi-emacs)

(defface psi-emacs-session-current-face
  '((t :inherit mode-line-emphasis))
  "Face for current-session markers."
  :group 'psi-emacs)

(defface psi-emacs-session-waiting-face
  '((t :inherit success :weight bold))
  "Face for waiting sessions that need user attention."
  :group 'psi-emacs)

(defface psi-emacs-session-running-face
  '((t :inherit shadow))
  "Face for running session state badges."
  :group 'psi-emacs)

(defface psi-emacs-session-retrying-face
  '((t :inherit warning))
  "Face for retrying session state badges."
  :group 'psi-emacs)

(defface psi-emacs-session-compacting-face
  '((t :inherit shadow :slant italic))
  "Face for compacting session state badges."
  :group 'psi-emacs)

(defun psi-emacs--markdown-mode-available-p ()
  "Return non-nil when `markdown-mode' is available."
  (fboundp 'markdown-mode))

(defun psi-emacs--preferred-major-mode ()
  "Return major mode symbol for dedicated psi buffer.

Uses `text-mode' so markdown fontification can be scoped to assistant replies
instead of the entire buffer."
  'text-mode)

(defun psi-emacs--install-mode-keybindings (&optional map)
  "Install canonical psi compose/navigation keybindings into MAP."
  (let ((map* (or map psi-emacs-mode-map)))
    (define-key map* (kbd "RET") #'newline)
    (define-key map* (kbd "M-p") #'psi-emacs-previous-input)
    (define-key map* (kbd "M-n") #'psi-emacs-next-input)
    (define-key map* (kbd "M-r") #'psi-emacs-search-input-history)
    (define-key map* (kbd "C-c RET") #'psi-emacs-send-from-buffer)
    (define-key map* (kbd "C-c C-q") #'psi-emacs-queue-from-buffer)
    (define-key map* (kbd "C-c C-c") #'psi-emacs-interrupt)
    (define-key map* (kbd "C-c C-k") #'psi-emacs-abort)
    (define-key map* (kbd "C-c C-r") #'psi-emacs-reconnect)
    (define-key map* (kbd "C-c C-t") #'psi-emacs-toggle-tool-output-view)

    ;; Model/thinking controls.
    (define-key map* (kbd "C-c m m") #'psi-emacs-set-model)
    (define-key map* (kbd "C-c m n") #'psi-emacs-cycle-model-next)
    (define-key map* (kbd "C-c m p") #'psi-emacs-cycle-model-prev)
    (define-key map* (kbd "C-c m t") #'psi-emacs-set-thinking-level)
    (define-key map* (kbd "C-c m c") #'psi-emacs-cycle-thinking-level)))

(define-derived-mode psi-emacs-mode text-mode "psi"
  "Major mode for dedicated psi chat buffer.

Uses `text-mode' so markdown fontification can be applied selectively to
finalized assistant content only."
  (setq-local buffer-read-only nil)
  (setq-local read-only-inhibit-point-motion t)
  ;; Reinstall keybindings and edit guard on every mode activation to self-heal
  ;; stale local state in long-lived Emacs sessions.
  (psi-emacs--install-mode-keybindings)
  (when (fboundp 'psi-emacs--install-input-read-only-guard)
    (psi-emacs--install-input-read-only-guard))
  (psi-emacs--install-prompt-capf))

(psi-emacs--install-mode-keybindings)

(provide 'psi-mode)

;;; psi-mode.el ends here
