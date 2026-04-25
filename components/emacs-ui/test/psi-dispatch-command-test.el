;;; psi-dispatch-command-test.el --- Command/session dispatch tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

(ert-deftest psi-resume-session-candidates-sort-newest-first-by-modified ()
  (let* ((sessions (list '((:psi.session-info/path . "/tmp/sessions/older.ndedn")
                           (:psi.session-info/name . "Older")
                           (:psi.session-info/modified . "2025-01-01T01:00:00Z"))
                         '((:psi.session-info/path . "/tmp/sessions/newer.ndedn")
                           (:psi.session-info/name . "Newer")
                           (:psi.session-info/modified . "2025-01-01T02:00:00Z"))
                         '((:psi.session-info/path . "/tmp/sessions/newest.ndedn")
                           (:psi.session-info/name . "Newest")
                           (:psi.session-info/modified . "2025-01-01T03:00:00Z"))))
         (candidates (psi-emacs--resume-session-candidates sessions)))
    (should (equal '(("Newest — /tmp/sessions/newest.ndedn" . "/tmp/sessions/newest.ndedn")
                     ("Newer — /tmp/sessions/newer.ndedn" . "/tmp/sessions/newer.ndedn")
                     ("Older — /tmp/sessions/older.ndedn" . "/tmp/sessions/older.ndedn"))
                   candidates))))

