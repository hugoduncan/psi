;;; psi-widget-projection-timers-test.el --- Mutation-timer tests for psi-widget-projection  -*- lexical-binding: t; -*-

;;; Commentary:
;; Buffer-local widget mutation watchdog timer tests, split out of
;; psi-widget-projection-test.el to keep each file under the length limit.

;;; Code:

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-widget-renderer)
(require 'psi-widget-projection)

;;; Helpers

(defmacro pwpt--with-state (&rest body)
  "Run BODY with a fresh psi-emacs-state bound as psi-emacs--state."
  `(let ((psi-emacs--state (psi-emacs--initialize-state nil)))
     ,@body))

(defun pwpt--make-button-spec (id key)
  "Build a spec with a single button node."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ((:type . button)
              (:label . "Go")
              (:key . ,key)
              (:disabled . nil)
              (:mutation . ((:name . ext/do-thing)
                            (:params . ((:widget . ,id)))))))))

(defun pwpt--capture-query-sends (thunk)
  "Run THUNK, capture (op params) pairs sent via send-request-function."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _cb)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

;;; ─── Mutation timeout ────────────────────────────────────────────────────────

(ert-deftest pwpt-timer-key-formats-correctly ()
  (should (equal "ext/w1:b1"
                 (psi-widget-projection--timer-key "ext" "w1" "b1"))))

(ert-deftest pwpt-effective-timeout-uses-mutation-timeout-first ()
  (let* ((mut    '((:name . ext/do) (:timeout-ms . 1000)))
         (button '((:type . button) (:timeout-ms . 2000))))
    (should (= 1000 (psi-widget-projection--effective-timeout-ms button mut)))))

(ert-deftest pwpt-effective-timeout-uses-button-timeout-when-no-mutation-timeout ()
  (let* ((mut    '((:name . ext/do)))
         (button '((:type . button) (:timeout-ms . 5000))))
    (should (= 5000 (psi-widget-projection--effective-timeout-ms button mut)))))

(ert-deftest pwpt-effective-timeout-uses-default-when-neither-set ()
  (let* ((mut    '((:name . ext/do)))
         (button '((:type . button))))
    (should (= psi-widget-projection--default-timeout-ms
               (psi-widget-projection--effective-timeout-ms button mut)))))

(ert-deftest pwpt-arm-cancel-mutation-timer-roundtrip ()
  (pwpt--with-state
   (cl-letf (((symbol-function 'run-at-time)
              (lambda (_secs _rep fn &rest args)
                ;; Return a fake timer object
                (apply #'list fn args))))
     (let ((timers (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
       (psi-widget-projection--arm-mutation-timer psi-emacs--state "ext" "w1" "b1" 5000)
       (should (gethash "ext/w1:b1" timers))
       (psi-widget-projection--cancel-mutation-timer psi-emacs--state "ext/w1:b1")
       (should (null (gethash "ext/w1:b1" timers)))))))

(ert-deftest pwpt-arm-threads-buffer-and-state-into-scheduled-callback ()
  "Arm threads originating BUFFER + STATE ahead of EXT-ID WIDGET-ID NODE-KEY
TIMEOUT-MS into the scheduled `run-at-time' callback args, mirroring
`psi-emacs--schedule-notification-dismiss'.  Asserts the threaded arg
order/values directly so design AC \"arm captures+threads buffer/state\" is
locked, not only exercised indirectly."
  (pwpt--with-state
   (let ((origin-state psi-emacs--state)
         (captured-fn   nil)
         (captured-args nil))
     (cl-letf (((symbol-function 'run-at-time)
                (lambda (_secs _rep fn &rest args)
                  (setq captured-fn fn captured-args args)
                  'fake-timer)))
       (psi-widget-projection--arm-mutation-timer
        origin-state "ext" "w1" "b1" 5000))
     ;; Scheduled fn is the timeout callback.
     (should (eq #'psi-widget-projection--on-mutation-timeout captured-fn))
     ;; Threaded args lead with originating buffer + state, then the
     ;; ext-id/widget-id/node-key/timeout-ms tail, matching the timeout
     ;; callback's `(buffer state ext-id widget-id node-key timeout-ms)' arglist.
     (should (equal (list (current-buffer) origin-state "ext" "w1" "b1" 5000)
                    captured-args)))))

