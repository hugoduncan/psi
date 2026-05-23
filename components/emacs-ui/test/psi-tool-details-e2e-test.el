;;; psi-tool-details-e2e-test.el --- End-to-end tool detail display check -*- lexical-binding: t; -*-

(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)

(defconst psi-tool-details-e2e-timeout-seconds 20
  "Maximum seconds to wait for each tool-details e2e step.")

(defun psi-tool-details-e2e--wait-for (pred timeout-seconds)
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

(defun psi-tool-details-e2e--buffer-snapshot (buffer)
  "Return plain-text snapshot for BUFFER."
  (if (buffer-live-p buffer)
      (with-current-buffer buffer
        (buffer-substring-no-properties (point-min) (point-max)))
    "<buffer-not-live>"))

(defun psi-tool-details-e2e--repo-local-command ()
  "Return a deterministic fake rpc-edn backend command for this e2e test."
  (let* ((script (make-temp-file "psi-tool-details-e2e-rpc-" nil ".py")))
    (with-temp-file script
      (insert
       "#!/usr/bin/env python3\n"
       "import re, sys\n"
       "\n"
       "TOOL_EVENT = r'''{:kind :event :event \"tool/result\" :data {:session-id \"e2e-session\" :tool-id \"tool-full-call\" :tool-name \"bash\" :arguments \"{\\\"command\\\":\\\"printf 'alpha beta gamma delta epsilon zeta eta theta iota kappa lambda'\\\",\\\"nested\\\":{\\\"keep\\\":[1,2,3]},\\\"reason\\\":\\\"e2e full call audit\\\"}\" :call-summary \"$ printf 'alpha beta…\" :result-text \"command output from fake backend\" :is-error false}}'''\n"
       "\n"
       "def frame_id(line):\n"
       "    m = re.search(r':id\\s+\\\"([^\\\"]+)\\\"', line)\n"
       "    return m.group(1) if m else 'unknown'\n"
       "\n"
       "def emit(text):\n"
       "    sys.stdout.write(text + '\\n')\n"
       "    sys.stdout.flush()\n"
       "\n"
       "for line in sys.stdin:\n"
       "    rid = frame_id(line)\n"
       "    if ':op \\\"handshake\\\"' in line:\n"
       "        emit('{:id \\\"%s\\\" :kind :response :op \\\"handshake\\\" :ok true :data {:server-info {:name \\\"psi-tool-details-e2e\\\" :protocol-version \\\"1.0\\\"}}}' % rid)\n"
       "    elif ':op \\\"subscribe\\\"' in line:\n"
       "        emit('{:id \\\"%s\\\" :kind :response :op \\\"subscribe\\\" :ok true :data {:subscribed true}}' % rid)\n"
       "    elif ':op \\\"get_messages\\\"' in line:\n"
       "        emit('{:id \\\"%s\\\" :kind :response :op \\\"get_messages\\\" :ok true :data {:messages []}}' % rid)\n"
       "        emit(TOOL_EVENT)\n"
       "    elif ':op \\\"quit\\\"' in line or ':op \\\"exit\\\"' in line:\n"
       "        emit('{:id \\\"%s\\\" :kind :response :op \\\"quit\\\" :ok true :data {}}' % rid)\n"
       "        break\n"
       "    else:\n"
       "        emit('{:id \\\"%s\\\" :kind :response :ok true :data {}}' % rid)\n"))
    (set-file-modes script #o700)
    (list "python3" script)))

(defun psi-tool-details-e2e-run ()
  "Run focused Emacs UI e2e scenario for expanded tool-call details."
  (let ((psi-emacs-command (psi-tool-details-e2e--repo-local-command))
        (psi-emacs-working-directory default-directory)
        (buffer nil)
        (ok t)
        (failure nil))
    (condition-case err
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-tool-details-e2e*"))

          (unless (psi-tool-details-e2e--wait-for
                   (lambda ()
                     (and (buffer-live-p buffer)
                          (with-current-buffer buffer
                            (and psi-emacs--state
                                 (eq (psi-emacs-state-transport-state psi-emacs--state)
                                     'ready)))))
                   psi-tool-details-e2e-timeout-seconds)
            (error "transport not ready within timeout; snapshot:\n%s"
                   (psi-tool-details-e2e--buffer-snapshot buffer)))

          (unless (psi-tool-details-e2e--wait-for
                   (lambda ()
                     (and (buffer-live-p buffer)
                          (with-current-buffer buffer
                            (string-match-p
                             (regexp-quote "$ printf 'alpha beta… success")
                             (buffer-string)))))
                   psi-tool-details-e2e-timeout-seconds)
            (error "did not observe collapsed tool row; snapshot:\n%s"
                   (psi-tool-details-e2e--buffer-snapshot buffer)))

          (with-current-buffer buffer
            (let ((collapsed (buffer-substring-no-properties (point-min) (point-max))))
              (when (string-match-p "Call" collapsed)
                (error "collapsed row unexpectedly shows Call section; snapshot:\n%s" collapsed))
              (when (string-match-p "command output from fake backend" collapsed)
                (error "collapsed row unexpectedly shows response; snapshot:\n%s" collapsed)))
            (call-interactively #'psi-emacs-toggle-tool-output-view))

          (unless (psi-tool-details-e2e--wait-for
                   (lambda ()
                     (and (buffer-live-p buffer)
                          (with-current-buffer buffer
                            (let ((text (buffer-substring-no-properties (point-min) (point-max))))
                              (and (string-match-p "Call" text)
                                   (string-match-p "Tool: bash" text)
                                   (string-match-p (regexp-quote "printf 'alpha beta gamma delta epsilon zeta eta theta iota kappa lambda'") text)
                                   (string-match-p "nested" text)
                                   (string-match-p "Response" text)
                                   (string-match-p "command output from fake backend" text))))))
                   psi-tool-details-e2e-timeout-seconds)
            (error "expanded tool details did not show full call and response; snapshot:\n%s"
                   (psi-tool-details-e2e--buffer-snapshot buffer)))

          (with-current-buffer buffer
            (call-interactively #'psi-emacs-toggle-tool-output-view)
            (let ((closed (buffer-substring-no-properties (point-min) (point-max))))
              (when (string-match-p "Call" closed)
                (error "toggled-closed row still shows Call section; snapshot:\n%s" closed))
              (when (string-match-p "command output from fake backend" closed)
                (error "toggled-closed row still shows response; snapshot:\n%s" closed))))

          (when (buffer-live-p buffer)
            (kill-buffer buffer)))
      (error
       (setq ok nil)
       (setq failure (error-message-string err))))

    (when (buffer-live-p buffer)
      (kill-buffer buffer))

    (if ok
        (progn
          (princ "psi-tool-details-e2e:ok\n")
          (kill-emacs 0))
      (progn
        (princ (format "psi-tool-details-e2e:fail:%s\n" (or failure "unknown failure")))
        (kill-emacs 1)))))

(provide 'psi-tool-details-e2e-test)

;;; psi-tool-details-e2e-test.el ends here
