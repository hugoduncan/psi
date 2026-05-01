;;; psi-delegate-command-and-result-test.el --- Delegate command/result transcript rendering -*- lexical-binding: t; -*-

(require 'ert)
(require 'psi)

(ert-deftest psi-delegate-command-and-bridge-both-render ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "command-result")
       (:data . ((:type . "text")
                 (:message . "Delegated to lambda-build — run run-1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:role . "user")
                 (:text . "Workflow run run-1 result:")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:role . "assistant")
                 (:text . "result text")))))
    (let ((buf (buffer-string)))
      (should (string-match-p (regexp-quote "ψ: Delegated to lambda-build — run run-1") buf))
      (should (string-match-p (regexp-quote "User: Workflow run run-1 result:") buf))
      (should (string-match-p (regexp-quote "ψ: result text") buf)))))

(provide 'psi-delegate-command-and-result-test)
;;; psi-delegate-command-and-result-test.el ends here
