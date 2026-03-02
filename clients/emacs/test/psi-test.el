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
            (should (hash-table-p (psi-emacs-state-pending-requests psi-emacs--state)))
            (should (hash-table-p (psi-emacs-state-tool-rows psi-emacs--state)))
            (should (markerp (psi-emacs-state-draft-anchor psi-emacs--state))))
          (should (psi-emacs-state-for-buffer buffer)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

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
                          (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
                          (psi-emacs-send-from-buffer nil)
                          (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
                          (psi-emacs-send-from-buffer nil)
                          (psi-emacs-send-from-buffer '(4))))))
            (should (equal "prompt" (caar calls)))
            (should (equal '((:message . "hello")) (cadar calls)))
            (should (equal "prompt_while_streaming" (caadr calls)))
            (should (equal '((:message . "hello") (:behavior . "steer")) (cadadr calls)))
            (should (equal "prompt_while_streaming" (caaddr calls)))
            (should (equal '((:message . "hello") (:behavior . "queue"))
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
                          (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
                          (psi-emacs-queue-from-buffer)
                          (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
                          (psi-emacs-queue-from-buffer)))))
            (should (equal "prompt_while_streaming" (caar calls)))
            (should (equal '((:message . "queued") (:behavior . "queue")) (cadar calls)))
            (should (equal "prompt" (caadr calls)))
            (should (equal '((:message . "queued")) (cadadr calls)))))
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
                        (psi-emacs-abort)))))
          (should (equal '(("abort" nil)) calls))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state)))
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
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "lo")))))
          (should (equal "Hello" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "Assistant: Hello\n" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "Assistant: Hello world\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-lifecycle-updates-single-inline-row-by-tool-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:toolCallId . "t-1") (:text . "start")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/delta") (:data . ((:toolCallId . "t-1") (:text . "working")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:toolCallId . "t-1") (:text . "done")))))
          (should (equal "Tool[t-1] result: done\n" (buffer-string)))
          (let ((row (gethash "t-1" (psi-emacs-state-tool-rows psi-emacs--state))))
            (should row)
            (should (equal "result" (plist-get row :stage)))
            (should (equal "done" (plist-get row :text)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-output-converts-ansi-to-face-and-removes-escapes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
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

(provide 'psi-test)

;;; psi-test.el ends here