(ert-deftest psi-idle-resume-explicit-path-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (no-arg-calls nil))
          (insert "  /resume  sessions/foo/bar  ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs--handle-idle-resume-no-arg)
                     (lambda (_state)
                       (push t no-arg-calls)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() no-arg-calls))
          (should (equal '(("command" ((:text . "  /resume  sessions/foo/bar  "))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-resume-explicit-path-command-clears-transcript-via-rehydrate-events ()
  "Explicit `/resume <path>` should clear stale transcript and replay resumed messages."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "old transcript\n/resume /tmp/sessions/a.ndedn")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript\n") 1) nil))
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (let ((rpc-calls nil))
            (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional _callback)
                         (push (list op params) rpc-calls)
                         t)))
              (psi-emacs-send-from-buffer nil))
            (setq rpc-calls (nreverse rpc-calls))
            (should (equal '("command") (mapcar #'car rpc-calls)))
            (psi-emacs--handle-rpc-event
             '((:event . "session/resumed")
               (:data . ((:session-id . "s-resumed")
                         (:session-file . "/tmp/sessions/a.ndedn")
                         (:message-count . 2)))))
            (psi-emacs--handle-rpc-event
             '((:event . "session/rehydrated")
               (:data . ((:messages . [((:role . :user) (:text . "First"))
                                       ((:role . :assistant) (:text . "Second"))])
                         (:tool-calls . ())
                         (:tool-order . ())))))
            (should-not (string-match-p "old transcript" (buffer-string)))
            (should (string-match-p "User: First\nψ: Second\n" (buffer-string)))
            (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
            (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
            (should (psi-emacs--input-separator-marker-valid-p))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-session-response-pins-target-session-id-before-footer-filtering ()
  "Regression: /new response must retarget local session id before stale footer restore.

Without this, already-arrived footer/session events for the new session can be
filtered against the old selected session and the stale footer survives." 
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (setf (psi-emacs-state-session-id psi-emacs--state) "s-old")
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "old footer")
          (cl-letf (((symbol-function 'psi-emacs--request-get-messages-for-switch)
                     (lambda (_state) t))
                    ((symbol-function 'psi-emacs--focus-input-area)
                     (lambda (&rest _args) t)))
            (psi-emacs--handle-new-session-response
             psi-emacs--state
             '((:kind . :response)
               (:ok . t)
               (:data . ((:session-id . "s-new")))))
            (should (equal "s-new" (psi-emacs-state-session-id psi-emacs--state)))
            (should (equal "old footer" (psi-emacs-state-projection-footer psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-command-result-session-switch-requests-switch-and-replays-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (setf (psi-emacs-state-rpc-client psi-emacs--state)
          (psi-rpc-make-client :process-state 'running :transport-state 'ready))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "old transcript")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (1+ (length "old transcript")) nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: old")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional callback)
                       (push (list op params) rpc-calls)
                       (cond
                        ((and callback (equal op "switch_session"))
                         (funcall callback
                                  '((:kind . :response)
                                    (:ok . t)
                                    (:data . ((:session-id . "s-new")))))
                         t)
                        ((and callback (equal op "get_messages"))
                         (should (equal '((:session-id . "s-new")) params))
                         (funcall callback
                                  '((:kind . :response)
                                    (:ok . t)
                                    (:data . ((:messages .
                                              [((:role . :user) (:text . "First"))
                                               ((:role . :assistant) (:text . "Second"))])))))
                         t)
                        ((and callback (equal op "query_eql"))
                         (should (equal `((:session-id . "s-new")
                                           (:query . ,(psi-emacs--rehydrate-switch-extras-query)))
                                        params))
                         (funcall callback
                                  '((:kind . :response)
                                    (:ok . t)
                                    (:data . ((:result . ((:psi.agent-session/tool-lifecycle-summaries . [])
                                                          (:psi.turn/is-streaming . nil)
                                                          (:psi.turn/text . nil)
                                                          (:psi.turn/tool-calls . [])))))))
                         t)
                        (t t)))))
            (psi-emacs--handle-rpc-event
             '((:event . "command-result")
               (:data . ((:type . "session_switch")
                         (:session-id . "s-new"))))))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("switch_session" "get_messages" "query_eql")
                         (mapcar #'car rpc-calls)))
          (should (equal '((:session-id . "s-new"))
                         (cadr (car rpc-calls))))
          (should (string-prefix-p "User: First
ψ: Second
" (buffer-string)))
          (should (psi-emacs--input-separator-marker-valid-p))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-switch-session-failure-does-not-trigger-success-rehydrate-side-effects ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "keep me")
          (puthash "keep-tool" (list :id "keep-tool" :stage "result" :text "keep")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs--handle-switch-session-response
             psi-emacs--state
             "sessions/missing.ndedn"
             '((:kind . :error)
               (:error-code . "request/not-found")
               (:error-message . "session file not found"))))
          (should (equal '() rpc-calls))
          (should (string-prefix-p
                   "keep me\nψ: Unable to switch session: session file not found\n"
                   (buffer-string)))
          (should (string-match-p
                   "Error: Unable to switch session: session file not found"
                   (buffer-string)))
          (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-switch-session-failure-sets-last-error-from-deterministic-message ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-switch-session-response
           psi-emacs--state
           "sessions/missing.ndedn"
           '((:kind . :error)
             (:error-code . "request/not-found")
             (:error-message . "session file not found")))
          (should (equal "Unable to switch session: session file not found"
                         (psi-emacs-state-last-error psi-emacs--state)))
          (should (string-match-p "Error: Unable to switch session: session file not found"
                                  (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "old transcript
/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript
") 1) nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/new")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatch-preserves-tool-output-view-mode ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (psi-emacs-toggle-tool-output-view)
          (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
          (insert "/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/new")))) rpc-calls))
          (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
          (should (string-match-p "tools:expanded" header-line-format)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-command-result-new-session-appends-message-and-idles ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "stale footer")
          (psi-emacs--handle-rpc-event
           '((:event . "command-result")
             (:data . ((:type . "new_session")
                       (:message . "[New session started]")))))
          (should (string-match-p "\[New session started\]" (buffer-string)))
          (should (equal "stale footer" (psi-emacs-state-projection-footer psi-emacs--state)))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-session-rehydrated-topics-are-subscribed ()
  "Emacs must subscribe to session rehydrate topics so /new, /tree and /resume can clear and rebuild transcript state."
  (should (member "session/resumed" psi-rpc-core-topics))
  (should (member "session/rehydrated" psi-rpc-core-topics))
  (should (member "session/resumed" psi-rpc-default-topics))
  (should (member "session/rehydrated" psi-rpc-default-topics)))

(ert-deftest psi-session-resumed-event-clears-transcript-before-rehydrate ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "old transcript")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (1+ (length "old transcript")) nil))
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (psi-emacs--handle-rpc-event
           '((:event . "session/resumed")
             (:data . ((:session-id . "s-new")
                       (:session-file . "/tmp/s-new.ndedn")
                       (:message-count . 1)))))
          (should-not (string-match-p "old transcript" (buffer-string)))
          (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
          (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
          (should (psi-emacs--input-separator-marker-valid-p)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-inactive-session-stream-events-are-ignored-and-do-not-garble-next-switch ()
  "Streaming/tool/footer events for a non-selected session must not mutate the current buffer.

Regression: when session B streamed while session A was selected, Emacs rendered
B output into A. Switching to B later then produced corrupted transcript state."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-session-id psi-emacs--state) "s-a")
    (psi-emacs--append-assistant-message "alpha")
    (let ((before (buffer-string)))
      (psi-emacs--handle-rpc-event
       '((:event . "assistant/delta")
         (:data . ((:session-id . "s-b")
                   (:text . "wrong-session text")))))
      (psi-emacs--handle-rpc-event
       '((:event . "tool/start")
         (:data . ((:session-id . "s-b")
                   (:tool-id . "tb")
                   (:tool-name . "read")))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:session-id . "s-b")
                   (:path-line . "/repo/b")
                   (:usage-parts . ["tokens:" "1"])))))
      (should (equal before (buffer-string)))
      (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))

    (psi-emacs--handle-rpc-event
     '((:event . "session/resumed")
       (:data . ((:session-id . "s-b")
                 (:session-file . "/tmp/s-b.ndedn")
                 (:message-count . 1)))))
    ;; Regression guard: before session/updated arrives, wrong-session stream
    ;; events must still be filtered out while selected session is s-b.
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta")
       (:data . ((:session-id . "s-a")
                 (:text . "late wrong-session text")))))
    (psi-emacs--handle-rpc-event
     '((:event . "session/rehydrated")
       (:data . ((:session-id . "s-b")
                 (:messages . [((:role . :assistant) (:text . "beta start"))])
                 (:tool-calls . ())
                 (:tool-order . ())))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta")
       (:data . ((:session-id . "s-b")
                 (:text . "beta live")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:session-id . "s-b")
                 (:text . "beta live done")
                 (:role . "assistant")
                 (:content . [((:type . :text) (:text . "beta live done"))])))))
    (let ((text (buffer-string)))
      (should-not (string-match-p "wrong-session text" text))
      (should-not (string-match-p "late wrong-session text" text))
      (should-not (string-match-p "alpha" text))
      (should (string-match-p "beta start" text))
      (should (string-match-p "beta live done" text)))))

(ert-deftest psi-session-rehydrated-event-replays-messages-without-get-messages ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "session/resumed")
             (:data . ((:session-id . "s-new")
                       (:session-file . "/tmp/s-new.ndedn")
                       (:message-count . 2)))))
          (psi-emacs--handle-rpc-event
           '((:event . "session/rehydrated")
             (:data . ((:messages . [((:role . "user")
                                      (:content . [((:type . "text") (:text . "First"))]))
                                     ((:role . "assistant")
                                      (:content . [((:type . "text") (:text . "Second"))]))])
                       (:tool-calls . ())
                       (:tool-order . ())))))
          (should (string-match-p "User: First
ψ: Second
" (buffer-string)))
          (should (psi-emacs--input-separator-marker-valid-p)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatch-failure-sets-command-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state _op _params &optional _callback)
                       nil)))
            (psi-emacs-send-from-buffer nil))
          (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "Cannot run `command`: request dispatch failed. Reconnect with C-c C-r."
                         (psi-emacs-state-last-error psi-emacs--state)))
          (should (equal "/new" (psi-emacs--tail-draft-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-status-slash-appends-diagnostics-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: boom")
          (let ((inhibit-read-only t))
            (erase-buffer))
          (insert "/status")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/status")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-help-slash-renders-help-on-send-and-queue ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/help")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "/help")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/help")))
                           ("command" ((:text . "/help"))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-question-mark-slash-alias-renders-help-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/?")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/?")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-no-arg-opens-selector-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (selector-called nil))
          (insert "/thinking")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs-set-thinking-level)
                     (lambda (&optional _level)
                       (interactive)
                       (setq selector-called t)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should-not selector-called)
          (should (equal '(("command" ((:text . "/thinking")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-no-arg-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (selector-called nil))
          (insert "/model")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs-set-model)
                     (lambda (&optional _provider _model-id)
                       (interactive)
                       (setq selector-called t)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should-not selector-called)
          (should (equal '(("command" ((:text . "/model")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-direct-dispatches-set-thinking-level ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/thinking high")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/thinking high")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-direct-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/model openai gpt-5.3-codex")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/model openai gpt-5.3-codex")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-invalid-arity-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/model openai")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/model openai")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-invalid-arity-renders-usage-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/thinking high extra")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/thinking high extra")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-background-job-slash-commands-dispatch-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (insert "/jobs running")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t)) (erase-buffer))

            (insert "/job job-1")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t)) (erase-buffer))

            (insert "/cancel-job job-1")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("command" "command" "command") (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/jobs running")) (cadr (nth 0 rpc-calls))))
          (should (equal '((:text . "/job job-1")) (cadr (nth 1 rpc-calls))))
          (should (equal '((:text . "/cancel-job job-1")) (cadr (nth 2 rpc-calls))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-sequential-slash-commands-use-latest-tail-draft ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/help")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (goto-char (psi-emacs--draft-end-position))
            (insert "/status")
            ;; Intentionally do not reset draft anchor between sends.
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/help")))
                           ("command" ((:text . "/status"))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-template-slash-command-routes-through-backend-command-and-renders-assistant-result ()
  "Regression: prompt-template `/name ...` uses the real command-path send, then renders backend prompt execution output.

This guards the Emacs side of the RPC command-op template fallback: frontend send
must go through `command`, local user echo should be preserved, and the backend's
assistant/message result from canonical prompt execution should render without a
`[not a command]` detour."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/gh-issue-work-on 27")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls)
                       t)))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/gh-issue-work-on 27")))) rpc-calls))
          (should (string-match-p (regexp-quote "User: /gh-issue-work-on 27") (buffer-string)))
          (should-not (string-match-p (regexp-quote "[not a command]") (buffer-string)))
          (setf (psi-emacs-state-session-id psi-emacs--state) "s1")
          (psi-emacs--handle-rpc-event
           '((:event . "session/updated")
             (:data . ((:session-id . "s1")
                       (:phase . "idle")
                       (:is-streaming . nil)
                       (:is-compacting . nil)
                       (:pending-message-count . 0)
                       (:retry-attempt . 0)
                       (:interrupt-pending . nil)))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:session-id . "s1")
                       (:role . "assistant")
                       (:content . [((:type . "text") (:text . "template ok"))])))))
          (should (string-match-p (regexp-quote "ψ: template ok") (buffer-string)))
          (should-not (string-match-p (regexp-quote "[not a command]") (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(provide 'psi-dispatch-command-test)
;;; psi-dispatch-command-test.el ends here
