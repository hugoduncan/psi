;;; psi-streaming-transcript-test.el --- Streaming transcript tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

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
          (should (equal "ψ: Hello\n" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "ψ: Hello world\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-finalize-preserves-cursor-when-user-typing-in-input-area ()
  "Finalize must preserve cursor offset within the input area.

If the user types \"hello\" then positions cursor between \\='h\\=' and \\='e\\='
while the agent is streaming, the cursor must stay between \\='h\\=' and \\='e\\='
after the turn completes.  Transcript inserts above the separator shift absolute
positions, so we assert on offset-from-input-start rather than absolute point.

The old implementation called (goto-char (draft-end-position)) unconditionally
when the anchor was at-end, which jumped the cursor to the end of the draft."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; User types "hello" in the input area
          (goto-char (psi-emacs--draft-end-position))
          (let ((inhibit-read-only t))
            (insert "hello"))
          ;; User moves cursor mid-word: 1 char into the input text (after 'h')
          (goto-char (+ (psi-emacs--input-start-position) 1))
          (let ((saved-input-offset 1))
            ;; Agent streams and finalizes
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/delta") (:data . ((:text . "reply")))))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/message") (:data . ((:text . "reply done")))))
            ;; Cursor offset within the input area must be preserved.
            (let ((offset-after (- (point) (psi-emacs--input-start-position))))
              (should (= saved-input-offset offset-after)))
            ;; Draft text must be intact and unmodified
            (should (equal "hello" (psi-emacs--tail-draft-text)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-renders-line-and-archives-on-finalize ()
  "Thinking line must remain visible after reply is finalized (archived, not cleared).

The thinking block is part of the transcript — it should be frozen in place
when the assistant reply arrives, not deleted."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan")))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "plan" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (string-match-p "· plan" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "done")))))
          ;; in-progress state cleared
          (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
          (should-not (psi-emacs-state-thinking-range psi-emacs--state))
          ;; thinking line archived — still visible in buffer
          (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))
          (should (string-match-p "· plan" (buffer-string)))
          (should (string-match-p "ψ: done" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-multiple-deltas-update-in-place ()
  "Multiple thinking deltas must update a single line in-place, not produce multiple lines.

The backend emits the full accumulated thinking text in each event (replace
semantics).  Each successive event carries more text than the previous one."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I think")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I think more")))))
          (should (equal "I think more" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          ;; Exactly one thinking line in the buffer — not one per delta
          (should (equal 1 (cl-count-if (lambda (line) (string-prefix-p "· " line))
                                        (split-string (buffer-string) "\n" t))))
          (should (string-match-p "· I think more" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-cumulative-prefix-snapshots-replace-single-line ()
  "Cumulative thinking snapshots that extend one character at a time stay on one line.

This matches the backend contract for `assistant/thinking-delta`, which emits
full accumulated thinking text rather than append-only chunks."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (dolist (text '("- TUII found the main doc drift:"
                          "- TUI commandI found the main doc drift:"
                          "- TUI command docsI found the main doc drift:"
                          "- TUI command docs don'tI found the main doc drift:"
                          "- TUI command docs don't mention `/work-*`"))
            (psi-emacs--handle-rpc-event
             `((:event . "assistant/thinking-delta") (:data . ((:text . ,text))))))
          (should (equal "- TUI command docs don't mention `/work-*`"
                         (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (equal 1 (cl-count-if (lambda (line) (string-prefix-p "· " line))
                                        (split-string (buffer-string) "\n" t))))
          (should (equal (format "· %s\n"
                                 "- TUI command docs don't mention `/work-*`")
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-uses-thinking-face-on-prefix ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "x")))))
          (goto-char (point-min))
          (let ((ovs (overlays-at (point)))
                (found nil))
            (dolist (ov ovs)
              (when (eq (overlay-get ov 'face) 'psi-emacs-assistant-thinking-face)
                (setq found t)))
            (should found)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-tool-event-starts-new-thinking-block ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-break") (:text . "start")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (should (equal "plan-2" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))
          (should (equal "· plan-1\nt-break pending\n· plan-2\n"
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-assistant-line-after-tool-insertion-keeps-late-thinking-block-ordered-before-final-reply ()
  "Thinking blocks emitted after an assistant line has started must stay in transcript order.

Second thinking blocks (after a tool event) should render before the finalized
reply that comes later in the same assistant turn, and must preserve the
thinking prefix.

"
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "reply-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash") (:arguments . "{\\\"command\\\":\\\"ls\\\"}")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "reply-2")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "final reply")))))
          (let ((buf (buffer-substring-no-properties
                      (point-min)
                      (psi-emacs--input-separator-position))))
            (should (string-match-p "\n· plan-2\n" buf))
            (let* ((plan-2-idx (string-match "\n· plan-2\n" buf))
                   (final-idx (string-match "\nψ: final reply\n" buf)))
              (should (and plan-2-idx final-idx))
              (should (< plan-2-idx final-idx)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))


(ert-deftest psi-thinking-streaming-tool-event-clears-stale-thinking-text-when-range-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Simulate stale state where thinking text remains but live marker range was lost.
          (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) "plan-1")
          (setf (psi-emacs-state-thinking-range psi-emacs--state) nil)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-break") (:text . "start")))))
          (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
          (should-not (psi-emacs-state-thinking-range psi-emacs--state))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (should (equal "plan-2" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (equal "t-break pending\n· plan-2\n"
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-recovers-span-from-region-identity-when-range-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "Hello")))))
    (let ((assistant-id (psi-emacs-state-active-assistant-id psi-emacs--state)))
      (should assistant-id)
      (should (psi-emacs--region-bounds 'assistant assistant-id))
      ;; Simulate lost compatibility cache while property identity remains in buffer.
      (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
      (psi-emacs--handle-rpc-event
       '((:event . "assistant/delta") (:data . ((:text . " world")))))
      (let* ((content (buffer-substring-no-properties
                       (point-min)
                       (psi-emacs--input-separator-position)))
             (assistant-lines (cl-remove-if-not
                               (lambda (line) (string-prefix-p "ψ: " line))
                               (split-string content "\n" t))))
        (should (= 1 (length assistant-lines)))
        (should (equal "ψ: Hello world" (car assistant-lines)))
        (should (psi-emacs--assistant-range-current))))))

(ert-deftest psi-assistant-streaming-cumulative-snapshots-replace-in-place ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "H")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "He")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
          (should (equal "Hel" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: Hel\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-cumulative-snapshots-with-tail-newline-churn-do-not-duplicate ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "H\n")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "He\n")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel\n")))))
          (should (equal "Hel\n" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: Hel\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-incremental-short-prefix-delta-does-not-shrink-buffer ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Reproduces the real regression where a short incremental delta
          ;; ("`") previously replaced the whole in-progress string.
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "`deps.edn")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "`")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " contents:")))))
          (should (equal "`deps.edn` contents:"
                         (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: `deps.edn` contents:\n" (buffer-string))))
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
          (should (equal "ψ: Hello from content\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-structured-content-finalizes-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . ((:kind . :structured)
                                    (:blocks . [((:kind . :text)
                                                 (:text . "Hello from structured content"))])))))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "ψ: Hello from structured content\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-error-message-fallback-renders-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [])
                       (:error-message . "Validation failed")))))
          (should (equal "ψ: [error] Validation failed\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-empty-without-streamed-text-noops ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant") (:content . [])))))
          (should (equal "" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-store-fallback-warning-renders-verbatim ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text)
                                     (:text . "⚠ Remembered with store fallback\n  provider: failing-store\n  store-error: boom"))])))))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
          (should (string-match-p "Remembered with store fallback" (buffer-string)))
          (should (string-match-p "provider: failing-store" (buffer-string)))
          (should (string-match-p "store-error: boom" (buffer-string))))
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
          (should (equal "ψ: partial\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-uses-verbatim-properties-until-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "**bold**")))))
          (goto-char (point-min))
          (re-search-forward "\\*\\*bold\\*\\*")
          (let ((start (match-beginning 0)))
            (should (eq t (get-text-property start 'psi-emacs-stream-verbatim))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "**bold** done")))))
          (goto-char (point-min))
          (re-search-forward "\\*\\*bold\\*\\* done")
          (let ((start (match-beginning 0)))
            (should-not (get-text-property start 'psi-emacs-stream-verbatim))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-markdown-processing-runs-only-at-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls nil))
          (cl-letf (((symbol-function 'psi-emacs--apply-finalized-assistant-markdown)
                     (lambda (start end)
                       (push (buffer-substring-no-properties start end) calls))))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/delta") (:data . ((:text . "partial")))))
            (should (equal nil calls))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/message") (:data . ((:text . "final **md**")))))
            (should (equal '("final **md**") calls))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-finalize-keeps-point-in-input-area ()
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
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:result-text . "done")))))
          ;; Default mode is collapsed: buffer shows header-only
          (should (equal "t-1 success\n" (buffer-string)))
          (let ((row (gethash "t-1" (psi-emacs-state-tool-rows psi-emacs--state))))
            (should row)
            (should (equal "result" (plist-get row :stage)))
            (should (equal "done" (plist-get row :text)))
            ;; Accumulated text contains all deltas
            (should (string-match-p "done" (plist-get row :accumulated-text)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-tool-interleaved-preserves-tool-row ()
  "Assistant delta followed by tool event then more delta must not delete the tool row.

Regression: the assistant-range end marker was advancing (insertion-type t),
causing delete-region on the next delta to swallow any tool row inserted
immediately after the assistant line.

The pre-tool assistant text is frozen as its own ψ: line at the tool
boundary.  Post-tool text-deltas create a fresh ψ: line so that the
next turn's reply is not concatenated with stale pre-tool content."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; 1. Assistant starts streaming
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hello")))))
          ;; 2. Tool starts — pre-tool text "Hello" is frozen; tool row appended after it
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          ;; 3. Tool completes
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok") (:is-error . nil)))))
          ;; 4. Post-tool streaming — creates a fresh ψ: line (NOT concatenated with "Hello")
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " world")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            ;; Pre-tool frozen line is visible
            (should (string-match-p "ψ: Hello" content))
            ;; Post-tool fresh line is present
            (should (string-match-p "ψ:  world" content))
            ;; Tool row must still be present — tool rows hash must have t-1
            (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
          ;; 5. Final message — finalizes the post-tool ψ: line; tool row must still be present
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            (should (string-match-p "ψ: Hello world" content))
            ;; Tool row still tracked and rendered
            (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-multiple-tool-rows-survive-update-after-assistant-delta ()
  "Multiple tool rows inserted before a live assistant line must not disappear
when each row is updated (tool/result).

Regression: tool-row update (delete+reinsert) caused the assistant start
marker to drift to the tool-row start position.  The next assistant/delta
then deleted the tool row along with the assistant line."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Assistant starts streaming
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "working")))))
          ;; Two tool rows arrive (both inserted before the assistant line)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          ;; Both complete (update in-place)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok1") (:is-error . nil)))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-2") (:tool-name . "read")
                                                (:result-text . "ok2") (:is-error . nil)))))
          ;; Post-tool text — creates a fresh ψ: line (pre-tool "working" is frozen)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " done")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            ;; Pre-tool frozen line still present
            (should (string-match-p "ψ: working" content))
            ;; Post-tool fresh line present
            (should (string-match-p "ψ:  done" content))
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
          ;; Final message — finalizes the post-tool ψ: line; both tool rows must survive
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "working done")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            (should (string-match-p "ψ: working done" content))
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-parallel-tool-rows-all-survive-sequential-result-updates ()
  "Multiple parallel tool rows (no assistant delta) must all survive when results arrive.

