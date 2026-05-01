;;; psi-buffer-lifecycle-test.el --- Buffer lifecycle tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

(ert-deftest psi-preferred-major-mode-falls-back-to-text-when-markdown-missing ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   nil
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'text-mode))))

(ert-deftest psi-preferred-major-mode-remains-text-when-markdown-available ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   t
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'text-mode))))

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
            (should (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
            (should (psi-emacs--input-separator-marker-valid-p)))
          (should (psi-emacs-state-for-buffer buffer)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-open-buffer-seeds-connecting-footer-before-handshake ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil))
    (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
               (lambda (_buffer) nil)))
      (unwind-protect
          (progn
            (setq buffer (psi-emacs-open-buffer "*psi-test-connecting-footer*"))
            (with-current-buffer buffer
              (should (psi-emacs--input-separator-marker-valid-p))
              (should (equal "connecting..." (psi-emacs-state-projection-footer psi-emacs--state)))
              (should (string-prefix-p "ψ\n" (buffer-string)))
              (should (string-match-p "connecting\.\.\." (buffer-string)))
              (should (= (point) (psi-emacs--draft-end-position)))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))))))

(ert-deftest psi-start-focuses-window-point-in-input-area-before-handshake ()
  (let* ((psi-emacs-buffer-name (format "*psi-test-focus-%s*" (gensym)))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (window nil))
    (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
               (lambda (_buffer) nil)))
      (unwind-protect
          (progn
            (setq buffer (psi-emacs-start nil))
            (setq window (get-buffer-window buffer t))
            (with-current-buffer buffer
              (should (window-live-p window))
              (should (= (point) (psi-emacs--draft-end-position)))
              (should (= (window-point window) (psi-emacs--draft-end-position)))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))))))

