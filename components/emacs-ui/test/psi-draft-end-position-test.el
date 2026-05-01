;;; psi-draft-end-position-test.el --- Regression tests for psi-emacs--draft-end-position  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)

(ert-deftest psi-draft-end-position-uses-last-projection-start-when-region-deleted ()
  "draft-end-position falls back to last-projection-start when projection text is deleted.

Regression: during psi-emacs--upsert-projection-block the projection text is
deleted and the region unregistered before reinsertion.  after-change hooks can
call draft-end-position in that window; without the snapshot fallback it returns
point-max and captures footer content as part of the composed input."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      ;; Build a minimal buffer: transcript + draft + footer.
      (insert "transcript\n")
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      (psi-emacs--ensure-input-area)
      (goto-char (psi-emacs--draft-end-position))
      (insert "my draft")
      ;; Simulate a registered projection block after the draft.
      (let* ((footer-start (point-max))
             (footer-text "\n─────\nfooter content\n"))
        (save-excursion
          (goto-char footer-start)
          (insert footer-text))
        (psi-emacs--region-register 'projection 'main footer-start (point-max))
        ;; Confirm normal path: draft-end-position == projection start.
        (should (= footer-start (psi-emacs--draft-end-position)))
        ;; Simulate the full delete+unregister step of upsert-projection-block:
        ;; snapshot the start, delete the text (removes text props too), then
        ;; unregister (nil markers + remove hash entry).
        (setf (psi-emacs-state-last-projection-start psi-emacs--state) footer-start)
        (delete-region footer-start (point-max))
        (psi-emacs--region-unregister 'projection 'main)
        ;; Both marker and text-property recovery paths are gone.
        (should (null (psi-emacs--region-bounds 'projection 'main)))
        ;; Snapshot must hold the line.
        (should (= footer-start (psi-emacs--draft-end-position)))
        ;; Composed text must not include footer content.
        (should (equal "my draft" (psi-emacs--composed-text)))))))

(ert-deftest psi-draft-end-position-clears-snapshot-after-reregistration ()
  "last-projection-start is cleared once the live projection region is back.

Ensures the snapshot does not linger as stale state after a full upsert cycle."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((inhibit-read-only t))
      (insert "transcript\n")
      (setf (psi-emacs-state-draft-anchor psi-emacs--state)
            (copy-marker (point-max) nil))
      (psi-emacs--ensure-input-area)
      ;; Plant a stale snapshot at an arbitrary position.
      (setf (psi-emacs-state-last-projection-start psi-emacs--state) 1)
      ;; Register a fresh projection region at the real end.
      (let ((proj-start (point-max)))
        (save-excursion
          (goto-char proj-start)
          (insert "\n─────\nfooter\n"))
        (psi-emacs--region-register 'projection 'main proj-start (point-max))
        ;; Simulate the clear that upsert-projection-block performs post-register.
        (setf (psi-emacs-state-last-projection-start psi-emacs--state) nil)
        ;; Snapshot must be gone; live region governs.
        (should (null (psi-emacs-state-last-projection-start psi-emacs--state)))
        (should (= proj-start (psi-emacs--draft-end-position)))))))

(provide 'psi-draft-end-position-test)

;;; psi-draft-end-position-test.el ends here