Regression: when two tool rows are adjacent in the buffer (no assistant line
between them), updating the first row via tool/result caused the second row's
:start marker to collapse to the first row's :start position.  The subsequent
tool/result for the second row then deleted both rows (delete-region spanned
both rows) leaving only the second row's updated content."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; Thinking then two parallel tool calls (no assistant/delta between them)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          ;; First tool completes
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok1")))))
          ;; Second tool completes — must not delete first tool row
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-2") (:tool-name . "read")
                                                (:result-text . "ok2")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--input-separator-position))))
            ;; Both tool rows must be present
            (should (string-match-p "· plan" content))
            (should (string-match-p "\\$" content))   ;; bash row
            (should (string-match-p "read" content))  ;; read row
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      nil)))

(ert-deftest psi-tool-row-recovers-from-region-identity-when-markers-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-recover") (:tool-name . "bash")))))
    (let* ((rows (psi-emacs-state-tool-rows psi-emacs--state))
           (row (gethash "t-recover" rows)))
      (should row)
      (should (psi-emacs--region-bounds 'tool-row "t-recover"))
      ;; Simulate lost row markers while text properties remain intact.
      (puthash "t-recover"
               (plist-put (plist-put row :start nil) :end nil)
               rows))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-recover")
                                           (:tool-name . "bash")
                                           (:result-text . "ok")))))
    (let* ((rows (psi-emacs-state-tool-rows psi-emacs--state))
           (row (gethash "t-recover" rows))
           (content (buffer-substring-no-properties
                     (point-min)
                     (psi-emacs--input-separator-position))))
      (should (markerp (plist-get row :start)))
      (should (markerp (plist-get row :end)))
      (should (string-match-p "success" content))
      (should-not (string-match-p "pending" content))
      (should (= 1 (hash-table-count rows))))))

