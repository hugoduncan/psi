;;; psi-test-support.el --- Shared helpers for psi Emacs frontend tests  -*- lexical-binding: t; -*-

(require 'cl-lib)

(defmacro psi-test--should-not-error (&rest body)
  "Assert that evaluating BODY raises no error (a harmless no-op).
Compresses the hand-rolled
`(should-not (condition-case err (progn BODY nil) (error err)))'
idiom into a single explicit no-op assertion."
  (declare (indent 0) (debug t))
  `(should-not (condition-case err
                   (progn ,@body nil)
                 (error err))))

(defun psi-test--spawn-long-lived-process (&optional command)
  "Spawn a long-lived process suitable for ownership/lifecycle tests."
  (make-process
   :name (format "psi-test-%s" (gensym))
   :command (or command '("cat"))
   :buffer nil
   :noquery t
   :connection-type 'pipe))

(defun psi-test--capture-request-sends (thunk)
  "Run THUNK with send function overridden and return captured RPC calls."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _callback)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

(provide 'psi-test-support)

;;; psi-test-support.el ends here
