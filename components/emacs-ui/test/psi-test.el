;;; psi-test.el --- Tests for psi Emacs MVP scaffold  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-rpc)

(defun psi-test--spawn-long-lived-process (&optional command)
  "Spawn a long-lived process suitable for ownership/lifecycle tests."
  (make-process
   :name (format "psi-test-%s" (gensym))
   :command (or command '("cat"))
   :buffer nil
   :noquery t
   :connection-type 'pipe))

(defun psi-test--capture-request-sends (thunk)
  "Run THUNK with send function overridden and return captured RPC calls."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _callback)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

(ert-deftest psi-preferred-major-mode-falls-back-to-text-when-markdown-missing ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   nil
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'text-mode))))

(ert-deftest psi-preferred-major-mode-uses-markdown-when-available ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   t
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'markdown-mode))))

(ert-deftest psi-open-buffer-initializes-state-boundaries ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-init*"))
          (with-current-buffer buffer
            (should (eq major-mode 'psi-emacs-mode))
            (should psi-emacs--owned-process)
            (should (process-live-p psi-emacs--owned-process))
            (should (psi-emacs-state-p psi-emacs--state))
            (should (hash-table-p (psi-emacs-state-tool-rows psi-emacs--state)))
            (should (markerp (psi-emacs-state-draft-anchor psi-emacs--state))))
          (should (psi-emacs-state-for-buffer buffer)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-initialize-state-sets-idle-run-state ()
  (let ((state (psi-emacs--initialize-state nil)))
    (should (eq 'idle (psi-emacs-state-run-state state)))))

(ert-deftest psi-status-string-includes-run-state ()
  (let* ((state (psi-emacs--initialize-state nil))
         (status (psi-emacs--status-string state)))
    (should (string-match-p "psi \\[disconnected/starting/idle\\]" status))))

(ert-deftest psi-status-string-reflects-current-run-state ()
  (let ((state (psi-emacs--initialize-state nil)))
    (setf (psi-emacs-state-run-state state) 'streaming)
    (should (string-match-p "psi \\[disconnected/starting/streaming\\]"
                            (psi-emacs--status-string state)))))

(ert-deftest psi-status-diagnostics-includes-last-error-when-present ()
  (let ((state (psi-emacs--initialize-state nil)))
    (setf (psi-emacs-state-last-error state) "runtime/fail: boom")
    (let ((status (psi-emacs--status-diagnostics-string state)))
      (should (string-match-p "psi \\[disconnected/starting/idle\\]" status))
      (should (string-match-p "last-error: runtime/fail: boom" status)))))

(ert-deftest psi-watchdog-arms-when-entering-streaming ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (unwind-protect
        (progn
          (psi-emacs--arm-stream-watchdog psi-emacs--state)
          (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
          (should (numberp (psi-emacs-state-last-stream-progress-at psi-emacs--state))))
      (psi-emacs--disarm-stream-watchdog psi-emacs--state))))

(ert-deftest psi-watchdog-disarms-on-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (psi-emacs--arm-stream-watchdog psi-emacs--state)
    (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
    (psi-emacs--assistant-finalize "done")
    (should-not (psi-emacs-state-stream-watchdog-timer psi-emacs--state))))

(ert-deftest psi-watchdog-resets-on-assistant-delta ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (psi-emacs--arm-stream-watchdog psi-emacs--state)
    (let ((first-timer (psi-emacs-state-stream-watchdog-timer psi-emacs--state))
          (first-ts (psi-emacs-state-last-stream-progress-at psi-emacs--state)))
      (psi-emacs--handle-rpc-event
       '((:event . "assistant/delta") (:data . ((:text . "tick")))))
      (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
      (should-not (eq first-timer (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
      (should (>= (psi-emacs-state-last-stream-progress-at psi-emacs--state) first-ts))
      (psi-emacs--disarm-stream-watchdog psi-emacs--state))))

(ert-deftest psi-watchdog-timeout-aborts-once-and-sets-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
        (psi-emacs--arm-stream-watchdog psi-emacs--state)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state))
      (setq calls (nreverse calls))
      (should (equal '(("abort" nil)) calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should-not (psi-emacs-state-stream-watchdog-timer psi-emacs--state))
      (should (string-match-p "Error: Streaming stalled after" (buffer-string))))))

(ert-deftest psi-watchdog-noop-when-not-streaming ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state))
      (should (equal '() calls))
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
      (should (string= "" (buffer-string))))))

(ert-deftest psi-killing-dedicated-buffer-terminates-only-owned-subprocess ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil)
        (unrelated (psi-test--spawn-long-lived-process))
        (owned nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-kill*"))
          (with-current-buffer buffer
            (setq owned psi-emacs--owned-process)
            (should (process-live-p owned)))
          (should (process-live-p unrelated))
          (kill-buffer buffer)
          (should-not (process-live-p owned))
          (should (process-live-p unrelated))
          (should-not (psi-emacs-state-for-buffer buffer)))
      (when (process-live-p unrelated)
        (delete-process unrelated))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-compose-source-prefers-region-over-tail-draft ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "prefix\nTAIL")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 8 nil))
          (set-mark 1)
          (goto-char 6)
          (activate-mark)
          (should (equal "prefi" (psi-emacs--composed-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-compose-source-uses-tail-draft-when-no-region ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "transcript\nDraft text")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "transcript\n") 1) nil))
          (deactivate-mark)
          (should (equal "Draft text" (psi-emacs--composed-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-ret-inserts-newline-and-does-not-send ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls (psi-test--capture-request-sends
                      (lambda ()
                        (insert "a")
                        (call-interactively (key-binding (kbd "RET")))))))
          (should (equal "a\n" (buffer-string)))
          (should (equal '() calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-streaming-p-uses-explicit-run-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "legacy")
    (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
    (should-not (psi-emacs--streaming-p))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (should (psi-emacs--streaming-p))))

(ert-deftest psi-send-routing-idle-and-streaming-steer-queue ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "hello")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
                          (psi-emacs-send-from-buffer nil)
                          (psi-emacs-send-from-buffer nil)
                          (psi-emacs-send-from-buffer '(4))))))
            (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
            (should (equal "prompt" (caar calls)))
            (should (equal '((:message . "hello")) (cadar calls)))
            (should (equal "prompt_while_streaming" (caadr calls)))
            (should (equal '((:message . "") (:behavior . "steer")) (cadadr calls)))
            (should (equal "prompt_while_streaming" (caaddr calls)))
            (should (equal '((:message . "") (:behavior . "queue"))
                           (cadr (nth 2 calls))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-queue-key-routes-queue-when-streaming-and-prompt-when-idle ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "queued")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
                          (psi-emacs-queue-from-buffer)
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
                          (psi-emacs-queue-from-buffer)))))
            (should (equal "prompt_while_streaming" (caar calls)))
            (should (equal '((:message . "queued") (:behavior . "queue")) (cadar calls)))
            (should (equal "prompt" (caadr calls)))
            (should (equal '((:message . "")) (cadadr calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-send-and-queue-share-slash-handler-path ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "  /handled  ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--idle-slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       t))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (erase-buffer)
            (insert "  /handled  ")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (should (equal '("  /handled  " "  /handled  ") slash-calls))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-unknown-slash-falls-through-to-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/foo")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--idle-slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (erase-buffer)
            (insert "/foo")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq slash-calls (nreverse slash-calls))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("/foo" "/foo") slash-calls))
          (should (equal '("prompt" "prompt") (mapcar #'car rpc-calls)))
          (should (equal '((:message . "/foo")) (cadr (car rpc-calls))))
          (should (equal '((:message . "/foo")) (cadr (cadr rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-quit-slash-requests-frontend-exit-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((exit-called nil)
              (rpc-calls nil))
          (insert "/quit")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs--request-frontend-exit)
                     (lambda ()
                       (setq exit-called t)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (should exit-called)
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-exit-slash-requests-frontend-exit-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((exit-called nil)
              (rpc-calls nil))
          (insert "/exit")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs--request-frontend-exit)
                     (lambda ()
                       (setq exit-called t)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (should exit-called)
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-slash-appends-fallback-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert " /resume ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (should (string-match-p "Resume selector unavailable in this frontend\." (buffer-string)))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-parity-no-arg-routes-to-no-arg-handler-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (no-arg-calls nil)
              (path-calls nil))
          (insert "/resume")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((psi-emacs-enable-resume-parity t))
            (cl-letf (((symbol-function 'psi-emacs--handle-idle-resume-parity-no-arg)
                       (lambda (state)
                         (push state no-arg-calls)
                         (psi-emacs--append-assistant-message "parity-no-arg")))
                      ((symbol-function 'psi-emacs--handle-idle-resume-parity-explicit-path)
                       (lambda (_state session-path)
                         (push session-path path-calls)))
                      ((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional _callback)
                         (push (list op params) rpc-calls))))
              (psi-emacs-send-from-buffer nil)))
          (should (= 1 (length no-arg-calls)))
          (should (equal '() path-calls))
          (should (string-match-p "Assistant: parity-no-arg" (buffer-string)))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-parity-no-arg-queries-sessions-and-dispatches-switch-session ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (labels-seen nil))
          (insert "/resume")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((psi-emacs-enable-resume-parity t))
            (cl-letf (((symbol-function 'completing-read)
                       (lambda (_prompt collection &rest _args)
                         (setq labels-seen (if (vectorp collection)
                                               (append collection nil)
                                             collection))
                         (car labels-seen)))
                      ((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional callback)
                         (push (list op params) rpc-calls)
                         (when (and callback (equal op "query_eql"))
                           (funcall callback
                                    `((:kind . :response)
                                      (:ok . t)
                                      (:op . "query_eql")
                                      (:data . ((:result .
                                                ((:psi.session/list .
                                                  [((:psi.session-info/path . "/tmp/sessions/a.ndedn")
                                                    (:psi.session-info/name . "Alpha")
                                                    (:psi.session-info/first-message . "hello"))
                                                   ((:psi.session-info/path . "/tmp/sessions/b.ndedn")
                                                    (:psi.session-info/name . "")
                                                    (:psi.session-info/first-message . "Beta first"))])))))))))))
              (psi-emacs-send-from-buffer nil)))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("query_eql" "switch_session") (mapcar #'car rpc-calls)))
          (should (equal '((:query . "[{:psi.session/list [:psi.session-info/path\n                        :psi.session-info/name\n                        :psi.session-info/first-message\n                        :psi.session-info/modified]}]"))
                         (cadr (car rpc-calls))))
          (should (equal '((:session-path . "/tmp/sessions/a.ndedn"))
                         (cadr (cadr rpc-calls))))
          (should-not (member "prompt" (mapcar #'car rpc-calls)))
          (should (= 2 (length labels-seen)))
          (should (string-match-p "^Alpha — /tmp/sessions/a\\.ndedn$" (car labels-seen))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-parity-no-arg-selector-cancel-is-noop ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (selected-paths nil)
              (before nil))
          (insert "/resume")
          (setq before (buffer-string))
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((psi-emacs-enable-resume-parity t))
            (cl-letf (((symbol-function 'completing-read)
                       (lambda (&rest _args)
                         (signal 'quit nil)))
                      ((symbol-function 'psi-emacs--handle-idle-resume-parity-explicit-path)
                       (lambda (_state session-path)
                         (push session-path selected-paths)))
                      ((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional callback)
                         (push (list op params) rpc-calls)
                         (when (and callback (equal op "query_eql"))
                           (funcall callback
                                    '((:kind . :response)
                                      (:ok . t)
                                      (:op . "query_eql")
                                      (:data . ((:result .
                                                ((:psi.session/list .
                                                  [((:psi.session-info/path . "/tmp/sessions/a.ndedn")
                                                    (:psi.session-info/name . "Alpha")
                                                    (:psi.session-info/first-message . "hello"))])))))))))))
              (psi-emacs-send-from-buffer nil)))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("query_eql") (mapcar #'car rpc-calls)))
          (should (equal '() selected-paths))
          (should (string= before (buffer-string)))
          (should-not (string-match-p "Assistant:" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-parity-explicit-path-dispatches-switch-session-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (no-arg-calls nil))
          (insert "  /resume  sessions/foo/bar  ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (let ((psi-emacs-enable-resume-parity t))
            (cl-letf (((symbol-function 'psi-emacs--handle-idle-resume-parity-no-arg)
                       (lambda (_state)
                         (push t no-arg-calls)))
                      ((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional _callback)
                         (push (list op params) rpc-calls))))
              (psi-emacs-send-from-buffer nil)))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() no-arg-calls))
          (should (equal '("switch_session") (mapcar #'car rpc-calls)))
          (should (equal '((:session-path . "sessions/foo/bar"))
                         (cadr (car rpc-calls))))
          (should-not (member "prompt" (mapcar #'car rpc-calls))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-resume-switch-success-resets-before-get-messages-and-replays-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (cleared-before-replay nil))
          (insert "old transcript\n/resume sessions/new.ndedn")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript\n") 1) nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: old")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (let ((psi-emacs-enable-resume-parity t))
            (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional callback)
                         (push (list op params) rpc-calls)
                         (cond
                          ((and callback (equal op "switch_session"))
                           (funcall callback
                                    '((:kind . :response)
                                      (:ok . t)
                                      (:data . ((:session-id . "s-new"))))))
                          ((and callback (equal op "get_messages"))
                           (setq cleared-before-replay
                                 (and (not (string-match-p "old transcript" (buffer-string)))
                                      (null (psi-emacs-state-last-error psi-emacs--state))
                                      (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
                           (funcall callback
                                    '((:kind . :response)
                                      (:ok . t)
                                      (:data . ((:messages .
                                                [((:role . :user) (:text . "First"))
                                                 ((:role . :assistant) (:text . "Second"))]))))))))))
              (psi-emacs-send-from-buffer nil)))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("switch_session" "get_messages") (mapcar #'car rpc-calls)))
          (should cleared-before-replay)
          (should (equal "User: First\nAssistant: Second\n" (buffer-string)))
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
                   "keep me\nAssistant: Unable to switch session: session file not found\n"
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

(ert-deftest psi-idle-new-slash-requests-new-session-and-resets-transcript ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "old transcript\n/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript\n") 1) nil))
          (puthash "t1" (list :id "t1" :stage "result" :text "done")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional callback)
                       (push (list op params) rpc-calls)
                       (when callback
                         (funcall callback '((:kind . :response)
                                             (:ok . t)
                                             (:data . ((:session-id . "s-2")))))))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("new_session") (mapcar #'car rpc-calls)))
          (should (equal '(nil) (mapcar #'cadr rpc-calls)))
          (should (string= "Assistant: Started a fresh backend session.\n" (buffer-string)))
          (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-new-slash-appends-deterministic-error-on-failure ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional callback)
                       (push (list op params) rpc-calls)
                       (when callback
                         (funcall callback '((:kind . :error)
                                             (:error-message . "backend unavailable")))))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("new_session") (mapcar #'car rpc-calls)))
          (should (equal '(nil) (mapcar #'cadr rpc-calls)))
          (should (string-match-p
                   "Unable to start a fresh backend session: backend unavailable"
                   (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-status-slash-appends-diagnostics-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: boom")
          (erase-buffer)
          (insert "/status")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (should (string-match-p "Assistant: psi \\[disconnected/.*/idle\\]" (buffer-string)))
          (should (string-match-p "last-error: runtime/fail: boom" (buffer-string)))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-help-slash-renders-help-on-send-and-queue ()
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
            (erase-buffer)
            (insert "/help")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (should (string-match-p "Supported slash commands:" (buffer-string)))
          (should (string-match-p "/help, /\?" (buffer-string)))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-question-mark-slash-alias-renders-help-and-bypasses-prompt ()
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
          (should (string-match-p "Supported slash commands:" (buffer-string)))
          (should (equal '() rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-sequential-slash-commands-use-latest-tail-draft ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (status nil))
          (insert "/help")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (goto-char (point-max))
            (insert "\n/status")
            ;; Intentionally do not reset draft anchor between sends.
            (setq status (psi-emacs--status-string psi-emacs--state))
            (psi-emacs-send-from-buffer nil))
          (should (equal '() rpc-calls))
          (should (string-suffix-p (concat "Assistant: " status "\n")
                                   (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-streaming-bypasses-idle-slash-handler ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/stream")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (cl-letf (((symbol-value 'psi-emacs--idle-slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       t))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() slash-calls))
          (should (equal '("prompt_while_streaming" "prompt_while_streaming")
                         (mapcar #'car rpc-calls))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-abort-sends-rpc-and-clears-streaming-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls (psi-test--capture-request-sends
                      (lambda ()
                        (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
                        (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
                        (psi-emacs-abort)))))
          (should (equal '(("abort" nil)) calls))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-single-block-aggregates-and-finalizes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "lo")))))
          (should (equal "Hello" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "Assistant: Hello\n" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "Assistant: Hello world\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-content-blocks-finalizes-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text) (:text . "Hello from content"))])))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "Assistant: Hello from content\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-empty-does-not-clobber-streaming-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "partial")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:role . "assistant") (:content . [])))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "Assistant: partial\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-finalize-moves-point-to-end ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "draft\n")
          (goto-char (point-min))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text) (:text . "move me"))])))))
          (should (equal (point-max) (point))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-lifecycle-updates-single-inline-row-by-tool-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:text . "start")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/delta") (:data . ((:tool-id . "t-1") (:text . "working")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:result-text . "done")))))
          ;; Default mode is collapsed: buffer shows header-only
          (should (equal "Tool[t-1] result\n" (buffer-string)))
          (let ((row (gethash "t-1" (psi-emacs-state-tool-rows psi-emacs--state))))
            (should row)
            (should (equal "result" (plist-get row :stage)))
            (should (equal "done" (plist-get row :text)))
            ;; Accumulated text contains all deltas
            (should (string-match-p "done" (plist-get row :accumulated-text)))))
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
             (:data . ((:toolCallId . "t-ansi") (:text . "\u001b[31mERR\u001b[0m")))))
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (equal "Tool[t-ansi] result: ERR\n" text)))
          (goto-char (point-min))
          (search-forward "ERR")
          (let ((face (get-text-property (1- (point)) 'face)))
            (should face)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

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

(ert-deftest psi-reconnecting-run-state-transitions-to-idle-when-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready)))
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
      (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format)))))

(ert-deftest psi-header-line-reflects-run-state-transitions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (should (string= "psi [disconnected/starting/streaming] tools:collapsed" header-line-format))
    (psi-emacs--set-run-state psi-emacs--state 'error)
    (should (string= "psi [disconnected/starting/error] tools:collapsed" header-line-format))))

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
      (should (string= "" (buffer-string)))
      (should-not (buffer-modified-p))
      (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
      (should-not (psi-emacs-state-last-error psi-emacs--state))
      (should-not (psi-emacs-state-error-line-range psi-emacs--state))
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

(ert-deftest psi-mvp-smoke-start-handshake-send-stream-abort-reconnect ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
    (let ((sent nil)
          (stop-count 0)
          (restart-count 0))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) sent)))
                ((symbol-function 'psi-rpc-stop!)
                 (lambda (_client)
                   (setq stop-count (1+ stop-count))))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer)
                   (setq restart-count (1+ restart-count)))))
        (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'handshaking)))
          (psi-emacs--on-rpc-state-change (current-buffer) client)
          (setf (psi-rpc-client-transport-state client) 'ready)
          (psi-emacs--on-rpc-state-change (current-buffer) client)
          (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format)))

        (insert "hello from smoke")
        (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
        (psi-emacs-send-from-buffer nil)

        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hi")))))
        (psi-emacs--handle-rpc-event
         '((:event . "tool/start") (:data . ((:toolCallId . "t-smoke") (:text . "start")))))
        (psi-emacs--handle-rpc-event
         '((:event . "tool/result") (:data . ((:toolCallId . "t-smoke") (:text . "done")))))
        (should (string-match-p "Assistant: Hi" (buffer-string)))
        ;; Default mode is collapsed: header-only (no body text)
        (should (string-match-p "Tool\\[t-smoke\\] result" (buffer-string)))

        (psi-emacs-abort)
        (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))

        (setf (psi-emacs-state-rpc-client psi-emacs--state) (psi-rpc-make-client))
        (set-buffer-modified-p nil)
        (psi-emacs-reconnect)

        (setq sent (nreverse sent))
        (should (equal "prompt" (caar sent)))
        (should (equal '((:message . "hello from smoke")) (cadar sent)))
        (should (equal "abort" (caadr sent)))
        (should (= 1 stop-count))
        (should (= 1 restart-count))
        (should (string= "" (buffer-string)))))))

;;; Tool-output-mode acceptance criteria tests (10 tests)

(ert-deftest psi-emacs-test-default-mode-is-collapsed ()
  "AC1: Default tool-output-view-mode is collapsed after state initialization."
  (let ((state (psi-emacs--initialize-state nil)))
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode state)))))

(ert-deftest psi-emacs-test-toggle-command-flips-mode ()
  "AC2: Toggle command flips mode collapsed->expanded->collapsed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; starts collapsed
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; toggle back to collapsed
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))))

(ert-deftest psi-emacs-test-toggle-with-no-rows-changes-future-mode ()
  "AC3: Toggle with no rows changes mode; future tool rows render in new mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; No rows, toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Inject a new tool row - should render in expanded mode (with body)
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-future") (:result-text . "output text")))))
    ;; Expanded mode shows body text
    (should (string-match-p "output text" (buffer-string)))))

(ert-deftest psi-emacs-test-collapsed-rows-render-header-only ()
  "AC4: Collapsed mode renders header-only rows (no body text)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Default is collapsed
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-hdr") (:text . "body content here")))))
    ;; Header should be present
    (should (string-match-p "Tool\\[t-hdr\\] start" (buffer-string)))
    ;; Body text should NOT be present in collapsed mode
    (should-not (string-match-p "body content here" (buffer-string)))))

(ert-deftest psi-emacs-test-collapsed-rows-show-live-status-updates ()
  "AC5: Collapsed mode updates header stage on lifecycle events."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "Tool\\[t-live\\] start" (buffer-string)))
    ;; Advance to executing stage
    (psi-emacs--handle-rpc-event
     '((:event . "tool/executing") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "Tool\\[t-live\\] executing" (buffer-string)))
    ;; Previous stage header should be replaced
    (should-not (string-match-p "Tool\\[t-live\\] start" (buffer-string)))))

(ert-deftest psi-emacs-test-accumulated-output-visible-after-expand ()
  "AC6: Collapsed rows accumulate output; toggle to expanded reveals full text."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Inject deltas in collapsed mode
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-acc") (:text . "first")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/delta") (:data . ((:tool-id . "t-acc") (:text . "second")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-acc") (:result-text . "final")))))
    ;; Collapsed: body text not visible
    (should-not (string-match-p "first" (buffer-string)))
    ;; Toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    ;; Expanded: accumulated text visible
    (let ((buf (buffer-string)))
      (should (string-match-p "final" buf)))))

(ert-deftest psi-emacs-test-error-row-stays-collapsed-in-collapsed-mode ()
  "AC7: Error tool results do not auto-expand in collapsed mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Inject an error result
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-err")
                 (:result-text . "ERROR: something went wrong")
                 (:is-error . t)))))
    ;; Collapsed: header visible
    (should (string-match-p "Tool\\[t-err\\] result" (buffer-string)))
    ;; Collapsed: error body text NOT visible
    (should-not (string-match-p "ERROR: something went wrong" (buffer-string)))))

(ert-deftest psi-emacs-test-header-line-includes-tool-mode ()
  "AC8: Header line displays tool-output mode (collapsed or expanded)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--refresh-header-line)
    ;; Default: header shows tools:collapsed
    (should (string-match-p "tools:collapsed" header-line-format))
    ;; After toggle: header shows tools:expanded
    (psi-emacs-toggle-tool-output-view)
    (should (string-match-p "tools:expanded" header-line-format))
    ;; Toggle back: header shows tools:collapsed again
    (psi-emacs-toggle-tool-output-view)
    (should (string-match-p "tools:collapsed" header-line-format))))

(ert-deftest psi-emacs-test-reconnect-resets-mode-to-collapsed ()
  "AC9: Reconnect (reset-transcript-state) restores tool-output mode to collapsed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Set mode to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Simulate reconnect reset
    (psi-emacs--reset-transcript-state)
    ;; Mode must be back to collapsed
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Header line must reflect collapsed
    (should (string-match-p "tools:collapsed" header-line-format))))

(ert-deftest psi-emacs-test-non-reconnect-session-preserves-mode ()
  "AC10: Non-reconnect session operations preserve the current tool-output mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
    ;; Set mode to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Simulate a new message send (non-reconnect session operation)
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (insert "new message")
        (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
        (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
        (psi-emacs-send-from-buffer nil)))
    ;; Mode must still be expanded
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))))

(provide 'psi-test)

;;; psi-test.el ends here