(ert-deftest psi-assistant-delta-not-duplicated-when-tool-row-longer-than-assistant-content ()
  "Assistant streaming text must not be duplicated when a tool row longer than the
current assistant content is inserted before the assistant line.

Regression: inserting a tool row of length R before an assistant line of
length A (where R > A) left the assistant-range end marker BEFORE the new
start marker (inverted range).  Subsequent assistant/delta calls then called
delete-region on the inverted range (which swaps bounds and deletes tool row
content), leaving the stale assistant text in the buffer.  Each new delta
appended to the stale text instead of replacing it, producing repeated/doubled
assistant content.

The tool boundary now freezes the pre-tool assistant line and starts a fresh
ψ: for post-tool deltas.  Content must NOT be doubled (\"TheThe last commit\")."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; Short assistant delta — assistant content is shorter than a typical tool row
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "The")))))
          ;; Tool start — pre-tool text "The" is frozen; a fresh ψ: will be created for
          ;; post-tool deltas.  This also tests that the inverted-range bug does not
          ;; recur: inserting a tool row longer than the frozen line must not corrupt state.
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                               (:arguments . "{\"command\":\"ls -la\"}")))))
          ;; Post-tool delta — must create a fresh ψ: line, not concatenate with "The"
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "The last commit")))))
          (let* ((content (buffer-substring-no-properties
                           (point-min) (psi-emacs--input-separator-position)))
                 (assistant-lines (cl-remove-if-not
                                   (lambda (line) (string-prefix-p "ψ: " line))
                                   (split-string content "\n" t))))
            ;; Two ψ: lines: frozen pre-tool "The" + fresh post-tool "The last commit"
            (should (= 2 (length assistant-lines)))
            (should (equal "ψ: The" (car assistant-lines)))
            ;; Post-tool line shows the delta text, NOT doubled "TheThe last commit"
            (should (equal "ψ: The last commit" (cadr assistant-lines)))))
      nil)))

