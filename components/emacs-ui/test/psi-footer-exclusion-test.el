;;; psi-footer-exclusion-test.el --- Regression tests for footer exclusion from submitted prompt  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

(ert-deftest psi-send-from-buffer-excludes-footer-from-submitted-prompt ()
  "send-from-buffer must not include footer/projection content in the submitted prompt.

Regression: psi-emacs--draft-end-position fell back to point-max when the
projection region was absent, causing footer text to be captured as part of
the composed input and sent to the backend.

This test verifies the full send-from-buffer path with a live footer: the
submitted RPC message must contain only the user's draft text."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (setf (psi-emacs-state-rpc-client psi-emacs--state)
          (psi-rpc-make-client :process-state 'running :transport-state 'ready))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
    (unwind-protect
        (progn
          (psi-emacs--ensure-startup-banner)
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (point-max) nil))
          ;; Install input separator and render an initial footer.
          (psi-emacs--ensure-input-area)
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "connecting...")
          (psi-emacs--upsert-projection-block)
          ;; User types draft text.
          (goto-char (psi-emacs--draft-end-position))
          (let ((inhibit-read-only t))
            (insert "hello world"))
          ;; Simulate a footer/updated event arriving (triggers upsert cycle).
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "idle · claude-3-5-sonnet")
          (psi-emacs--upsert-projection-block)
          ;; Verify projection region is live and draft is isolated.
          (should (psi-emacs--region-bounds 'projection 'main))
          (should (equal "hello world" (psi-emacs--tail-draft-text)))
          ;; Submit and capture the RPC call.
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (deactivate-mark)
                          (psi-emacs-send-from-buffer nil)))))
            ;; Exactly one RPC call must have been dispatched.
            (should (= 1 (length calls)))
            (let* ((call (car calls))
                   (op (car call))
                   (params (cadr call))
                   (message (cdr (assoc :message params))))
              ;; Operation must be "prompt" (idle state).
              (should (equal "prompt" op))
              ;; Submitted message must be exactly the draft text.
              (should (equal "hello world" message))
              ;; Footer content must not appear anywhere in the submitted message.
              (should (not (string-match-p "claude" (or message ""))))
              (should (not (string-match-p "idle" (or message "")))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-upsert-projection-block-reentrant-call-does-not-corrupt-buffer ()
  "Re-entrant upsert-projection-block must not produce a second projection block.

Scenario: upsert snapshots last-projection-start, deletes the old projection
text, then a re-entrant call arrives before region-unregister fires.  This
simulates what happens when undo-outer-limit-truncate fires display-warning ->
sit-for 0 -> pending RPC process output is processed mid-upsert.

Without the re-entrancy guard the second upsert sees the (now zero-length)
registered region, deletes nothing, inserts a new projection block, registers
it, and the outer upsert then inserts a second block at point-max.  The result
is two footer blocks concatenated after the draft, corrupting draft-end-position
so footer content is captured on the next submit.

The advice injects the re-entrant call immediately after delete-region returns,
gated on last-projection-start being set — the exact window the upsert opens."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      ;; Build: banner + separator + draft + initial footer.
      (psi-emacs--ensure-startup-banner)
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      (psi-emacs--ensure-input-area)
      (goto-char (psi-emacs--draft-end-position))
      (insert "my draft")
      (setf (psi-emacs-state-projection-footer psi-emacs--state) "connecting...")
      (psi-emacs--upsert-projection-block)
      (should (equal "my draft" (psi-emacs--tail-draft-text)))
      (should (psi-emacs--region-bounds 'projection 'main))
      ;; Inject a re-entrant upsert call inside delete-region.
      ;; Gate on last-projection-start being set so the injection fires only
      ;; during the upsert's own projection-block delete, not other deletes.
      (let ((injected nil))
        (advice-add
         'delete-region :around
         (lambda (orig beg end)
           (funcall orig beg end)
           (when (and (not injected)
                      psi-emacs--state
                      (psi-emacs-state-last-projection-start psi-emacs--state))
             (setq injected t)
             (setf (psi-emacs-state-projection-footer psi-emacs--state)
                   "idle · claude-3-5-sonnet")
             (psi-emacs--upsert-projection-block)))
         '((name . psi-test-reentrant-upsert-inject)))
        (unwind-protect
            (progn
              (setf (psi-emacs-state-projection-footer psi-emacs--state) "new footer")
              (psi-emacs--upsert-projection-block))
          (advice-remove 'delete-region 'psi-test-reentrant-upsert-inject)))
      ;; Draft must be intact — not contaminated with any footer content.
      (should (equal "my draft" (psi-emacs--tail-draft-text)))
      ;; Exactly one projection block: region is live and ends at point-max.
      (let ((bounds (psi-emacs--region-bounds 'projection 'main)))
        (should bounds)
        (should (< (car bounds) (cdr bounds)))
        (should (= (cdr bounds) (point-max)))))))

(provide 'psi-footer-exclusion-test)

;;; psi-footer-exclusion-test.el ends here
