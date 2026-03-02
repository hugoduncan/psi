;;; psi-rpc-test.el --- Tests for psi rpc-edn transport  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-rpc)

(defun psi-rpc-test--spawn-cat (_command)
  "Spawn a long-lived process used in rpc transport tests."
  (make-process
   :name (format "psi-rpc-test-%s" (gensym))
   :command '("cat")
   :buffer nil
   :noquery t
   :connection-type 'pipe))

(ert-deftest psi-rpc-parse-line-valid-edn-frame ()
  (pcase (psi-rpc--parse-line "{:id \"1\" :kind :event :event \"assistant/delta\" :data {:text \"hi\"}}")
    (`(:ok ,frame)
     (should (equal "1" (alist-get :id frame)))
     (should (eq :event (alist-get :kind frame)))
     (should (equal "assistant/delta" (alist-get :event frame)))
     (should (equal "hi" (alist-get :text (alist-get :data frame)))))
    (_ (ert-fail "expected successful parse"))))

(ert-deftest psi-rpc-parse-line-invalid-edn-frame ()
  (pcase (psi-rpc--parse-line "not-edn")
    (`(:error ,message)
     (should (stringp message)))
    (_ (ert-fail "expected parse error"))))

(ert-deftest psi-rpc-parse-line-edn-set-supported ()
  (pcase (psi-rpc--parse-line
          "{:id \"1\" :kind :response :op \"handshake\" :ok true :data {:server-info {:features #{\"a\" \"b\"}}}}")
    (`(:ok ,frame)
     (let* ((data (alist-get :data frame))
            (server-info (alist-get :server-info data))
            (features (alist-get :features server-info)))
       (should (listp features))
       (should (member "a" features))
       (should (member "b" features))))
    (_ (ert-fail "expected successful parse"))))

(ert-deftest psi-rpc-send-request-escapes-multiline-strings ()
  (let* ((captured nil)
         (process (psi-rpc-test--spawn-cat '("cat")))
         (client (psi-rpc-make-client
                  :process process
                  :send-function (lambda (_proc payload)
                                   (setq captured payload)))))
    (unwind-protect
        (progn
          (psi-rpc-send-request! client "prompt" '((:message . "line-1\nline-2")))
          (should (stringp captured))
          (should (string-suffix-p "\n" captured))
          (let ((body (string-trim-right captured)))
            (should (string-match-p "\\\\n" body))
            (should-not (string-match-p "\n" body))
            (pcase (psi-rpc--parse-line body)
              (`(:ok ,frame)
               (should (equal "line-1\nline-2"
                              (alist-get :message (alist-get :params frame)))))
              (_ (ert-fail "expected successful parse")))))
      (when (process-live-p process)
        (delete-process process)))))

