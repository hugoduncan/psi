;;; psi-entry.el --- Buffer entry points for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted top-level buffer start/open/state API used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)
(require 'psi-widget-projection)

(defvar psi-emacs-buffer-name)
(defvar psi-emacs-working-directory)

(declare-function psi-emacs--ensure-input-area "psi-compose")
(declare-function psi-emacs--draft-end-position "psi-compose")
(declare-function psi-emacs--preferred-major-mode "psi-mode")
(declare-function psi-emacs-mode "psi-mode")
(declare-function psi-emacs--install-buffer-lifecycle-hooks "psi-lifecycle")
(declare-function psi-emacs--install-input-read-only-guard "psi-compose")
(declare-function psi-emacs--ensure-startup-banner "psi-compose")
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--initialize-state "psi-lifecycle" (process))
(declare-function psi-emacs--refresh-header-line "psi-run-state")
(declare-function psi-emacs--start-rpc-client "psi-lifecycle" (buffer))

(defun psi-emacs--seed-connecting-footer ()
  "Render a deterministic pre-handshake footer placeholder."
  (when psi-emacs--state
    (setf (psi-emacs-state-projection-footer psi-emacs--state) "connecting...")
    (when (fboundp 'psi-emacs--upsert-projection-block)
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--focus-input-area (&optional buffer window)
  "Move point to compose input area for BUFFER and sync visible window point.

When WINDOW is provided and displays BUFFER, it is preferred as the primary
window-point target."
  (let ((buffer* (or buffer (current-buffer))))
    (when (buffer-live-p buffer*)
      (with-current-buffer buffer*
        (when psi-emacs--state
          (psi-emacs--ensure-input-area)
          (goto-char (psi-emacs--draft-end-position))
          (let* ((pos (point))
                 (primary-window
                  (cond
                   ((and (window-live-p window)
                         (eq (window-buffer window) buffer*))
                    window)
                   ((and (window-live-p (selected-window))
                         (eq (window-buffer (selected-window)) buffer*))
                    (selected-window))
                   (t
                    (get-buffer-window buffer* t)))))
            (when (window-live-p primary-window)
              (set-window-point primary-window pos))
            ;; Keep any additional visible windows on this frame aligned.
            (dolist (win (get-buffer-window-list buffer* nil nil))
              (when (window-live-p win)
                (set-window-point win pos)))))))))

(defun psi-emacs--show-connecting-affordances (&optional buffer window)
  "Ensure transient connecting footer and input focus are visible.

Used by startup and session rehydrate flows (/new, /resume) so users keep
input/footer affordances while waiting for canonical backend updates."
  (let ((buffer* (or buffer (current-buffer))))
    (when (buffer-live-p buffer*)
      (with-current-buffer buffer*
        (when psi-emacs--state
          (psi-emacs--seed-connecting-footer)
          (psi-emacs--focus-input-area buffer* window))))))

(defun psi-emacs--normalize-directory (dir)
  "Return DIR as absolute directory path, or nil when unavailable."
  (when (and (stringp dir)
             (> (length dir) 0)
             (file-directory-p dir))
    (file-name-as-directory (expand-file-name dir))))

(defun psi-emacs--resolve-working-directory (&optional start-directory)
  "Return working directory for psi subprocess startup.

Prefer explicit `psi-emacs-working-directory`; otherwise use
START-DIRECTORY (the directory where `psi-emacs-start' was invoked)."
  (or (psi-emacs--normalize-directory psi-emacs-working-directory)
      (psi-emacs--normalize-directory start-directory)
      (psi-emacs--normalize-directory default-directory)))

(defun psi-emacs--entry-project-root-directory (&optional start-directory)
  "Return canonical project root for START-DIRECTORY, or nil.

Unlike helper functions used for display paths, this is strict: it only
returns a root when Emacs project discovery finds one."
  (let* ((base (psi-emacs--normalize-directory
                (or start-directory default-directory)))
         (project-root
          (when (and base (fboundp 'project-current))
            (let ((proj (ignore-errors (project-current nil base))))
              (cond
               ((and proj (fboundp 'project-root))
                (ignore-errors (project-root proj)))
               ((and proj (fboundp 'project-roots))
                (car (ignore-errors (project-roots proj))))
               (t nil))))))
    (psi-emacs--normalize-directory project-root)))

(defun psi-emacs--project-buffer-base-name (&optional project-root)
  "Return project-scoped psi buffer base name for PROJECT-ROOT."
  (let* ((root (or (and (stringp project-root)
                        (> (length project-root) 0)
                        (file-name-as-directory (expand-file-name project-root)))
                   (psi-emacs--entry-project-root-directory default-directory)))
         (project-name (and root
                            (file-name-nondirectory
                             (directory-file-name root)))))
    (format "*psi:%s*" (or project-name "project"))))

(defun psi-emacs--project-buffer-name-for-prefix (base-name prefix)
  "Return project-scoped buffer name from BASE-NAME and PREFIX.

No PREFIX reuses BASE-NAME.
Plain `C-u' forces a fresh generated name.
Numeric prefix selects slot N (`N<=1' => BASE-NAME, else `BASE-NAME<N>')."
  (cond
   ((null prefix)
    base-name)
   ((equal prefix '(4))
    (generate-new-buffer-name base-name))
   (t
    (let ((n (prefix-numeric-value prefix)))
      (if (<= n 1)
          base-name
        (format "%s<%d>" base-name n))))))

(defun psi-emacs-open-buffer (&optional buffer-name start-directory)
  "Open and initialize dedicated psi chat buffer BUFFER-NAME.

START-DIRECTORY sets the startup cwd for the owned subprocess when
`psi-emacs-working-directory' is nil.

Creates/uses one dedicated buffer with one owned subprocess and initializes
frontend state boundaries."
  (let* ((start-directory* (or start-directory default-directory))
         ;; Capture buffer-local command value from the calling buffer before
         ;; switching context; psi-emacs-command may be set buffer-locally in
         ;; project buffers and would otherwise resolve to the psi buffer's value.
         (captured-command psi-emacs-command)
         (buffer (get-buffer-create (or buffer-name psi-emacs-buffer-name))))
    (with-current-buffer buffer
      (unless (derived-mode-p 'psi-emacs-mode)
        (let ((mode (psi-emacs--preferred-major-mode)))
          (funcall mode))
        (psi-emacs-mode))
      (psi-emacs--install-buffer-lifecycle-hooks)
      (psi-emacs--install-input-read-only-guard)
      ;; Propagate caller's buffer-local command into the psi buffer so that
      ;; psi-emacs--start-rpc-client reads the correct value when it runs here.
      (setq-local psi-emacs-command captured-command)
      (setq-local default-directory
                  (psi-emacs--resolve-working-directory start-directory*))
      (if psi-emacs--state
          (let* ((state psi-emacs--state)
                 (process (psi-emacs-state-process state))
                 (process-live? (process-live-p process))
                 (client (psi-emacs-state-rpc-client state))
                 (transport-state (psi-emacs-state-transport-state state))
                 (client-live-and-connected?
                  (and client
                       process-live?
                       (memq transport-state '(handshaking ready)))))
            (unless (markerp (psi-emacs-state-draft-anchor state))
              (setf (psi-emacs-state-draft-anchor state)
                    (copy-marker (point-max) nil)))
            (psi-emacs--ensure-startup-banner)
            (psi-emacs--focus-input-area)
            (when (and (not (eq transport-state 'ready))
                       (null (psi-emacs-state-projection-footer state))
                       (null (psi-emacs--region-bounds 'projection 'main)))
              (psi-emacs--show-connecting-affordances buffer))
            ;; Recover from stale/disconnected buffer state (e.g. after code
            ;; reload or failed startup handshake) by restarting transport.
            (unless client-live-and-connected?
              (when process-live?
                ;; This restart is intentional; suppress subprocess-exit error
                ;; surfacing from the transport sentinel.
                (process-put process 'psi-rpc-stop-requested t)
                (delete-process process))
              (psi-emacs--start-rpc-client buffer)))
        (setq psi-emacs--state (psi-emacs--initialize-state nil))
        (psi-emacs--ensure-startup-banner)
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))
        (psi-emacs--show-connecting-affordances buffer)
        (puthash buffer psi-emacs--state psi-emacs--state-by-buffer)
        (psi-emacs--refresh-header-line)
        (psi-emacs--start-rpc-client buffer)))
    (psi-widget-projection-setup)
    buffer))

;;;###autoload (autoload 'psi-emacs-start "psi" "Start a dedicated psi buffer/session." t)
(defun psi-emacs-start (&optional prefix)
  "Start psi frontend in its dedicated process buffer.

With PREFIX, create and switch to a fresh dedicated buffer name."
  (interactive "P")
  (let* ((buffer-name (when prefix
                        (generate-new-buffer-name psi-emacs-buffer-name)))
         (buffer (psi-emacs-open-buffer buffer-name default-directory))
         (window (pop-to-buffer buffer)))
    (psi-emacs--focus-input-area buffer window)
    buffer))

;;;###autoload (autoload 'psi-emacs-project "psi" "Start or switch to a project-scoped psi buffer/session." t)
(defun psi-emacs-project (&optional prefix)
  "Start psi frontend for the current project.

Buffer naming style is project-scoped: `*psi:<project>*'.

No PREFIX reuses canonical project buffer.
Plain `C-u' forces a fresh project buffer name.
Numeric prefix (`C-u N') opens project buffer slot N."
  (interactive "P")
  (let* ((project-root (or (psi-emacs--entry-project-root-directory default-directory)
                           (user-error "No project found for current directory")))
         (buffer
          (psi-emacs-open-buffer
           (psi-emacs--project-buffer-name-for-prefix
            (psi-emacs--project-buffer-base-name project-root)
            prefix)
           project-root))
         (window (pop-to-buffer buffer)))
    (psi-emacs--focus-input-area buffer window)
    buffer))

(defun psi-emacs-state-for-buffer (buffer)
  "Return frontend state tracked for BUFFER, or nil."
  (gethash buffer psi-emacs--state-by-buffer))

(provide 'psi-entry)

;;; psi-entry.el ends here