(ert-deftest pwpt-on-mutation-timeout-clears-in-flight ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
          (lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-mutation-timeout
        (current-buffer) psi-emacs--state "ext" "w1" "b1" 5000))
     (should-not (psi-widget-renderer--in-flight-p
                  (psi-widget-projection--get-lstate "ext" "w1") "b1")))))

(ert-deftest pwpt-on-mutation-timeout-calls-error-handler ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
          (received nil)
          (psi-widget-projection-error-handler
           (lambda (ctx) (setq received ctx))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-mutation-timeout
        (current-buffer) psi-emacs--state "ext" "w1" "b1" 5000))
     (should received)
     (should (equal "mutation-timeout"
                    (alist-get :error-code received nil nil #'equal)))
     (should (equal "w1"
                    (alist-get :widget-id received nil nil #'equal))))))

(ert-deftest pwpt-on-mutation-timeout-noop-when-buffer-dead ()
  "Timeout is a harmless no-op when the originating BUFFER is dead.
Repurposed from the former `pwpt-on-mutation-timeout-noop-when-no-state':
the post-change no-op pivots on `(buffer-live-p buffer)', not dynamic state."
  (pwpt--with-state
   (let* ((live-state psi-emacs--state)
          (timers     (psi-emacs-state-projection-mutation-timers live-state))
          (dead-buffer (generate-new-buffer " *pwpt-dead*")))
     (puthash "ext/w1:b1" 'sentinel timers)
     (kill-buffer dead-buffer)
     (should-not (condition-case err
                     (progn
                       (psi-widget-projection--on-mutation-timeout
                        dead-buffer live-state "ext" "w1" "b1" 5000)
                       nil)
                   (error err)))
     ;; A live buffer's store is untouched by a dead-buffer timeout.
     (should (eq 'sentinel (gethash "ext/w1:b1" timers))))))

(ert-deftest pwpt-on-mutation-timeout-noop-when-state-nil ()
  "Timeout is a harmless no-op when STATE is nil.
Mirrors the `psi-emacs--schedule-notification-dismiss' guard
`(and (buffer-live-p buffer) st)': a live BUFFER with a nil STATE must not
error or mutate anything."
  (pwpt--with-state
   (let ((live-buffer (current-buffer)))
     (should-not (condition-case err
                     (progn
                       (psi-widget-projection--on-mutation-timeout
                        live-buffer nil "ext" "w1" "b1" 5000)
                       nil)
                   (error err))))))

(ert-deftest pwpt-dispatch-mutation-arms-timer ()
  (pwpt--with-state
   (let* ((mutation '((:name . ext/do-thing) (:params . ())))
          (timer-armed nil))
     (cl-letf (((symbol-function 'run-at-time)
                (lambda (_s _r _fn &rest _a) (setq timer-armed t) 'fake-timer))
               ((symbol-function 'cancel-timer) #'ignore))
       (pwpt--capture-query-sends
        (lambda ()
          (psi-widget-projection--dispatch-mutation
           mutation "ext" "w1" "b1" nil))))
     (should timer-armed))))

(ert-deftest pwpt-dispatch-mutation-cancels-timer-on-response ()
  (pwpt--with-state
   (let* ((mutation '((:name . ext/do-thing) (:params . ())))
          (timer-cancelled nil)
          (captured-cb nil))
     (cl-letf (((symbol-function 'run-at-time)
                (lambda (_s _r _fn &rest _a) 'fake-timer))
               ((symbol-function 'cancel-timer)
                (lambda (_t) (setq timer-cancelled t)))
               ((symbol-function 'timerp) (lambda (x) (eq x 'fake-timer)))
               ((symbol-value 'psi-emacs--send-request-function)
                (lambda (_state _op _params &optional cb) (setq captured-cb cb)))
               ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--dispatch-mutation mutation "ext" "w1" "b1" nil)
       ;; The timer must have been recorded in the buffer-local store.
       (should (gethash "ext/w1:b1"
                        (psi-emacs-state-projection-mutation-timers
                         psi-emacs--state)))
       (when captured-cb
         (funcall captured-cb '((:data . ((:ok . t))))))
       ;; And cleared from that same store once the response cancels it.
       (should (null (gethash "ext/w1:b1"
                              (psi-emacs-state-projection-mutation-timers
                               psi-emacs--state)))))
     (should timer-cancelled))))

(ert-deftest pwpt-dispatch-response-targets-originating-buffer ()
  "A response arriving while a different buffer is current cancels/clears the
originating buffer's store + in-flight lstate, not the current buffer's.
Mirrors `pwpt-on-mutation-timeout-targets-originating-buffer' for the response
path (design.md Scope (d): the response path clears the originating buffer's
\"buffer-local timer store and lstate\")."
  (let ((origin-buffer (generate-new-buffer " *pwpt-origin*"))
        (other-buffer  (generate-new-buffer " *pwpt-other*"))
        (captured-cb   nil))
    (unwind-protect
        (cl-letf (((symbol-function 'run-at-time)
                   (lambda (_s _r _fn &rest _a) 'fake-timer))
                  ((symbol-function 'cancel-timer) #'ignore)
                  ((symbol-function 'timerp) (lambda (x) (eq x 'fake-timer)))
                  ((symbol-value 'psi-emacs--send-request-function)
                   (lambda (_state _op _params &optional cb) (setq captured-cb cb)))
                  ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
          ;; Origin buffer: spec + in-flight lstate, then arm + dispatch.
          (with-current-buffer origin-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
                   (lstate (psi-widget-renderer-lstate-set-in-flight
                            (psi-widget-renderer-make-lstate) "b1" t)))
              (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
                    (list spec))
              (psi-widget-projection--sync-lstates (list spec))
              (psi-widget-projection--set-lstate "ext" "w1" lstate))
            (psi-widget-projection--dispatch-mutation
             '((:name . ext/do-thing) (:params . ())) "ext" "w1" "b1" nil))
          ;; Other buffer: independent store + same-key in-flight lstate.
          (with-current-buffer other-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
                   (lstate (psi-widget-renderer-lstate-set-in-flight
                            (psi-widget-renderer-make-lstate) "b1" t)))
              (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
                    (list spec))
              (psi-widget-projection--sync-lstates (list spec))
              (psi-widget-projection--set-lstate "ext" "w1" lstate))
            (puthash "ext/w1:b1" 'sentinel
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
          ;; Fire the response while the OTHER buffer is current.
          (with-current-buffer other-buffer
            (funcall captured-cb '((:data . ((:ok . t))))))
          ;; Origin store + in-flight cleared; other store + in-flight untouched.
          (with-current-buffer origin-buffer
            (should (null (gethash "ext/w1:b1"
                                   (psi-emacs-state-projection-mutation-timers
                                    psi-emacs--state))))
            (should-not (psi-widget-renderer--in-flight-p
                         (psi-widget-projection--get-lstate "ext" "w1") "b1")))
          (with-current-buffer other-buffer
            (should (eq 'sentinel
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state))))
            (should (psi-widget-renderer--in-flight-p
                     (psi-widget-projection--get-lstate "ext" "w1") "b1"))))
      (kill-buffer origin-buffer)
      (kill-buffer other-buffer))))

(ert-deftest pwpt-dispatch-response-noop-when-buffer-dead ()
  "A response for a dead originating buffer is a no-op (no error)."
  (let ((origin-buffer (generate-new-buffer " *pwpt-origin*"))
        (captured-cb   nil))
    (cl-letf (((symbol-function 'run-at-time)
               (lambda (_s _r _fn &rest _a) 'fake-timer))
              ((symbol-function 'cancel-timer) #'ignore)
              ((symbol-function 'timerp) (lambda (x) (eq x 'fake-timer)))
              ((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state _op _params &optional cb) (setq captured-cb cb)))
              ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
      (with-current-buffer origin-buffer
        (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
        (psi-widget-projection--dispatch-mutation
         '((:name . ext/do-thing) (:params . ())) "ext" "w1" "b1" nil))
      (kill-buffer origin-buffer)
      (should-not (condition-case err
                      (progn (funcall captured-cb '((:data . ((:ok . t))))) nil)
                    (error err))))))

(ert-deftest pwpt-on-mutation-timeout-targets-originating-buffer ()
  "A timeout firing while a different buffer is current cancels/clears the
originating buffer's store + in-flight lstate, not the current buffer's.
Mirrors `pwpt-dispatch-response-targets-originating-buffer' for the timeout
path (design.md Scope (d): \"a response (and a timeout)\")."
  (let ((origin-buffer (generate-new-buffer " *pwpt-origin*"))
        (other-buffer  (generate-new-buffer " *pwpt-other*")))
    (unwind-protect
        (cl-letf (((symbol-function 'cancel-timer) #'ignore)
                  ((symbol-function 'timerp) (lambda (x) (eq x 'fake-timer)))
                  ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
          ;; Origin buffer: armed timer + in-flight lstate for the spec's button.
          (with-current-buffer origin-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
                   (lstate (psi-widget-renderer-lstate-set-in-flight
                            (psi-widget-renderer-make-lstate) "b1" t)))
              (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
                    (list spec))
              (psi-widget-projection--sync-lstates (list spec))
              (psi-widget-projection--set-lstate "ext" "w1" lstate)
              (puthash "ext/w1:b1" 'fake-timer
                       (psi-emacs-state-projection-mutation-timers
                        psi-emacs--state))))
          ;; Other buffer: independent store with the same key.
          (with-current-buffer other-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (puthash "ext/w1:b1" 'sentinel
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
          ;; Fire the timeout against the ORIGIN buffer/state while OTHER is
          ;; current.
          (let ((origin-state (with-current-buffer origin-buffer
                                psi-emacs--state)))
            (with-current-buffer other-buffer
              (psi-widget-projection--on-mutation-timeout
               origin-buffer origin-state "ext" "w1" "b1" 5000)))
          ;; Origin store + in-flight cleared; other store untouched.
          (with-current-buffer origin-buffer
            (should (null (gethash "ext/w1:b1"
                                   (psi-emacs-state-projection-mutation-timers
                                    psi-emacs--state))))
            (should-not (psi-widget-renderer--in-flight-p
                         (psi-widget-projection--get-lstate "ext" "w1") "b1")))
          (with-current-buffer other-buffer
            (should (eq 'sentinel
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state))))))
      (kill-buffer origin-buffer)
      (kill-buffer other-buffer))))

;;; ─── Global error handler ────────────────────────────────────────────────────

(ert-deftest pwpt-error-handler-called-when-set ()
  (let* ((received nil)
         (psi-widget-projection-error-handler
          (lambda (ctx) (setq received ctx)))
         (ctx '((:error-code . "test") (:widget-id . "w1"))))
    (psi-widget-projection--call-error-handler ctx)
    (should (equal ctx received))))

(ert-deftest pwpt-error-handler-noop-when-nil ()
  (let ((psi-widget-projection-error-handler nil))
    (should-not (condition-case err
                    (progn
                      (psi-widget-projection--call-error-handler
                       '((:error-code . "test")))
                      nil)
                  (error err)))))

(ert-deftest pwpt-error-handler-survives-handler-exception ()
  (let ((psi-widget-projection-error-handler
         (lambda (_ctx) (error "handler exploded"))))
    (should-not (condition-case err
                    (progn
                      (psi-widget-projection--call-error-handler
                       '((:error-code . "test")))
                      nil)
                  (error err)))))

(ert-deftest pwpt-query-failure-calls-error-handler ()
  (pwpt--with-state
   (let* ((received nil)
          (psi-widget-projection-error-handler
           (lambda (ctx) (setq received ctx))))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--handle-spec-data-result
        "ext/w1"
        '((:error . "resolver failed"))))
     (should received)
     (should (equal "query-failed"
                    (alist-get :error-code received nil nil #'equal))))))

;;; ─── Teardown / transcript-reset / cross-buffer ─────────────────────────────

(ert-deftest pwpt-clear-mutation-timers-cancels-and-clears ()
  "`--clear-mutation-timers' cancels every timer and empties the store."
  (pwpt--with-state
   (let ((timers (psi-emacs-state-projection-mutation-timers psi-emacs--state))
         (cancelled nil))
     (cl-letf (((symbol-function 'timerp) (lambda (x) (memq x '(t1 t2))))
               ((symbol-function 'cancel-timer)
                (lambda (tm) (push tm cancelled))))
       (puthash "ext/w1:b1" 't1 timers)
       (puthash "ext/w2:b2" 't2 timers)
       (psi-widget-projection--clear-mutation-timers psi-emacs--state)
       (should (= 0 (hash-table-count timers)))
       (should (= 2 (length cancelled)))))))

(ert-deftest pwpt-clear-mutation-timers-noop-when-state-nil ()
  "`--clear-mutation-timers' is a harmless no-op for a nil STATE."
  (should-not (condition-case err
                  (progn (psi-widget-projection--clear-mutation-timers nil) nil)
                (error err))))

(ert-deftest pwpt-teardown-cancels-in-flight-mutation-timers ()
  "Killing a psi buffer cancels and clears its in-flight widget mutation timers."
  (let ((buffer (generate-new-buffer " *pwpt-teardown*"))
        (cancelled nil))
    (unwind-protect
        (cl-letf (((symbol-function 'timerp) (lambda (x) (eq x 'live-timer)))
                  ((symbol-function 'cancel-timer)
                   (lambda (tm) (push tm cancelled))))
          (with-current-buffer buffer
            (psi-emacs-mode)
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (let ((timers (psi-emacs-state-projection-mutation-timers
                           psi-emacs--state)))
              (puthash "ext/w1:b1" 'live-timer timers)
              (let ((noninteractive t))
                (psi-emacs--teardown-buffer))
              ;; Teardown nils `psi-emacs--state'; assert against the store it
              ;; cleared (captured before teardown).
              (should (= 0 (hash-table-count timers)))
              (should (memq 'live-timer cancelled)))))
      (when (buffer-live-p buffer) (kill-buffer buffer)))))

(ert-deftest pwpt-reset-transcript-clears-mutation-timers ()
  "Transcript reset clears the buffer's widget mutation timers."
  (let ((buffer (generate-new-buffer " *pwpt-reset*"))
        (cancelled nil))
    (unwind-protect
        (cl-letf (((symbol-function 'timerp) (lambda (x) (eq x 'live-timer)))
                  ((symbol-function 'cancel-timer)
                   (lambda (tm) (push tm cancelled))))
          (with-current-buffer buffer
            (psi-emacs-mode)
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (puthash "ext/w1:b1" 'live-timer
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state))
            (psi-emacs--reset-transcript-state)
            (should (= 0 (hash-table-count
                          (psi-emacs-state-projection-mutation-timers
                           psi-emacs--state))))
            (should (memq 'live-timer cancelled))))
      (when (buffer-live-p buffer) (kill-buffer buffer)))))

(ert-deftest pwpt-two-buffers-do-not-share-mutation-timer-state ()
  "Two psi buffers with the same key keep independent timer stores."
  (let ((buffer-a (generate-new-buffer " *pwpt-a*"))
        (buffer-b (generate-new-buffer " *pwpt-b*")))
    (unwind-protect
        (progn
          (with-current-buffer buffer-a
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (puthash "ext/w1:b1" 'timer-a
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
          (with-current-buffer buffer-b
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (puthash "ext/w1:b1" 'timer-b
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
          ;; Distinct stores; same key resolves independently per buffer.
          (with-current-buffer buffer-a
            (should (eq 'timer-a
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state)))))
          (with-current-buffer buffer-b
            (should (eq 'timer-b
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state)))))
          ;; Clearing buffer-a's store leaves buffer-b's intact.
          (with-current-buffer buffer-a
            (psi-widget-projection--clear-mutation-timers psi-emacs--state))
          (with-current-buffer buffer-b
            (should (eq 'timer-b
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state))))))
      (when (buffer-live-p buffer-a) (kill-buffer buffer-a))
      (when (buffer-live-p buffer-b) (kill-buffer buffer-b)))))

(provide 'psi-widget-projection-timers-test)
;;; psi-widget-projection-timers-test.el ends here