(ert-deftest psi-focus-input-area-prefers-explicit-window-target ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--seed-connecting-footer)
    (let ((calls nil))
      (cl-letf (((symbol-function 'window-live-p)
                 (lambda (win) (eq win 'target-win)))
                ((symbol-function 'window-buffer)
                 (lambda (_win) (current-buffer)))
                ((symbol-function 'set-window-point)
                 (lambda (win pos)
                   (push (list win pos) calls)))
                ((symbol-function 'get-buffer-window-list)
                 (lambda (&rest _args) nil)))
        (psi-emacs--focus-input-area (current-buffer) 'target-win))
      (should calls)
      (should (eq 'target-win (caar calls)))
      (should (= (psi-emacs--draft-end-position) (cadar calls))))))

(ert-deftest psi-open-buffer-respects-explicit-working-directory ()
  (let* ((psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (psi-emacs-working-directory (make-temp-file "psi-emacs-working-dir-" t))
         (buffer nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-working-dir*"))
          (with-current-buffer buffer
            (should (equal (file-name-as-directory psi-emacs-working-directory)
                           default-directory))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (when (file-directory-p psi-emacs-working-directory)
        (delete-directory psi-emacs-working-directory t)))))

(ert-deftest psi-open-buffer-defaults-to-invocation-working-directory ()
  (let* ((outside-dir (make-temp-file "psi-emacs-outside-" t))
         (psi-emacs-command '("cat"))
         (psi-emacs-working-directory nil)
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil))
    (unwind-protect
        (let ((default-directory (file-name-as-directory outside-dir)))
          (setq buffer (psi-emacs-open-buffer "*psi-test-invocation-dir*"))
          (with-current-buffer buffer
            (should (equal (file-name-as-directory outside-dir)
                           default-directory))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (when (file-directory-p outside-dir)
        (delete-directory outside-dir t)))))

(ert-deftest psi-start-without-prefix-reuses-default-buffer ()
  (let* ((base-name (format "*psi-test-start-%s*" (gensym)))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (setq first (psi-emacs-start nil))
            (setq second (psi-emacs-start nil))
            (should (eq first second))
            (should (equal base-name (buffer-name first))))
        (when (buffer-live-p first)
          (kill-buffer first))))))

(ert-deftest psi-start-with-prefix-creates-fresh-buffer ()
  (let* ((base-name (format "*psi-test-start-%s*" (gensym)))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (setq first (psi-emacs-start nil))
            (setq second (psi-emacs-start '(4)))
            (should (buffer-live-p first))
            (should (buffer-live-p second))
            (should-not (eq first second))
            (should (equal base-name (buffer-name first)))
            (should (string-prefix-p base-name (buffer-name second))))
        (when (buffer-live-p second)
          (kill-buffer second))
        (when (buffer-live-p first)
          (kill-buffer first))))))

(ert-deftest psi-project-buffer-base-name-uses-project-name ()
  (let ((project-root "/tmp/demo-project/"))
    (should (equal "*psi:demo-project*"
                   (psi-emacs--project-buffer-base-name project-root)))))

(ert-deftest psi-project-buffer-name-for-prefix-behavior ()
  (let ((base "*psi:demo-project*"))
    (should (equal base (psi-emacs--project-buffer-name-for-prefix base nil)))
    (should (equal base (psi-emacs--project-buffer-name-for-prefix base '(1))))
    (should (equal (format "%s<3>" base)
                   (psi-emacs--project-buffer-name-for-prefix base '(3))))
    (cl-letf (((symbol-function 'generate-new-buffer-name)
               (lambda (_name) "*psi:demo-project*<2>")))
      (should (equal "*psi:demo-project*<2>"
                     (psi-emacs--project-buffer-name-for-prefix base '(4)))))))

(ert-deftest psi-project-without-prefix-reuses-project-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-name (psi-emacs--project-buffer-base-name project-root))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq first (psi-emacs-project nil))
            (setq second (psi-emacs-project nil))
            (should (eq first second))
            (should (equal expected-name (buffer-name first)))
            (with-current-buffer first
              (should (equal project-root default-directory))))
        (when (buffer-live-p first)
          (kill-buffer first))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-with-prefix-creates-fresh-project-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-base (psi-emacs--project-buffer-base-name project-root))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq first (psi-emacs-project nil))
            (setq second (psi-emacs-project '(4)))
            (should (buffer-live-p first))
            (should (buffer-live-p second))
            (should-not (eq first second))
            (should (equal expected-base (buffer-name first)))
            (should (string-prefix-p expected-base (buffer-name second))))
        (when (buffer-live-p second)
          (kill-buffer second))
        (when (buffer-live-p first)
          (kill-buffer first))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-with-numeric-prefix-opens-slot-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-base (psi-emacs--project-buffer-base-name project-root))
         (slot-name (format "%s<3>" expected-base))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (slot-buffer nil)
         (same-slot nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq slot-buffer (psi-emacs-project '(3)))
            (setq same-slot (psi-emacs-project '(3)))
            (should (eq slot-buffer same-slot))
            (should (equal slot-name (buffer-name slot-buffer))))
        (when (buffer-live-p slot-buffer)
          (kill-buffer slot-buffer))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-errors-when-project-root-unavailable ()
  (cl-letf (((symbol-function 'psi-emacs--entry-project-root-directory)
             (lambda (&optional _start-directory) nil)))
    (should-error (psi-emacs-project nil)
                  :type 'user-error)))

(ert-deftest psi-start-restarts-existing-buffer-when-process-exited ()
  (let* ((base-name (format "*psi-test-restart-%s*" (gensym)))
         (start-dir (make-temp-file "psi-emacs-start-" t))
         (outside-dir (make-temp-file "psi-emacs-outside-" t))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs-working-directory nil)
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (first-process nil)
         (second-process nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (let ((default-directory (file-name-as-directory start-dir)))
              (setq buffer (psi-emacs-start nil)))
            (with-current-buffer buffer
              (setq first-process (psi-emacs-state-process psi-emacs--state))
              (should (process-live-p first-process))
              (should (equal (file-name-as-directory start-dir)
                             default-directory))
              (delete-process first-process))

            (let ((default-directory (file-name-as-directory outside-dir)))
              (psi-emacs-start nil))

            (with-current-buffer buffer
              (setq second-process (psi-emacs-state-process psi-emacs--state))
              (should (process-live-p second-process))
              (should-not (eq first-process second-process))
              (should (equal (file-name-as-directory outside-dir)
                             default-directory))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))
        (when (file-directory-p outside-dir)
          (delete-directory outside-dir t))
        (when (file-directory-p start-dir)
          (delete-directory start-dir t))))))

(ert-deftest psi-open-buffer-restarts-existing-buffer-when-transport-disconnected ()
  (let* ((buffer-name (format "*psi-test-disconnected-%s*" (gensym)))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (stale-process nil)
         (restart-called nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer buffer-name))
          (with-current-buffer buffer
            (setq stale-process (psi-emacs-state-process psi-emacs--state))
            (should (process-live-p stale-process))
            (setf (psi-emacs-state-rpc-client psi-emacs--state)
                  (psi-rpc-make-client :process stale-process
                                       :process-state 'running
                                       :transport-state 'disconnected))
            (setf (psi-emacs-state-process-state psi-emacs--state) 'running)
            (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected))
          (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
                     (lambda (_buffer)
                       (setq restart-called t))))
            (psi-emacs-open-buffer buffer-name))
          (should restart-called)
          (should-not (process-live-p stale-process)))
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

(ert-deftest psi-kill-buffer-cancels-when-user-declines-process-confirmation ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil)
        (owned nil)
        (prompted nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-kill-confirm*"))
          (with-current-buffer buffer
            (setq owned psi-emacs--owned-process)
            (should (process-live-p owned)))
          (cl-letf (((symbol-function 'yes-or-no-p)
                     (lambda (_prompt)
                       (setq prompted t)
                       nil)))
            (let ((noninteractive nil))
              (should-not (kill-buffer buffer))))
          (should prompted)
          (should (buffer-live-p buffer))
          (should (process-live-p owned)))
      (when (buffer-live-p buffer)
        (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) t)))
          (let ((noninteractive nil))
            (kill-buffer buffer)))))))

(ert-deftest psi-kill-buffer-allows-without-prompt-in-noninteractive-tests ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((prompted nil))
      (cl-letf (((symbol-function 'yes-or-no-p)
                 (lambda (_prompt)
                   (setq prompted t)
                   t)))
        (let ((noninteractive t))
          (should (psi-emacs--confirm-kill-buffer-p))))
      (should-not prompted))))

(ert-deftest psi-refresh-buffer-lifecycle-hooks-adds-kill-query-hook-to-existing-psi-buffers ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local kill-buffer-query-functions nil)
    (should-not (memq #'psi-emacs--confirm-kill-buffer-p kill-buffer-query-functions))
    (psi-emacs--refresh-buffer-lifecycle-hooks)
    (should (memq #'psi-emacs--confirm-kill-buffer-p kill-buffer-query-functions))))

(ert-deftest psi-default-spawn-process-uses-unique-process-name ()
  (let ((process-1 nil)
        (process-2 nil))
    (unwind-protect
        (progn
          (setq process-1 (psi-emacs--default-spawn-process '("cat")))
          (setq process-2 (psi-emacs--default-spawn-process '("cat")))
          (should (process-live-p process-1))
          (should (process-live-p process-2))
          (should-not (equal (process-name process-1)
                             (process-name process-2))))
      (when (process-live-p process-1)
        (delete-process process-1))
      (when (process-live-p process-2)
        (delete-process process-2))
      (dolist (proc (list process-1 process-2))
        (when proc
          (when-let ((stderr (process-get proc 'psi-rpc-stderr-buffer)))
            (when (buffer-live-p stderr)
              (kill-buffer stderr))))))))


(ert-deftest psi-footer-update-does-not-insert-newline-into-draft ()
  "Upserting the projection block must not corrupt in-progress draft text.

Regression: psi-emacs--ensure-newline-before-projection-append was called
inside upsert-projection-block.  When the old projection was deleted the
buffer tail became the user's draft text (no trailing newline), so the helper
inserted a newline directly into the draft area.  The rendered block already
starts with \"\\n\" so the extra call was both redundant and harmful."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      (psi-emacs--ensure-startup-banner)
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      ;; Establish input separator and seed an initial footer (simulates
      ;; show-connecting-affordances at startup).
      (psi-emacs--ensure-input-area)
      (setf (psi-emacs-state-projection-footer psi-emacs--state) "connecting...")
      (psi-emacs--upsert-projection-block)
      ;; Simulate user typing "hello" into the draft area before RPC connects.
      (goto-char (psi-emacs--draft-end-position))
      (insert "hello")
      (let ((draft-before (psi-emacs--tail-draft-text)))
        ;; Now simulate footer/updated arriving from the backend (RPC connected).
        (setf (psi-emacs-state-projection-footer psi-emacs--state) "idle · claude-3-5-sonnet")
        (psi-emacs--upsert-projection-block)
        ;; Draft text must be exactly what the user typed — no injected newline.
        (should (equal draft-before (psi-emacs--tail-draft-text)))
        (should (equal "hello" (psi-emacs--tail-draft-text)))))))

(ert-deftest psi-open-buffer-does-not-reseed-connecting-footer-when-transport-ready ()
  "open-buffer re-entry must not overwrite a live footer with \"connecting...\".

Regression: when open-buffer was called again after the RPC connection was
established (e.g. user invokes psi-emacs-project to focus the window), the
show-connecting-affordances guard only checked whether projection-footer and
region-bounds were nil.  If the footer had been cleared by some transient
state (or was nil for any reason) while the transport was already ready, the
guard would fire and overwrite the display with \"connecting...\".

Fix: add (not (eq transport-state \\='ready)) to the guard so the seed is
suppressed only when the transport is fully connected, while still allowing
the seed during handshaking and disconnected states."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      (psi-emacs--ensure-startup-banner)
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      ;; Simulate a fully connected RPC client (transport = ready).
      (let* ((mock-client (psi-rpc-make-client
                           :process-state 'running
                           :transport-state 'ready))
             (mock-process (psi-test--spawn-long-lived-process)))
        (setf (psi-rpc-client-process mock-client) mock-process)
        (setf (psi-emacs-state-rpc-client psi-emacs--state) mock-client)
        (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
        (setf (psi-emacs-state-process psi-emacs--state) mock-process)
        (unwind-protect
            (progn
              ;; Footer is nil and no projection region — the old guard would fire.
              (should (null (psi-emacs-state-projection-footer psi-emacs--state)))
              (should (null (psi-emacs--region-bounds 'projection 'main)))
              ;; Simulate open-buffer re-entry by applying the guard condition.
              (let ((transport-state (psi-emacs-state-transport-state psi-emacs--state))
                    (state psi-emacs--state))
                (when (and (not (eq transport-state 'ready))
                           (null (psi-emacs-state-projection-footer state))
                           (null (psi-emacs--region-bounds 'projection 'main)))
                  (psi-emacs--show-connecting-affordances (current-buffer))))
              ;; With transport ready, show-connecting-affordances must NOT have fired.
              (should (null (psi-emacs-state-projection-footer psi-emacs--state))))
          (when (process-live-p mock-process)
            (delete-process mock-process)))))))

(ert-deftest psi-open-buffer-reseeds-connecting-footer-when-transport-handshaking ()
  "open-buffer re-entry must seed \"connecting...\" when transport is handshaking.

The guard only suppresses the seed when transport is \\='ready.  During
handshaking (reconnect or initial startup race) the seed should still fire
so the user sees the connecting affordance."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      (psi-emacs--ensure-startup-banner)
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      (let* ((mock-client (psi-rpc-make-client
                           :process-state 'running
                           :transport-state 'handshaking))
             (mock-process (psi-test--spawn-long-lived-process)))
        (setf (psi-rpc-client-process mock-client) mock-process)
        (setf (psi-emacs-state-rpc-client psi-emacs--state) mock-client)
        (setf (psi-emacs-state-transport-state psi-emacs--state) 'handshaking)
        (setf (psi-emacs-state-process psi-emacs--state) mock-process)
        (unwind-protect
            (progn
              (should (null (psi-emacs-state-projection-footer psi-emacs--state)))
              (should (null (psi-emacs--region-bounds 'projection 'main)))
              ;; Apply the guard condition as open-buffer would.
              (let ((transport-state (psi-emacs-state-transport-state psi-emacs--state))
                    (state psi-emacs--state))
                (when (and (not (eq transport-state 'ready))
                           (null (psi-emacs-state-projection-footer state))
                           (null (psi-emacs--region-bounds 'projection 'main)))
                  (psi-emacs--show-connecting-affordances (current-buffer))))
              ;; During handshaking the seed must fire.
              (should (equal "connecting..."
                             (psi-emacs-state-projection-footer psi-emacs--state))))
          (when (process-live-p mock-process)
            (delete-process mock-process)))))))

(provide 'psi-buffer-lifecycle-test)

;;; psi-buffer-lifecycle-test.el ends here
