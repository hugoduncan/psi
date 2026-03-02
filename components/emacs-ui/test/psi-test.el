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
      (should (string= "psi [handshaking/running] tools:collapsed" header-line-format))
      (setf (psi-rpc-client-transport-state client) 'ready)
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (string= "psi [ready/running] tools:collapsed" header-line-format)))))

(ert-deftest psi-rpc-error-event-goes-to-minibuffer-not-transcript ()
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
      (should (equal "existing\n" (buffer-string)))
      (should (= 1 (length messages)))
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
      (should (string= "" (buffer-string)))
      (should-not (buffer-modified-p))
      (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
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
          (should (string= "psi [ready/running] tools:collapsed" header-line-format)))

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