(ert-deftest psi-rpc-process-filter-split-chunk-frame-dispatches-once ()
  (let* ((events nil)
         (errors nil)
         (client (psi-rpc-make-client
                  :on-event (lambda (frame)
                              (push frame events))
                  :on-rpc-error (lambda (code message frame)
                                  (push (list code message frame) errors))))
         (process (psi-rpc-test--spawn-cat '("cat")))
         (frame-line "{:id \"evt-1\" :kind :event :event \"assistant/delta\" :data {:text \"hi\"}}\n")
         (split-at 24)
         (chunk-a (substring frame-line 0 split-at))
         (chunk-b (substring frame-line split-at)))
    (unwind-protect
        (progn
          (process-put process 'psi-rpc-client client)

          (psi-rpc-process-filter process chunk-a)
          (should (null events))
          (should (null errors))

          (psi-rpc-process-filter process chunk-b)
          (should (= 1 (length events)))
          (should (equal "evt-1" (alist-get :id (car events))))
          (should (equal "assistant/delta" (alist-get :event (car events))))
          (should (equal "hi" (alist-get :text (alist-get :data (car events)))))
          (should (null errors))
          (should (string-empty-p (psi-rpc-client-line-buffer client))))
      (when (process-live-p process)
        (delete-process process)))))

(ert-deftest psi-rpc-request-correlation-success-and-error ()
  (let* ((client (psi-rpc-make-client))
         (hits nil)
         (process (psi-rpc-test--spawn-cat '("cat"))))
    (unwind-protect
        (progn
          (setf (psi-rpc-client-process client) process)
          (psi-rpc-send-request! client "ping" nil (lambda (frame) (push (cons :ok frame) hits)))
          (psi-rpc-send-request! client "prompt" '((:message . "hi")) (lambda (frame) (push (cons :err frame) hits)))

          (psi-rpc--handle-frame
           client
           '((:id . "req-1") (:kind . :response) (:op . "ping") (:ok . t) (:data . ((:pong . t)))))
          (psi-rpc--handle-frame
           client
           '((:id . "req-2") (:kind . :error) (:op . "prompt") (:error-code . "runtime/failed") (:error-message . "boom")))

          (should (= 2 (length hits)))
          (should (null (gethash "req-1" (psi-rpc-client-pending-callbacks client))))
          (should (null (gethash "req-2" (psi-rpc-client-pending-callbacks client)))))
      (when (process-live-p process)
        (delete-process process)))))

(ert-deftest psi-rpc-startup-lifecycle-ready-path ()
  (let* ((states nil)
         (errors nil)
         (client (psi-rpc-make-client
                  :on-state-change (lambda (c)
                                     (push (list (psi-rpc-client-process-state c)
                                                 (psi-rpc-client-transport-state c))
                                           states))
                  :on-rpc-error (lambda (code message _frame)
                                  (push (list code message) errors))))
         (process nil))
    (setf (psi-rpc-client-send-function client)
          (lambda (_proc payload)
            (pcase (psi-rpc--parse-line (string-trim-right payload))
              (`(:ok ,frame)
               (let ((id (alist-get :id frame))
                     (op (alist-get :op frame)))
                 (cond
                  ((equal op "handshake")
                   (psi-rpc--handle-frame
                    client
                    `((:id . ,id) (:kind . :response) (:op . "handshake") (:ok . t)
                      (:data . ((:server-info . ((:protocol-version . "1.0"))))))))
                  ((equal op "subscribe")
                   (psi-rpc--handle-frame
                    client
                    `((:id . ,id) (:kind . :response) (:op . "subscribe") (:ok . t)
                      (:data . ((:subscribed . ["assistant/delta"]))))))))))))
    (unwind-protect
        (progn
          (psi-rpc-start! client #'psi-rpc-test--spawn-cat '("cat"))
          (setq process (psi-rpc-client-process client))
          (sleep-for 0.02)
          (should (equal '(running ready)
                         (list (psi-rpc-client-process-state client)
                               (psi-rpc-client-transport-state client))))
          (should (equal "1.0" (psi-rpc-client-protocol-version client)))
          (should (equal psi-rpc-mvp-topics (psi-rpc-client-subscribed-topics client)))
          (should (null errors))
          (should (member '(running handshaking) states))
          (should (member '(running ready) states)))
      (when (and process (process-live-p process))
        (delete-process process)))))

(ert-deftest psi-rpc-startup-lifecycle-handshake-error-path ()
  (let* ((errors nil)
         (client (psi-rpc-make-client
                  :on-rpc-error (lambda (code message _frame)
                                  (push (list code message) errors))))
         (process nil))
    (setf (psi-rpc-client-send-function client)
          (lambda (_proc payload)
            (pcase (psi-rpc--parse-line (string-trim-right payload))
              (`(:ok ,frame)
               (let ((id (alist-get :id frame))
                     (op (alist-get :op frame)))
                 (when (equal op "handshake")
                   (psi-rpc--handle-frame
                    client
                    `((:id . ,id) (:kind . :error) (:op . "handshake")
                      (:error-code . "protocol/unsupported-version")
                      (:error-message . "unsupported protocol")))))))))
    (unwind-protect
        (progn
          (psi-rpc-start! client #'psi-rpc-test--spawn-cat '("cat"))
          (setq process (psi-rpc-client-process client))
          (sleep-for 0.02)
          (should (equal '(running disconnected)
                         (list (psi-rpc-client-process-state client)
                               (psi-rpc-client-transport-state client))))
          (should (= 1 (length errors)))
          (should (equal "protocol/unsupported-version" (caar errors))))
      (when (and process (process-live-p process))
        (delete-process process)))))

(provide 'psi-rpc-test)

;;; psi-rpc-test.el ends here
