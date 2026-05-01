;;; psi-delegate-live-sequence-test.el --- Delegate live-sequence transcript tests -*- lexical-binding: t; -*-

(require 'ert)
(require 'psi)

(defun psi-test--delegate-live-sequence-buffer-string (events)
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Match the real frontend condition: command-result arrives before session id
    ;; is necessarily pinned by session/updated, then later bridge messages arrive.
    (dolist (frame events)
      (psi-emacs--handle-rpc-event frame))
    (buffer-string)))

(ert-deftest psi-delegate-live-sequence-renders-ack-user-marker-and-result-text ()
  (let* ((buf (psi-test--delegate-live-sequence-buffer-string
               '((( :event . "command-result")
                  (:data . ((:type . "text")
                            (:message . "Delegated to lambda-build — run run-1"))))
                 ((:event . "assistant/message")
                  (:data . ((:session-id . "s1")
                            (:role . "user")
                            (:text . "Workflow run run-1 result:"))))
                 ((:event . "assistant/message")
                  (:data . ((:session-id . "s1")
                            (:role . "assistant")
                            (:text . "result text"))))))))
    (should (string-match-p (regexp-quote "ψ: Delegated to lambda-build — run run-1") buf))
    (should (string-match-p (regexp-quote "User: Workflow run run-1 result:") buf))
    (should (string-match-p (regexp-quote "ψ: result text") buf))))

(ert-deftest psi-delegate-live-sequence-renders-assistant-result-from-content-blocks ()
  (let* ((buf (psi-test--delegate-live-sequence-buffer-string
               '((( :event . "command-result")
                  (:data . ((:type . "text")
                            (:message . "Delegated to lambda-build — run run-1"))))
                 ((:event . "assistant/message")
                  (:data . ((:session-id . "s1")
                            (:role . "user")
                            (:text . "Workflow run run-1 result:"))))
                 ((:event . "assistant/message")
                  (:data . ((:session-id . "s1")
                            (:role . "assistant")
                            (:content . [((:type . :text)
                                          (:text . "λx.use(munera, track(work)) ∧ use(mementum, track(state ∧ knowledge))"))]))))))))
    (should (string-match-p (regexp-quote "ψ: Delegated to lambda-build — run run-1") buf))
    (should (string-match-p (regexp-quote "User: Workflow run run-1 result:") buf))
    (should (string-match-p
             (regexp-quote "ψ: λx.use(munera, track(work)) ∧ use(mementum, track(state ∧ knowledge))")
             buf))))

(provide 'psi-delegate-live-sequence-test)
;;; psi-delegate-live-sequence-test.el ends here
