;;; psi-streaming-runtime-test.el --- Runtime/header/reconnect streaming tests  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-rpc)
(require 'psi-test-support)

(ert-deftest psi-empty-assistant-message-archives-thinking-not-clears ()
  "Empty assistant/message (tool-only turn) must archive thinking, not delete it.

Regression: the empty-message path called clear-thinking-line which deleted
the thinking block from the buffer.  Thinking is transcript and must survive."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Thinking arrives, then a tool-only turn (no text in assistant/message)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "reasoning")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:result-text . "ok")))))
          ;; Empty assistant/message — no text, no streamed text
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ())))
          (let ((content (buffer-substring-no-properties (point-min) (point-max))))
            ;; Thinking must still be visible
            (should (string-match-p "· reasoning" content))
            ;; Thinking archived (not in-progress)
            (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
            (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-output-converts-ansi-to-face-and-removes-escapes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    ;; Use expanded mode so the body text (with ANSI) is rendered in the buffer
    (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'expanded)
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result")
             (:data . ((:tool-id . "t-ansi") (:text . "\u001b[31mERR\u001b[0m")))))
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (equal "t-ansi success\nERR\n" text)))
          (goto-char (point-min))
          (search-forward "ERR")
          (let ((face (get-text-property (1- (point)) 'face)))
            (should face)
            (should-not (eq face 'default))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-row-header-prefers-tool-call-details-over-tracking-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")
                       (:arguments . "{\"command\":\"echo hi\"}")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")
                       (:result-text . "ok")
                       (:is-error . nil)))))
          (let ((buf (buffer-string)))
            (should (equal "$ echo hi success\n" buf))
            (should-not (string-match-p
                         (regexp-quote "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                         buf))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-summary-uses-project-relative-paths-for-file-tools ()
  (let* ((project-root (make-temp-file "psi-emacs-proj-" t))
         (default-directory (file-name-as-directory project-root))
         (read-path (expand-file-name "src/read.clj" project-root))
         (edit-path (expand-file-name "src/edit.clj" project-root))
         (write-path (expand-file-name "src/write.clj" project-root)))
    (unwind-protect
        (progn
          (should (equal "read src/read.clj"
                         (psi-emacs--tool-summary "read" `((:path . ,read-path)) nil "t-read")))
          (should (equal "edit src/edit.clj"
                         (psi-emacs--tool-summary "edit" `((:path . ,edit-path)) nil "t-edit")))
          (should (equal "write src/write.clj"
                         (psi-emacs--tool-summary "write" `((:path . ,write-path)) nil "t-write"))))
      (ignore-errors (delete-directory project-root t)))))

(ert-deftest psi-tool-summary-read-includes-offset-limit-line-range ()
  (should (equal "read src/core.clj:10:20"
                 (psi-emacs--tool-summary "read"
                                          '((:path . "src/core.clj")
                                            (:offset . 10)
                                            (:limit . 11))
                                          nil
                                          "t-read")))
  (should (equal "read src/core.clj:42"
                 (psi-emacs--tool-summary "read"
                                          '((:path . "src/core.clj")
                                            (:offset . 42))
                                          nil
                                          "t-read"))))

(ert-deftest psi-tool-summary-edit-includes-first-changed-line-range ()
  (should (equal "edit src/core.clj:7:9"
                 (psi-emacs--tool-summary "edit"
                                          '((:path . "src/core.clj")
                                            (:oldText . "a\nb\nc"))
                                          nil
                                          "t-edit"
                                          '((:firstChangedLine . 7)))))
  (should (equal "edit src/core.clj:7"
                 (psi-emacs--tool-summary "edit"
                                          '((:path . "src/core.clj"))
                                          nil
                                          "t-edit"
                                          '((:firstChangedLine . 7))))))

(ert-deftest psi-header-line-updates-from-rpc-state-transitions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'handshaking)))
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (string= "psi [handshaking/running/idle] tools:collapsed" header-line-format))
      (setf (psi-rpc-client-transport-state client) 'ready)
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format)))))

(ert-deftest psi-rpc-state-change-ignores-stale-client-updates ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((active-client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (stale-client (psi-rpc-make-client :process-state 'stopped :transport-state 'disconnected)))
      (setf (psi-emacs-state-rpc-client psi-emacs--state) active-client)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'running)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
      (psi-emacs--on-rpc-state-change (current-buffer) stale-client)
      (should (eq active-client (psi-emacs-state-rpc-client psi-emacs--state)))
      (should (eq 'running (psi-emacs-state-process-state psi-emacs--state)))
      (should (eq 'ready (psi-emacs-state-transport-state psi-emacs--state))))))

(ert-deftest psi-reconnecting-run-state-transitions-to-idle-when-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :user) (:text . "engage nucleus"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
      (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format))
      (should (equal '("get_messages" "query_eql")
                     (mapcar #'car (nreverse rpc-calls))))
      (should (string-match-p "User: engage nucleus" (buffer-string))))))

(ert-deftest psi-initial-transcript-hydration-runs-once-per-ready-lifecycle ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "boot ok"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client)
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      (should (equal '("get_messages" "query_eql")
                     (mapcar #'car (nreverse rpc-calls))))
      (should (= 1 (how-many "^ψ: boot ok$" (point-min) (point-max)))))))

(ert-deftest psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages ()
  "AC: initial hydration keeps point in the compose input area when messages are replayed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready)))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op _params &optional callback)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "engage nucleus"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client)))
    (should (= (point) (psi-emacs--draft-end-position)))
    (should (= 1 (how-many "engage nucleus" (point-min) (point-max))))))

(ert-deftest psi-initial-transcript-hydration-no-scroll-when-no-messages ()
  "AC: initial hydration with empty messages does not move point."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (let ((initial-point (point))
          (client (psi-rpc-make-client :process-state 'running :transport-state 'ready)))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op _params &optional callback)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      ;; Point should stay at draft end when no messages
      (should (= (point) initial-point)))))

(ert-deftest psi-header-line-reflects-run-state-transitions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (should (string= "psi [disconnected/starting/streaming] tools:collapsed" header-line-format))
    (psi-emacs--set-run-state psi-emacs--state 'error)
    (should (string= "psi [disconnected/starting/error] tools:collapsed" header-line-format))))

(ert-deftest psi-session-updated-preserves-nested-retry-payload ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-1")
                 (:phase . "idle")
                 (:is-streaming . t)
                 (:is-compacting . nil)
                 (:pending-message-count . 2)
                 (:retry-attempt . 1)
                 (:retry . ((:active\? . t)
                            (:attempt . 1)
                            (:delay-ms . 8000)
                            (:delay-source . :retry-after)
                            (:resume-at . 8000)
                            (:rate-limit . ((:remaining . 0)
                                            (:limit . 5000)
                                            (:reset-at . 32000)))) )
                 (:model-provider . "openai")
                 (:model-id . "gpt-5.3-codex")
                 (:model-reasoning . t)
                 (:thinking-level . "high")
                 (:effective-reasoning-effort . "high")
                 (:header-model-label . "(openai) gpt-5.3-codex • thinking high")
                 (:status-session-line . "session: sess-1 phase:idle streaming:yes compacting:no pending:2 retry:1 retrying-in:8s source:retry-after remaining:0/5000 reset-in:32s")))))
    (should (equal '((:active\? . t)
                     (:attempt . 1)
                     (:delay-ms . 8000)
                     (:delay-source . :retry-after)
                     (:resume-at . 8000)
                     (:rate-limit . ((:remaining . 0)
                                     (:limit . 5000)
                                     (:reset-at . 32000))))
                   (psi-emacs-state-session-retry psi-emacs--state)))))

(ert-deftest psi-session-updated-projects-model-label-into-header-line ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-1")
                 (:phase . "idle")
                 (:is-streaming . t)
                 (:is-compacting . nil)
                 (:pending-message-count . 2)
                 (:retry-attempt . 1)
                 (:retry . ((:active\? . t)
                            (:attempt . 1)
                            (:delay-ms . 8000)
                            (:delay-source . :retry-after)
                            (:resume-at . 8000)
                            (:rate-limit . ((:remaining . 0)
                                            (:limit . 5000)
                                            (:reset-at . 32000)))) )
                 (:model-provider . "openai")
                 (:model-id . "gpt-5.3-codex")
                 (:model-reasoning . t)
                 (:thinking-level . "high")
                 (:effective-reasoning-effort . "high")
                 (:header-model-label . "(openai) gpt-5.3-codex • thinking high")
                 (:status-session-line . "session: sess-1 phase:idle streaming:yes compacting:no pending:2 retry:1 retrying-in:8s source:retry-after remaining:0/5000 reset-in:32s")))))
    (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
    (should (equal "(openai) gpt-5.3-codex • thinking high"
                   (psi-emacs-state-header-model-label psi-emacs--state)))
    (should (string= "psi [disconnected/starting/streaming] tools:collapsed model:(openai) gpt-5.3-codex • thinking high"
                     header-line-format))))

(ert-deftest psi-session-updated-status-diagnostics-include-session-summary ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-2")
                 (:phase . "idle")
                 (:is-streaming . nil)
                 (:is-compacting . nil)
                 (:pending-message-count . 0)
                 (:retry-attempt . 0)
                 (:retry . nil)
                 (:model-provider . "anthropic")
                 (:model-id . "claude-sonnet")
                 (:model-reasoning . t)
                 (:thinking-level . "medium")
                 (:effective-reasoning-effort . "medium")
                 (:header-model-label . "(anthropic) claude-sonnet • thinking medium")
                 (:status-session-line . "session: sess-2 phase:idle streaming:no compacting:no pending:0 retry:0")))))
    (let ((status (psi-emacs--status-diagnostics-string psi-emacs--state)))
      (should (string-match-p "session: sess-2" status))
      (should (string-match-p "phase:idle" status))
      (should (string-match-p "pending:0" status))
      (should (string-match-p "retry:0" status)))))

(ert-deftest psi-session-updated-does-not-overwrite-error-run-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-run-state psi-emacs--state 'error)
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-3")
                 (:phase . "streaming")
                 (:is-streaming . t)
                 (:pending-message-count . 1)
                 (:retry-attempt . 0)
                 (:model-provider . "openai")
                 (:model-id . "gpt-5")
                 (:model-reasoning . t)
                 (:thinking-level . "high")
                 (:effective-reasoning-effort . "high")
                 (:header-model-label . "(openai) gpt-5 • thinking high")
                 (:status-session-line . "session: sess-3 phase:streaming streaming:yes compacting:no pending:1 retry:0")))))
    (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
    (should (string-match-p "model:(openai) gpt-5 • thinking high" header-line-format))))

(ert-deftest psi-error-line-upsert-replaces-previous ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-last-error psi-emacs--state "first error")
    (psi-emacs--set-last-error psi-emacs--state "second error")
    (let ((buf (buffer-string)))
      (should (string-match-p "Error: second error" buf))
      (should-not (string-match-p "Error: first error" buf))
      (should (= 1 (how-many "^Error:" (point-min) (point-max)))))))

(ert-deftest psi-rpc-error-persists-in-transcript-and-sets-last-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "existing\n")
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((messages nil))
      (cl-letf (((symbol-function 'message)
                 (lambda (fmt &rest args)
                   (push (apply #'format fmt args) messages))))
        (psi-emacs--on-rpc-event
         (current-buffer)
         '((:event . "error") (:data . ((:code . "runtime/fail") (:message . "boom"))))))
      (should (string-match-p "existing" (buffer-string)))
      (should (string-match-p "Error: runtime/fail: boom" (buffer-string)))
      (should (= 1 (length messages)))
      (should (equal "runtime/fail: boom" (psi-emacs-state-last-error psi-emacs--state)))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (string-match-p "runtime/fail" (car messages))))))

(ert-deftest psi-reconnect-cancels-when-user-declines-confirmation ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "keep this")
    (set-buffer-modified-p t)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((started nil))
      (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) nil))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) (setq started t))))
        (psi-emacs-reconnect))
      (should (equal "keep this" (buffer-string)))
      (should-not started)
      (should (buffer-modified-p)))))

(ert-deftest psi-reconnect-confirmed-clears-buffer-and-restarts-fresh-session ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "old transcript")
    (set-buffer-modified-p t)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
    (setf (psi-emacs-state-assistant-range psi-emacs--state)
          (cons (copy-marker (point-min) nil) (copy-marker (point-max) t)))
    (puthash "t1" (list :id "t1" :stage "result" :text "done")
             (psi-emacs-state-tool-rows psi-emacs--state))
    (psi-emacs--set-last-error psi-emacs--state "old error")
    (setf (psi-emacs-state-session-id psi-emacs--state) "sess-old")
    (setf (psi-emacs-state-header-model-label psi-emacs--state) "(openai) gpt-old")
    (let ((started nil)
          (stop-called nil))
      (setf (psi-emacs-state-rpc-client psi-emacs--state) (psi-rpc-make-client))
      (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) t))
                ((symbol-function 'psi-rpc-stop!)
                 (lambda (_client) (setq stop-called t)))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) (setq started t))))
        (psi-emacs-reconnect))
      (should stop-called)
      (should started)
      (should (eq 'reconnecting (psi-emacs-state-run-state psi-emacs--state)))
      (should (psi-emacs--input-separator-marker-valid-p))
      (should (string-prefix-p "─" (buffer-string)))
      (should-not (buffer-modified-p))
      (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
      (should-not (psi-emacs-state-last-error psi-emacs--state))
      (should-not (psi-emacs-state-error-line-range psi-emacs--state))
      (should-not (psi-emacs-state-session-id psi-emacs--state))
      (should-not (psi-emacs-state-header-model-label psi-emacs--state))
      (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))))

(ert-deftest psi-reconnect-does-not-auto-resume ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "old")
    (set-buffer-modified-p nil)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((ops nil))
      (cl-letf (((symbol-function 'psi-emacs--dispatch-request)
                 (lambda (op _params &optional _callback)
                   (push op ops)))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) nil)))
        (psi-emacs-reconnect))
      (should-not (member "resume" ops))
      (should-not (member "session/resume" ops)))))


;;; History/input/replay tests moved to psi-streaming-history-test.el


(provide 'psi-streaming-runtime-test)
;;; psi-streaming-runtime-test.el ends here
