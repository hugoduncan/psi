;;; psi-delegate-transcript-role-test.el --- External user-role assistant/message handling -*- lexical-binding: t; -*-

(require 'ert)
(require 'psi)

(ert-deftest psi-external-user-role-renders-as-user-transcript-line ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:role . "user")
                 (:text . "Workflow run r1 result:")))))
    (let ((buf (buffer-string)))
      (should (string-match-p (regexp-quote "User: Workflow run r1 result:") buf))
      (should-not (string-match-p (regexp-quote "ψ: Workflow run r1 result:") buf)))))

(provide 'psi-delegate-transcript-role-test)
;;; psi-delegate-transcript-role-test.el ends here