(ert-deftest psi-tool-boundary-freezes-pre-tool-text-as-separate-psi-line ()
  "Pre-tool streaming text must be frozen at the tool boundary as its own ψ: line.

Regression: in a multi-turn agent loop, text-delta events from turn N were
accumulated in `assistant-in-progress'.  When turn N+1's text-delta arrived
after tool execution, `merge-stream-text' concatenated the old and new text,
causing turn N's pre-tool planning/preamble text to appear in the ψ: prefix
alongside (or instead of) the actual reply."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    ;; Pre-tool text (model streams planning text before calling a tool)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "I'll run bash to check")))))
    ;; Tool boundary — pre-tool text must be frozen here
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t1") (:tool-name . "bash")
                                          (:result-text . "ok") (:content . []) (:is-error . nil)))))
    ;; Post-tool text (next turn's reply — must NOT concatenate with pre-tool text)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "The result is ok")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message") (:data . ((:text . "The result is ok")))))
    (let* ((content (buffer-substring-no-properties
                     (point-min) (psi-emacs--input-separator-position)))
           (lines (split-string content "\n" t))
           (psi-lines (cl-remove-if-not (lambda (l) (string-prefix-p "ψ: " l)) lines)))
      ;; Two ψ: lines: frozen pre-tool + finalized post-tool
      (should (= 2 (length psi-lines)))
      (should (equal "ψ: I'll run bash to check" (car psi-lines)))
      (should (equal "ψ: The result is ok" (cadr psi-lines)))
      ;; Pre-tool text must NOT appear in the final ψ: line
      (should-not (string-match-p "bash to check" (cadr psi-lines))))))

(ert-deftest psi-tool-boundary-freeze-does-not-affect-thinking-line-ordering ()
  "Freeze at tool boundary must not disturb thinking-line ordering.

When a turn has thinking → text → tool: thinking must appear before the
frozen pre-tool text, and post-tool thinking must appear before the final
ψ: reply line."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "pre-tool")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t1") (:tool-name . "bash")
                                          (:result-text . "ok") (:content . []) (:is-error . nil)))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "final reply")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message") (:data . ((:text . "final reply")))))
    (let* ((content (buffer-substring-no-properties
                     (point-min) (psi-emacs--input-separator-position)))
           (lines (split-string content "\n" t)))
      ;; Thinking lines use · prefix
      (should (cl-some (lambda (l) (equal "· plan-1" l)) lines))
      (should (cl-some (lambda (l) (equal "· plan-2" l)) lines))
      ;; No thinking content in ψ: lines
      (dolist (line lines)
        (when (string-prefix-p "ψ: " line)
          (should-not (string-match-p "plan-" line))))
      ;; plan-2 must appear before the final ψ: reply
      (let* ((plan2-idx (cl-position "· plan-2" lines :test #'equal))
             (final-idx (cl-position "ψ: final reply" lines :test #'equal)))
        (should plan2-idx)
        (should final-idx)
        (should (< plan2-idx final-idx))))))


;;; Runtime/header/reconnect tests moved to psi-streaming-runtime-test.el

(provide 'psi-streaming-transcript-test)


