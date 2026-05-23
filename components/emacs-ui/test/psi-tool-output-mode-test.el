;;; psi-tool-output-mode-test.el --- Tool output mode tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)

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
    (should (string-match-p "t-hdr pending" (buffer-string)))
    ;; Body text should NOT be present in collapsed mode
    (should-not (string-match-p "body content here" (buffer-string)))))

(ert-deftest psi-emacs-test-collapsed-rows-show-live-status-updates ()
  "AC5: Collapsed mode updates header stage on lifecycle events."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "t-live pending" (buffer-string)))
    ;; Advance to executing stage
    (psi-emacs--handle-rpc-event
     '((:event . "tool/executing") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "t-live running" (buffer-string)))
    ;; Previous stage header should be replaced
    (should-not (string-match-p "t-live pending" (buffer-string)))))

(ert-deftest psi-emacs-test-accumulated-output-visible-after-expand ()
  "AC6: Collapsed rows accumulate output; toggle to expanded reveals full text."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Inject start/executing/result events in collapsed mode
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-acc") (:text . "first")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/executing") (:data . ((:tool-id . "t-acc") (:text . "second")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-acc") (:result-text . "final")))))
    ;; Collapsed: body text not visible
    (should-not (string-match-p "first" (buffer-string)))
    ;; Toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    ;; Expanded: accumulated text visible below the header line
    (let* ((buf (buffer-string))
           (header (string-match-p "t-acc success\n" buf))
           (body (string-match-p "\nfinal\n" buf)))
      (should header)
      (should body)
      (should (< header body)))))

(ert-deftest psi-emacs-test-expanded-tool-output-keeps-header-and-body-on-separate-lines ()
  "Expanded rows keep header/status on one line and body output below it."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-separate")
                 (:tool-name . "bash")
                 (:arguments . "{\"command\":\"ls\"}")
                 (:result-text . "line one\nline two")))))
    (psi-emacs-toggle-tool-output-view)
    (let ((text (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p (regexp-quote "$ ls success\nCall\nTool: bash\nArguments: ((command . \"ls\"))\nResponse\nline one\nline two\n") text)))))

