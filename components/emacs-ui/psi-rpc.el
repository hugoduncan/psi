;;; psi-rpc.el --- rpc-edn process transport for psi Emacs frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Minimal rpc-edn transport manager for Emacs MVP frontend.

;;; Code:

(require 'cl-lib)
(require 'subr-x)

(defconst psi-rpc--parseedn-available
  (not (null (require 'parseedn nil t)))
  "Non-nil when parseedn is available for EDN parsing/printing.")

(defconst psi-rpc-protocol-version "1.0"
  "Supported rpc-edn protocol version for Emacs MVP transport.")

(defconst psi-rpc-mvp-topics
  '("assistant/delta"
    "assistant/message"
    "tool/start"
    "tool/delta"
    "tool/executing"
    "tool/update"
    "tool/result"
    "session/updated"
    "error")
  "MVP topic subscription set for Emacs frontend.")

(cl-defstruct psi-rpc-client
  process
  process-state
  transport-state
  protocol-version
  subscribed-topics
  line-buffer
  next-request-id
  pending-callbacks
  on-state-change
  on-event
  on-rpc-error
  send-function)

(defun psi-rpc--default-send (process payload)
  "Send PAYLOAD string to PROCESS."
  (process-send-string process payload))

(defun psi-rpc-make-client (&rest keys)
  "Create a rpc client transport with keyword KEYS overrides."
  (let ((client (make-psi-rpc-client)))
    (setf (psi-rpc-client-process client) nil
          (psi-rpc-client-process-state client) 'stopped
          (psi-rpc-client-transport-state client) 'disconnected
          (psi-rpc-client-protocol-version client) nil
          (psi-rpc-client-subscribed-topics client) nil
          (psi-rpc-client-line-buffer client) ""
          (psi-rpc-client-next-request-id client) 0
          (psi-rpc-client-pending-callbacks client) (make-hash-table :test #'equal)
          (psi-rpc-client-on-state-change client) nil
          (psi-rpc-client-on-event client) nil
          (psi-rpc-client-on-rpc-error client) nil
          (psi-rpc-client-send-function client) #'psi-rpc--default-send)
    (while keys
      (let ((k (pop keys))
            (v (pop keys)))
        (pcase k
          (:process (setf (psi-rpc-client-process client) v))
          (:process-state (setf (psi-rpc-client-process-state client) v))
          (:transport-state (setf (psi-rpc-client-transport-state client) v))
          (:protocol-version (setf (psi-rpc-client-protocol-version client) v))
          (:subscribed-topics (setf (psi-rpc-client-subscribed-topics client) v))
          (:line-buffer (setf (psi-rpc-client-line-buffer client) v))
          (:next-request-id (setf (psi-rpc-client-next-request-id client) v))
          (:pending-callbacks (setf (psi-rpc-client-pending-callbacks client) v))
          (:on-state-change (setf (psi-rpc-client-on-state-change client) v))
          (:on-event (setf (psi-rpc-client-on-event client) v))
          (:on-rpc-error (setf (psi-rpc-client-on-rpc-error client) v))
          (:send-function (setf (psi-rpc-client-send-function client) v))
          (_ nil))))
    client))

(defun psi-rpc--notify-state (client)
  "Emit state change notification for CLIENT."
  (when-let ((f (psi-rpc-client-on-state-change client)))
    (funcall f client)))

(defun psi-rpc--set-state (client process-state transport-state)
  "Set PROCESS-STATE and TRANSPORT-STATE on CLIENT and notify."
  (setf (psi-rpc-client-process-state client) process-state
        (psi-rpc-client-transport-state client) transport-state)
  (psi-rpc--notify-state client))

(defun psi-rpc--signal-error (client code message &optional frame)
  "Report transport/protocol error for CLIENT with CODE MESSAGE and FRAME."
  (when-let ((f (psi-rpc-client-on-rpc-error client)))
    (funcall f code message frame)))

(defun psi-rpc--next-id (client)
  "Return next request id string for CLIENT."
  (setf (psi-rpc-client-next-request-id client)
        (1+ (psi-rpc-client-next-request-id client)))
  (format "req-%d" (psi-rpc-client-next-request-id client)))

(defun psi-rpc--alist-map-p (x)
  "Return non-nil when X looks like an alist map representation."
  (and (listp x)
       (cl-every #'consp x)))

(defun psi-rpc--edn-encode-key (k)
  "Encode map key K as EDN text."
  (cond
   ((keywordp k) (symbol-name k))
   ((symbolp k) (symbol-name k))
   (t (psi-rpc--edn-encode k))))

(defun psi-rpc--edn-encode-string (s)
  "Encode string S as an EDN string literal.

Escapes control characters so each RPC frame remains single-line."
  (concat
   "\""
   (mapconcat
    (lambda (ch)
      (pcase ch
        (?\" "\\\"")
        (?\\ "\\\\")
        (?\n "\\n")
        (?\r "\\r")
        (?\t "\\t")
        (?\b "\\b")
        (?\f "\\f")
        (_ (if (or (< ch 32) (= ch 127))
               (format "\\u%04X" ch)
             (char-to-string ch)))))
    (string-to-list s)
    "")
   "\""))

(defun psi-rpc--edn-encode (x)
  "Encode X as EDN text for rpc-edn wire frames.

Used as fallback when parseedn is unavailable."
  (cond
   ((null x) "nil")
   ((eq x t) "true")
   ((stringp x) (psi-rpc--edn-encode-string x))
   ((numberp x) (number-to-string x))
   ((vectorp x)
    (format "[%s]"
            (mapconcat #'psi-rpc--edn-encode (append x nil) " ")))
   ((psi-rpc--alist-map-p x)
    (format "{%s}"
            (mapconcat (lambda (entry)
                         (format "%s %s"
                                 (psi-rpc--edn-encode-key (car entry))
                                 (psi-rpc--edn-encode (cdr entry))))
                       x
                       " ")))
   ((listp x)
    (format "[%s]" (mapconcat #'psi-rpc--edn-encode x " ")))
   ((symbolp x)
    (symbol-name x))
   (t
    (prin1-to-string x))))

(defun psi-rpc--serialize-frame (frame)
  "Serialize FRAME to one EDN text line (without trailing newline)."
  (if psi-rpc--parseedn-available
      (with-temp-buffer
        (parseedn-print frame)
        (buffer-string))
    (psi-rpc--edn-encode frame)))

(defun psi-rpc--send-frame! (client frame)
  "Serialize and send FRAME for CLIENT.

FRAME is represented as an alist with keyword keys.
Always writes one EDN map per line."
  (let* ((process (psi-rpc-client-process client))
         (sender (or (psi-rpc-client-send-function client) #'psi-rpc--default-send))
         (payload (concat (psi-rpc--serialize-frame frame) "\n")))
    (when (process-live-p process)
      (funcall sender process payload))))

(defun psi-rpc-send-request! (client op &optional params callback)
  "Send request OP with optional PARAMS and CALLBACK for CLIENT."
  (let* ((id (psi-rpc--next-id client))
         (frame (append (list (cons :id id)
                              (cons :kind :request)
                              (cons :op op))
                        (when params
                          (list (cons :params params))))))
    (when callback
      (puthash id callback (psi-rpc-client-pending-callbacks client)))
    (psi-rpc--send-frame! client frame)
    id))

(defun psi-rpc--edn->sexp-string (line)
  "Transform EDN LINE into a read-compatible sexp carrier string.

Maps become (psi-map ...), vectors become (psi-vec ...)."
  (let ((i 0)
        (len (length line))
        (in-string nil)
        (escaped nil)
        (chunks nil))
    (while (< i len)
      (let ((ch (aref line i)))
        (if in-string
            (progn
              (push (char-to-string ch) chunks)
              (cond
               (escaped (setq escaped nil))
               ((eq ch ?\\) (setq escaped t))
               ((eq ch ?\") (setq in-string nil))))
          (pcase ch
            (?\" (setq in-string t)
                 (push "\"" chunks))
            (?# (if (and (< (1+ i) len)
                         (eq (aref line (1+ i)) ?{))
                    (progn
                      (push "(psi-set " chunks)
                      (setq i (1+ i)))
                  (push "#" chunks)))
            (?{ (push "(psi-map " chunks))
            (?} (push ")" chunks))
            (?\[ (push "(psi-vec " chunks))
            (?\] (push ")" chunks))
            (?, (push " " chunks))
            (_ (push (char-to-string ch) chunks)))))
      (setq i (1+ i)))
    (apply #'concat (nreverse chunks))))

(defun psi-rpc--from-edn-value (v)
  "Convert parsed carrier value V into Emacs representation.

EDN maps become alists, vectors become vectors.
Booleans map as true=>t and false=>nil."
  (cond
   ((eq v 'true) t)
   ((eq v 'false) nil)
   ((or (null v) (stringp v) (numberp v) (keywordp v)) v)
   ((symbolp v) v)
   ((and (listp v) (eq (car v) 'psi-vec))
    (vconcat (mapcar #'psi-rpc--from-edn-value (cdr v))))
   ((and (listp v) (eq (car v) 'psi-map))
    (let ((xs (cdr v))
          (out nil))
      (while xs
        (let ((k (car xs))
              (rest (cdr xs)))
          (unless rest
            (setq xs nil)
            (setq out (cons (cons :__parse-error "odd map arity") out)))
          (when rest
            (setq out (cons (cons (psi-rpc--from-edn-value k)
                                  (psi-rpc--from-edn-value (car rest)))
                            out)
                  xs (cdr rest)))))
      (nreverse out)))
   ((and (listp v) (eq (car v) 'psi-set))
    (mapcar #'psi-rpc--from-edn-value (cdr v)))
   ((listp v)
    (mapcar #'psi-rpc--from-edn-value v))
   (t v)))

(defun psi-rpc--parseedn-read-forms (line)
  "Parse LINE into top-level EDN forms via parseedn.

Returns a list of parsed forms."
  (with-temp-buffer
    (insert line)
    (goto-char (point-min))
    (parseedn-read)))

(defun psi-rpc--from-parseedn-value (v)
  "Convert parseedn value V to rpc client internal shape.

Maps become alists, vectors remain vectors, sets become lists."
  (cond
   ((hash-table-p v)
    (let (out)
      (maphash (lambda (k val)
                 (push (cons (psi-rpc--from-parseedn-value k)
                             (psi-rpc--from-parseedn-value val))
                       out))
               v)
      (nreverse out)))
   ((vectorp v)
    (vconcat (mapcar #'psi-rpc--from-parseedn-value (append v nil))))
   ((and (consp v) (eq (car v) 'edn-set))
    (let ((items (if (and (consp (cdr v))
                          (null (cddr v))
                          (listp (cadr v)))
                     (cadr v)
                   (cdr v))))
      (mapcar #'psi-rpc--from-parseedn-value items)))
   ((listp v)
    (mapcar #'psi-rpc--from-parseedn-value v))
   (t v)))

(defun psi-rpc--parse-line-with-parseedn (line)
  "Parse LINE as one EDN frame map via parseedn."
  (condition-case _
      (let* ((forms (psi-rpc--parseedn-read-forms line)))
        (cond
         ((/= (length forms) 1)
          (list :error "trailing data after EDN frame"))
         (t
          (let ((frame (psi-rpc--from-parseedn-value (car forms))))
            (if (psi-rpc--alist-map-p frame)
                (list :ok frame)
              (list :error "frame must be EDN map"))))))
    (error (list :error "unable to parse EDN frame"))))

(defun psi-rpc--parse-line-fallback (line)
  "Parse LINE as one EDN frame map using fallback parser."
  (condition-case _
      (let* ((carrier (psi-rpc--edn->sexp-string line))
             (result (read-from-string carrier))
             (parsed (car result))
             (idx (cdr result))
             (rest (string-trim (substring carrier idx)))
             (frame (psi-rpc--from-edn-value parsed)))
        (cond
         ((not (string-empty-p rest))
          (list :error "trailing data after EDN frame"))
         ((not (psi-rpc--alist-map-p frame))
          (list :error "frame must be EDN map"))
         ((alist-get :__parse-error frame)
          (list :error "invalid EDN map structure"))
         (t
          (list :ok frame))))
    (error (list :error "unable to parse EDN frame"))))

(defun psi-rpc--parse-line (line)
  "Parse LINE as one EDN frame map. Return plist with :ok or :error."
  (if psi-rpc--parseedn-available
      (psi-rpc--parse-line-with-parseedn line)
    (psi-rpc--parse-line-fallback line)))

(defun psi-rpc--dispatch-response-or-error (client frame)
  "Dispatch FRAME callback for CLIENT by request id."
  (let* ((id (alist-get :id frame))
         (cb (and (stringp id)
                  (gethash id (psi-rpc-client-pending-callbacks client)))))
    (when (and cb (stringp id))
      (remhash id (psi-rpc-client-pending-callbacks client))
      (funcall cb frame))))

(defun psi-rpc--handle-frame (client frame)
  "Handle parsed FRAME for CLIENT."
  (let ((kind (alist-get :kind frame)))
    (pcase kind
      (:response
       (psi-rpc--dispatch-response-or-error client frame))
      (:error
       (psi-rpc--dispatch-response-or-error client frame)
       (unless (alist-get :id frame)
         (psi-rpc--signal-error client
                                (or (alist-get :error-code frame) "rpc/error")
                                (or (alist-get :error-message frame) "rpc error")
                                frame)))
      (:event
       (when-let ((f (psi-rpc-client-on-event client)))
         (funcall f frame)))
      (_
       (psi-rpc--signal-error client
                              "protocol/invalid-envelope"
                              "unsupported inbound frame kind"
                              frame)))))

(defun psi-rpc-process-filter (process chunk)
  "Process filter for rpc-edn PROCESS that consumes CHUNK incrementally."
  (when-let ((client (process-get process 'psi-rpc-client)))
    (let* ((acc (concat (psi-rpc-client-line-buffer client) chunk))
           (parts (split-string acc "\n"))
           (complete (butlast parts))
           (tail (car (last parts))))
      (setf (psi-rpc-client-line-buffer client) (or tail ""))
      (dolist (line complete)
        (unless (string-empty-p (string-trim line))
          (pcase (psi-rpc--parse-line line)
            (`(:ok ,frame)
             (psi-rpc--handle-frame client frame))
            (`(:error ,message)
             (psi-rpc--signal-error client "transport/invalid-frame" message nil))))))))

(defun psi-rpc--cleanup-process-stderr-buffer (process)
  "Kill PROCESS-owned stderr buffer (if any)."
  (when-let ((stderr-buffer (and process (process-get process 'psi-rpc-stderr-buffer))))
    (when (buffer-live-p stderr-buffer)
      (kill-buffer stderr-buffer))
    (process-put process 'psi-rpc-stderr-buffer nil)))

(defun psi-rpc-process-sentinel (process event)
  "Process sentinel for rpc-edn PROCESS with EVENT text."
  (ignore event)
  (when-let ((client (process-get process 'psi-rpc-client)))
    (setf (psi-rpc-client-process client) process)
    (if (process-live-p process)
        (psi-rpc--set-state client 'running (psi-rpc-client-transport-state client))
      (let ((status (process-status process)))
        (psi-rpc--cleanup-process-stderr-buffer process)
        (psi-rpc--set-state client
                            (if (eq status 'exit) 'stopped 'crashed)
                            'disconnected)))))

(defun psi-rpc-bootstrap! (client &optional topics)
  "Run startup handshake and topic subscription for CLIENT.

TOPICS defaults to `psi-rpc-mvp-topics'."
  (let ((topics* (or topics psi-rpc-mvp-topics)))
    (psi-rpc-send-request!
     client
     "handshake"
     `((:client-info . ((:name . "psi-emacs")
                        (:version . "0.1.0")
                        (:protocol-version . ,psi-rpc-protocol-version)
                        (:features . []))))
     (lambda (frame)
       (if (and (eq (alist-get :kind frame) :response)
                (eq (alist-get :ok frame) t))
           (progn
             (setf (psi-rpc-client-protocol-version client)
                   (or (alist-get :protocol-version (alist-get :server-info (alist-get :data frame)))
                       psi-rpc-protocol-version))
             (psi-rpc-send-request!
              client
              "subscribe"
              `((:topics . ,(vconcat topics*)))
              (lambda (sub-frame)
                (if (and (eq (alist-get :kind sub-frame) :response)
                         (eq (alist-get :ok sub-frame) t))
                    (progn
                      (setf (psi-rpc-client-subscribed-topics client) topics*)
                      (psi-rpc--set-state client 'running 'ready))
                  (psi-rpc--set-state client 'running 'disconnected)
                  (psi-rpc--signal-error client
                                         (or (alist-get :error-code sub-frame) "transport/not-ready")
                                         (or (alist-get :error-message sub-frame) "subscribe failed")
                                         sub-frame)))))
         (psi-rpc--set-state client 'running 'disconnected)
         (psi-rpc--signal-error client
                                (or (alist-get :error-code frame) "protocol/unsupported-version")
                                (or (alist-get :error-message frame) "handshake failed")
                                frame))))))

(defun psi-rpc-start! (client spawn-process-fn command &optional topics)
  "Start CLIENT process with SPAWN-PROCESS-FN using COMMAND.

SPAWN-PROCESS-FN receives COMMAND and returns an Emacs process object."
  (let ((process (funcall spawn-process-fn command)))
    (setf (psi-rpc-client-process client) process)
    (process-put process 'psi-rpc-client client)
    (set-process-filter process #'psi-rpc-process-filter)
    (set-process-sentinel process #'psi-rpc-process-sentinel)
    (psi-rpc--set-state client
                        (if (process-live-p process) 'running 'starting)
                        'handshaking)
    (psi-rpc-bootstrap! client topics)
    client))

(defun psi-rpc-stop! (client)
  "Stop CLIENT process if live and clear transport state."
  (let ((process (psi-rpc-client-process client)))
    (when (process-live-p process)
      (delete-process process))
    (psi-rpc--cleanup-process-stderr-buffer process))
  (clrhash (psi-rpc-client-pending-callbacks client))
  (psi-rpc--set-state client 'stopped 'disconnected))

(provide 'psi-rpc)

;;; psi-rpc.el ends here
