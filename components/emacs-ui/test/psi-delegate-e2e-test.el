;;; psi-delegate-e2e-test.el --- End-to-end /delegate scenario for psi frontend -*- lexical-binding: t; -*-

(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)

(defconst psi-delegate-e2e-timeout-seconds 60
  "Maximum seconds to wait for each asynchronous /delegate e2e step.")

(defun psi-delegate-e2e--repo-local-command ()
  "Return a repo-local backend command so e2e exercises the current worktree."
  (let* ((repo-root default-directory)
         (psi-script (expand-file-name "bb/psi.clj" repo-root))
         (bb-bin (string-trim
                  (with-temp-buffer
                    (unless (zerop (call-process "bash" nil t nil "-lc" "which bb"))
                      (error "bb not found on PATH"))
                    (buffer-string)))))
    (list bb-bin psi-script "--" "--rpc-edn")))

(defun psi-delegate-e2e--wait-for (pred timeout-seconds)
  "Poll PRED until non-nil or TIMEOUT-SECONDS elapse."
  (let ((deadline (+ (float-time) timeout-seconds))
        (done nil))
    (while (and (not done)
                (< (float-time) deadline))
      (setq done (condition-case _
                     (funcall pred)
                   (error nil)))
      (unless done
        (accept-process-output nil 0.05)
        (sleep-for 0.05)))
    done))

(defun psi-delegate-e2e--buffer-snapshot (buffer)
  "Return plain-text snapshot for BUFFER."
  (if (buffer-live-p buffer)
      (with-current-buffer buffer
        (buffer-substring-no-properties (point-min) (point-max)))
    "<buffer-not-live>"))

(defun psi-delegate-e2e--delegate-bridge-complete-p (buffer)
  "Return non-nil when BUFFER shows ack, user bridge, and a later assistant result line."
  (and (buffer-live-p buffer)
       (with-current-buffer buffer
         (let* ((buf (buffer-string))
                (lines (split-string buf "\n"))
                (bridge-index
                 (cl-position-if
                  (lambda (line)
                    (string-match-p "User: Workflow run lambda-compiler-.* result:" line))
                  lines)))
           (and (string-match-p
                 "User: Workflow run lambda-compiler-.* result:"
                 buf)
                (string-match-p
                 "ψ: Delegated to lambda-compiler — run "
                 buf)
                bridge-index
                (cl-loop for line in (nthcdr (1+ bridge-index) lines)
                         thereis (and (string-match-p "^ψ: " line)
                                      (not (string-match-p
                                            "^ψ: Delegated to lambda-compiler — run "
                                            line)))))))))

(defun psi-delegate-e2e-run ()
  "Run focused Emacs UI end-to-end /delegate scenario."
  (let ((psi-emacs-command (psi-delegate-e2e--repo-local-command))
        (psi-emacs-working-directory default-directory)
        (buffer nil)
        (ok t)
        (failure nil))
    (condition-case err
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-delegate-e2e*"))

          (unless (psi-delegate-e2e--wait-for
                   (lambda ()
                     (and (buffer-live-p buffer)
                          (with-current-buffer buffer
                            (and psi-emacs--state
                                 (eq (psi-emacs-state-transport-state psi-emacs--state)
                                     'ready)))))
                   psi-delegate-e2e-timeout-seconds)
            (error "transport not ready within timeout"))

          (with-current-buffer buffer
            (let ((inhibit-read-only t))
              (psi-emacs--replace-input-text
               "/delegate lambda-compiler munera tracks work, mementum tracks state and knowledge"))
            (psi-emacs-send-from-buffer nil))

          (unless (psi-delegate-e2e--wait-for
                   (lambda ()
                     (and (buffer-live-p buffer)
                          (with-current-buffer buffer
                            (string-match-p
                             "Delegated to lambda-compiler — run "
                             (buffer-string)))))
                   psi-delegate-e2e-timeout-seconds)
            (error "did not observe /delegate ack; snapshot:\n%s"
                   (psi-delegate-e2e--buffer-snapshot buffer)))

          (unless (psi-delegate-e2e--wait-for
                   (lambda ()
                     (psi-delegate-e2e--delegate-bridge-complete-p buffer))
                   psi-delegate-e2e-timeout-seconds)
            (error "did not observe /delegate bridge completion; snapshot:\n%s"
                   (psi-delegate-e2e--buffer-snapshot buffer)))

          (with-current-buffer buffer
            (when (string-match-p
                   (regexp-quote "(workflow context bridge)")
                   (buffer-string))
              (error "visible bridge filler rendered; snapshot:\n%s"
                     (buffer-string))))

          (when (buffer-live-p buffer)
            (with-current-buffer buffer
              (let ((inhibit-read-only t))
                (psi-emacs--replace-input-text "/quit"))
              (psi-emacs-send-from-buffer nil)))

          (unless (psi-delegate-e2e--wait-for
                   (lambda () (not (buffer-live-p buffer)))
                   5)
            (error "buffer did not close after /quit")))
      (error
       (setq ok nil)
       (setq failure (error-message-string err))))

    (when (buffer-live-p buffer)
      (kill-buffer buffer))

    (if ok
        (progn
          (princ "psi-delegate-e2e:ok\n")
          (kill-emacs 0))
      (progn
        (princ (format "psi-delegate-e2e:fail:%s\n" (or failure "unknown failure")))
        (kill-emacs 1)))))

(provide 'psi-delegate-e2e-test)

;;; psi-delegate-e2e-test.el ends here