(ert-deftest psi-emacs-test-toggle-expanded-preserves-header-faces ()
  "Expanding keeps summary and status faces confined to the header line."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-face")
                 (:tool-name . "bash")
                 (:arguments . "{\"command\":\"ls\"}")
                 (:result-text . "body text")))))
    (psi-emacs-toggle-tool-output-view)
    (goto-char (point-min))
    (search-forward "$ ls")
    (let ((summary-face (get-text-property (1- (point)) 'face)))
      (search-forward "success")
      (let ((status-face (get-text-property (1- (point)) 'face)))
        (search-forward "body text")
        (let ((body-face (get-text-property (- (point) (length "text")) 'face))
              (body-font-lock-face (get-text-property (- (point) (length "text")) 'font-lock-face)))
          (should summary-face)
          (should status-face)
          (should (eq summary-face 'psi-emacs-tool-call-face))
          (should (eq status-face 'psi-emacs-tool-success-face))
          (should (eq body-face 'psi-emacs-tool-output-face))
          (should (eq body-font-lock-face 'psi-emacs-tool-output-face)))))))

(ert-deftest psi-emacs-test-toggle-preserves-adjacent-tool-rows ()
  "Toggling expanded/collapsed must not make adjacent tool rows disappear."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-1") (:tool-name . "bash") (:arguments . "{\"command\":\"echo 1\"}") (:result-text . "ok1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-2") (:tool-name . "bash") (:arguments . "{\"command\":\"echo 2\"}") (:result-text . "ok2")))))
    (psi-emacs-toggle-tool-output-view)
    (psi-emacs-toggle-tool-output-view)
    (let ((text (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p (regexp-quote "$ echo 1 success") text))
      (should (string-match-p (regexp-quote "$ echo 2 success") text))
      (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))))

(ert-deftest psi-emacs-test-tool-row-after-frozen-assistant-line-does-not-inherit-prefix-overlay ()
  "Tool rows inserted after a frozen assistant line must not inherit the ψ: prefix overlay face."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Create a live assistant line so a ψ: prefix overlay exists.
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "I'll inspect the tree")))))
    ;; Tool boundary freezes that line, then inserts a tool row immediately after it.
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-overlay")
                 (:tool-name . "bash")
                 (:arguments . "{\"command\":\"ls\"}")
                 (:result-text . "out")))))
    (psi-emacs-toggle-tool-output-view)
    (let ((text (buffer-string)))
      (goto-char (point-min))
      (search-forward "$ ls")
      (backward-char 1)
      (let ((faces (list (face-at-point nil t)
                         (get-char-property (point) 'face)
                         (get-char-property (point) 'font-lock-face))))
        (should-not (member 'psi-emacs-assistant-reply-face faces))
        (should (eq (nth 2 faces) 'psi-emacs-tool-call-face))))))

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
    (should (string-match-p "t-err error" (buffer-string)))
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

(ert-deftest psi-emacs-test-expanded-tool-details-show-full-call-and-toggle-closed ()
  "Expanded tool details show full call arguments before response and collapse globally."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((long-command "printf 'alpha beta gamma delta epsilon zeta eta theta iota kappa lambda'"))
      (psi-emacs--handle-rpc-event
       `((:event . "tool/result")
         (:data . ((:tool-id . "t-full-call")
                   (:tool-name . "bash")
                   (:arguments . ,(format "{\"command\":%S,\"nested\":{\"keep\":[1,2,3]}}" long-command))
                   (:call-summary . "$ printf 'alpha beta…")
                   (:result-text . "command output")))))
      (let ((collapsed (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p (regexp-quote "$ printf 'alpha beta… success") collapsed))
        (should-not (string-match-p "Call" collapsed))
        (should-not (string-match-p (regexp-quote long-command) collapsed))
        (should-not (string-match-p "command output" collapsed)))
      (psi-emacs-toggle-tool-output-view)
      (let ((expanded (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p "Call" expanded))
        (should (string-match-p "Tool: bash" expanded))
        (should (string-match-p (regexp-quote long-command) expanded))
        (should (string-match-p "nested" expanded))
        (should (string-match-p "Response" expanded))
        (should (string-match-p "command output" expanded)))
      (psi-emacs-toggle-tool-output-view)
      (let ((closed (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p (regexp-quote "$ printf 'alpha beta… success") closed))
        (should-not (string-match-p "Call" closed))
        (should-not (string-match-p (regexp-quote long-command) closed))
        (should-not (string-match-p "command output" closed))))))


(ert-deftest psi-emacs-test-expanded-tool-details-suppress-raw-fallback-for-canonically-equivalent-args ()
  "Equivalent complete parsed/raw args suppress unnecessary raw fallback."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((raw-arguments "{\"command\":\"echo alpha\",\"nested\":{\"path\":\"a/b\",\"count\":2}}"))
      (psi-emacs--handle-rpc-event
       `((:event . "tool/result")
         (:data . ((:tool-id . "t-equivalent")
                   (:tool-name . "bash")
                   (:arguments . ,raw-arguments)
                   (:parsed-args . ((command . "echo alpha")
                                    (nested . ((path . "a/b")
                                               (count . 2)))))
                   (:call-summary . "$ echo alpha")
                   (:result-text . "alpha")))))
      (psi-emacs-toggle-tool-output-view)
      (let ((expanded (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p "Call" expanded))
        (should (string-match-p "Tool: bash" expanded))
        (should (string-match-p (regexp-quote "Arguments: ((command . \"echo alpha\")") expanded))
        (should (string-match-p "nested" expanded))
        (should-not (string-match-p "Raw arguments:" expanded))
        (should (string-match-p "Response" expanded))
        (should (string-match-p "alpha" expanded))))))

(ert-deftest psi-emacs-test-expanded-tool-details-include-raw-fallback-for-projected-parsed-args ()
  "Expanded details include raw fallback when parsed args omit raw invocation fields."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((raw-arguments "{\"path\":\"mementum/state.md\",\"offset\":5,\"limit\":13,\"reason\":\"audit full invocation\"}"))
      (psi-emacs--handle-rpc-event
       `((:event . "tool/result")
         (:data . ((:tool-id . "t-projected")
                   (:tool-name . "psi-tool")
                   (:arguments . ,raw-arguments)
                   (:parsed-args . ((path . "mementum/state.md")))
                   (:call-summary . "psi-tool mementum/state.md…")
                   (:result-text . "tool output")))))
      (psi-emacs-toggle-tool-output-view)
      (let ((expanded (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p "Call" expanded))
        (should (string-match-p "Tool: psi-tool" expanded))
        (should (string-match-p (regexp-quote "Arguments: ((path . \"mementum/state.md\"))") expanded))
        (should (string-match-p (regexp-quote "Raw arguments: {\"path\":\"mementum/state.md\",\"offset\":5,\"limit\":13,\"reason\":\"audit full invocation\"}") expanded))
        (should (string-match-p "Response" expanded))
        (should (string-match-p "tool output" expanded))))))

(ert-deftest psi-emacs-test-expanded-tool-details-show-explicit-nil-arguments-and-toggle-closed ()
  "Expanded Call details show an explicit nil marker when no arguments exist."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-no-args")
                 (:tool-name . "no-arg-tool")
                 (:call-summary . "no-arg-tool")
                 (:result-text . "done")))))
    (let ((collapsed (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p (regexp-quote "no-arg-tool success") collapsed))
      (should-not (string-match-p "Call" collapsed))
      (should-not (string-match-p "Arguments: nil" collapsed))
      (should-not (string-match-p "done" collapsed)))
    (psi-emacs-toggle-tool-output-view)
    (let ((expanded (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p "Call" expanded))
      (should (string-match-p "Tool: no-arg-tool" expanded))
      (should (string-match-p "Arguments: nil" expanded))
      (should (string-match-p "Response" expanded))
      (should (string-match-p "done" expanded)))
    (psi-emacs-toggle-tool-output-view)
    (let ((closed (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p (regexp-quote "no-arg-tool success") closed))
      (should-not (string-match-p "Call" closed))
      (should-not (string-match-p "Arguments: nil" closed))
      (should-not (string-match-p "done" closed)))))

(ert-deftest psi-emacs-test-expanded-tool-details-show-invalid-raw-arguments-verbatim ()
  "Expanded Call details display unparseable raw arguments verbatim."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((invalid-raw "{\"command\": \"echo alpha\", trailing-garbage"))
      (psi-emacs--handle-rpc-event
       `((:event . "tool/result")
         (:data . ((:tool-id . "t-invalid-raw")
                   (:tool-name . "bash")
                   (:arguments . ,invalid-raw)
                   (:call-summary . "$ echo alpha")
                   (:result-text . "alpha")))))
      (psi-emacs-toggle-tool-output-view)
      (let ((expanded (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p "Call" expanded))
        (should (string-match-p "Tool: bash" expanded))
        (should (string-match-p (regexp-quote (concat "Arguments: " invalid-raw)) expanded))
        (should-not (string-match-p (regexp-quote "Arguments: ((command . \"echo alpha\"))") expanded))
        (should (string-match-p "Response" expanded))
        (should (string-match-p "alpha" expanded))))))


(provide 'psi-tool-output-mode-test)

;;; psi-tool-output-mode-test.el ends here
