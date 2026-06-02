;;; psi-widget-projection.el --- Widget-spec query, lstate, and projection wiring  -*- lexical-binding: t; -*-

;;; Commentary:
;;
;; Wires psi-widget-renderer into the live frontend:
;;
;;   ui/widget-specs-updated event
;;     → re-query [:psi.ui/widget-specs] via query_eql
;;     → store specs in psi-emacs-state
;;     → merge/initialise per-widget local state (lstate)
;;     → for each spec with non-empty :query, fire query_eql
;;       → store result in projection-widget-data keyed by "ext-id/widget-id"
;;     → trigger projection block re-render
;;
;;   psi-widget-projection-render-specs
;;     → called from psi-projection.el render block
;;     → renders each spec via psi-widget-renderer-render-spec
;;       passing per-spec query data from projection-widget-data
;;     → returns propertized string
;;
;;   Button / collapsible interaction handlers
;;     → update lstate
;;     → fire mutation over RPC (button) or re-render (collapsible)

;;; Code:

(require 'cl-lib)
(require 'psi-rpc)
(require 'psi-widget-renderer)
(require 'psi-globals)

;;; Forward declarations

(defvar psi-emacs--state)
(defvar psi-emacs--send-request-function)

(declare-function psi-emacs--upsert-projection-block "psi-projection")

;;; Constants

(defconst psi-widget-projection--query
  "[:psi.ui/widget-specs]"
  "EQL query string to fetch all registered widget specs.")

(defconst psi-widget-projection--default-timeout-ms 30000
  "Default mutation timeout in milliseconds.")

;;; Global error handler

(defvar psi-widget-projection-error-handler nil
  "Function called when a widget error occurs.

Called as (fn ctx) where ctx is an alist with keys:
  :widget-id    — widget id string
  :extension-id — extension id string
  :error-code   — string: \"mutation-timeout\", \"query-failed\",
                  or \"malformed-spec\"
  :message      — human-readable error string
  :context      — mutation-timeout, query-failed, malformed-spec,
                  or event-error
If nil, errors are silently ignored.
Inline renderer errors still display.")

(defun psi-widget-projection--call-error-handler (ctx)
  "Invoke `psi-widget-projection-error-handler' with CTX if set."
  (when (functionp psi-widget-projection-error-handler)
    (condition-case err
        (funcall psi-widget-projection-error-handler ctx)
      (error (message "psi-widget: error handler raised: %S" err)))))

;;; Mutation timeout state

(defun psi-widget-projection--clear-mutation-timers (state)
  "Cancel and clear all in-flight widget mutation timers in STATE.
A nil STATE is a harmless no-op, mirroring
`psi-emacs--clear-notification-lifecycle'."
  (when state
    (let ((timers (psi-emacs-state-projection-mutation-timers state)))
      (when (hash-table-p timers)
        (maphash (lambda (_tkey timer)
                   (when (timerp timer)
                     (cancel-timer timer)))
                 timers)
        (clrhash timers)))))

;;; Query + spec storage

(defun psi-widget-projection-request-specs ()
  "Fire query_eql for :psi.ui/widget-specs and store results in state."
  (when (and psi-emacs--state
             (functionp psi-emacs--send-request-function))
    (funcall psi-emacs--send-request-function
             psi-emacs--state
             "query_eql"
             `((:query . ,psi-widget-projection--query))
             (lambda (frame)
               (psi-widget-projection--handle-specs-result frame)))))

(defun psi-widget-projection--handle-specs-result (frame)
  "Handle query_eql response FRAME for widget specs."
  (when psi-emacs--state
    (let* ((data   (alist-get :data frame nil nil #'equal))
           (result (and (listp data)
                        (alist-get :result data nil nil #'equal)))
           (specs  (and (listp result)
                        (alist-get :psi.ui/widget-specs result nil nil #'equal)))
           (specs-list (cond ((vectorp specs) (append specs nil))
                             ((listp specs)   specs)
                             (t               nil))))
      (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
            specs-list)
      (psi-widget-projection--sync-lstates specs-list)
      ;; Fetch per-spec query data; render after all fetches complete (or immediately
      ;; if no specs have queries).
      (psi-widget-projection--fetch-spec-data specs-list))))

;;; Local state management

(defun psi-widget-projection--sync-lstates (specs)
  "Sync per-widget lstates for SPECS — init new, preserve existing, drop removed."
  (let* ((current-lstates (or (psi-emacs-state-projection-widget-lstates psi-emacs--state)
                              (make-hash-table :test #'equal)))
         (new-lstates     (make-hash-table :test #'equal)))
    (dolist (spec specs)
      (let* ((ext-id    (alist-get :extension-id spec nil nil #'equal))
             (widget-id (alist-get :id           spec nil nil #'equal))
             (key       (format "%s/%s" ext-id widget-id))
             (existing  (gethash key current-lstates)))
        (puthash key
                 (or existing
                     (psi-widget-renderer-initial-lstate spec))
                 new-lstates)))
    (setf (psi-emacs-state-projection-widget-lstates psi-emacs--state)
          new-lstates)))

(defun psi-widget-projection--get-lstate (ext-id widget-id)
  "Return lstate for EXT-ID/WIDGET-ID, or a fresh one."
  (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state))
        (key     (format "%s/%s" ext-id widget-id)))
    (if (hash-table-p lstates)
        (or (gethash key lstates)
            (psi-widget-renderer-make-lstate))
      (psi-widget-renderer-make-lstate))))

(defun psi-widget-projection--set-lstate (ext-id widget-id lstate)
  "Store LSTATE for EXT-ID/WIDGET-ID."
  (when psi-emacs--state
    (let ((lstates (or (psi-emacs-state-projection-widget-lstates psi-emacs--state)
                       (make-hash-table :test #'equal)))
          (key     (format "%s/%s" ext-id widget-id)))
      (unless (hash-table-p (psi-emacs-state-projection-widget-lstates psi-emacs--state))
        (setf (psi-emacs-state-projection-widget-lstates psi-emacs--state) lstates))
      (puthash key lstate lstates))))

;;; Per-spec query data

(defun psi-widget-projection--spec-key (spec)
  "Return \"ext-id/widget-id\" key for SPEC."
  (format "%s/%s"
          (alist-get :extension-id spec nil nil #'equal)
          (alist-get :id           spec nil nil #'equal)))

(defun psi-widget-projection--spec-query-string (spec)
  "Return an EQL query string for SPEC's :query vector, or nil if empty.
Uses psi-widget-projection--edn-encode for each element to correctly
handle keywords, joins (alist maps), and nested structures."
  (let ((q (alist-get :query spec nil nil #'equal)))
    (when (and q (> (length q) 0))
      (format "[%s]"
              (mapconcat #'psi-widget-projection--edn-encode
                         (if (vectorp q) (append q nil) q)
                         " ")))))

(defun psi-widget-projection--get-spec-data (key)
  "Return stored query-result alist for KEY, or nil."
  (when psi-emacs--state
    (let ((ht (psi-emacs-state-projection-widget-data psi-emacs--state)))
      (when (hash-table-p ht)
        (gethash key ht)))))

(defun psi-widget-projection--set-spec-data (key data)
  "Store query-result DATA for KEY."
  (when psi-emacs--state
    (let ((ht (or (psi-emacs-state-projection-widget-data psi-emacs--state)
                  (make-hash-table :test #'equal))))
      (unless (hash-table-p (psi-emacs-state-projection-widget-data psi-emacs--state))
        (setf (psi-emacs-state-projection-widget-data psi-emacs--state) ht))
      (puthash key data ht))))

(defun psi-widget-projection--fetch-spec-data (specs)
  "Fetch per-spec query data for SPECS that have a non-empty :query.
Fires one query_eql per spec; re-renders after each response.
Specs without a :query trigger an immediate re-render."
  (if (null specs)
      (psi-emacs--upsert-projection-block)
    (let ((needs-fetch nil))
      (dolist (spec specs)
        (let ((query-str (psi-widget-projection--spec-query-string spec))
              (key       (psi-widget-projection--spec-key spec)))
          (if (and query-str (functionp psi-emacs--send-request-function))
              (progn
                (setq needs-fetch t)
                (let* ((captured-key key)
                       (entity      (alist-get :entity spec nil nil #'equal))
                       (entity-str  (when entity
                                      (psi-widget-projection--edn-encode entity)))
                       (params      (if entity-str
                                        `((:query . ,query-str) (:entity . ,entity-str))
                                      `((:query . ,query-str)))))
                  (funcall psi-emacs--send-request-function
                           psi-emacs--state
                           "query_eql"
                           params
                           (lambda (frame)
                             (psi-widget-projection--handle-spec-data-result
                              captured-key frame)))))
            ;; No query — clear stale data
            (psi-widget-projection--set-spec-data key nil))))
      (unless needs-fetch
        (psi-emacs--upsert-projection-block)))))

(defun psi-widget-projection--handle-spec-data-result (key frame)
  "Handle query_eql response FRAME for per-spec data identified by KEY."
  (when psi-emacs--state
    (let* ((data    (alist-get :data frame nil nil #'equal))
           (error   (alist-get :error frame nil nil #'equal))
           (result  (and (listp data)
                         (alist-get :result data nil nil #'equal))))
      (if error
          (let* ((parts   (split-string key "/"))
                 (ext-id  (car parts))
                 (wid     (cadr parts)))
            (psi-widget-projection--call-error-handler
             `((:widget-id    . ,wid)
               (:extension-id . ,ext-id)
               (:error-code   . "query-failed")
               (:message      . ,(format "Query failed: %s" error))
               (:context      . query-failed))))
        (psi-widget-projection--set-spec-data key result))
      (psi-emacs--upsert-projection-block))))

;;; Rendering

(defun psi-widget-projection-render-specs (state)
  "Render all widget specs from STATE into a propertized string.
Returns empty string when no specs are registered."
  (let ((specs (psi-emacs-state-projection-widget-specs state)))
    (if (null specs)
        ""
      (mapconcat
       (lambda (spec)
         (let* ((ext-id    (alist-get :extension-id spec nil nil #'equal))
                (widget-id (alist-get :id           spec nil nil #'equal))
                (key       (format "%s/%s" ext-id widget-id))
                (data      (psi-widget-projection--get-spec-data key))
                (lstate    (psi-widget-projection--get-lstate ext-id widget-id)))
           (psi-widget-renderer-render-spec spec data lstate)))
       specs
       "\n"))))

;;; Interaction handlers

(defun psi-widget-projection--parse-widget-id (widget-id)
  "Split \"ext-id/widget-id\" into (ext-id . widget-id) cons, or nil."
  (when (stringp widget-id)
    (let ((pos (string-match "/" widget-id)))
      (when pos
        (cons (substring widget-id 0 pos)
              (substring widget-id (1+ pos)))))))

(defun psi-widget-projection--on-button-activate (widget-id node-key)
  "Handle button activation for WIDGET-ID / NODE-KEY.
Updates lstate to in-flight, fires mutation over RPC, re-renders."
  (when-let ((parsed (psi-widget-projection--parse-widget-id widget-id)))
    (let* ((ext-id    (car parsed))
           (wid       (cdr parsed))
           (specs     (psi-emacs-state-projection-widget-specs psi-emacs--state))
           (spec      (cl-find-if
                       (lambda (s)
                         (and (equal (alist-get :extension-id s nil nil #'equal) ext-id)
                              (equal (alist-get :id s nil nil #'equal) wid)))
                       specs))
           (lstate    (psi-widget-projection--get-lstate ext-id wid))
           (root-node (alist-get :spec spec nil nil #'equal))
           (button    (psi-widget-projection--find-node-by-key root-node node-key))
           (mutation  (and button (alist-get :mutation button nil nil #'equal))))
      (when (and spec mutation
                 (not (psi-widget-renderer--in-flight-p lstate node-key))
                 (not (alist-get :disabled button nil nil #'equal)))
        ;; Mark in-flight
        (psi-widget-projection--set-lstate
         ext-id wid
         (psi-widget-renderer-lstate-set-in-flight lstate node-key t))
        ;; Fire mutation via query_eql (pass button node for timeout resolution)
        (psi-widget-projection--dispatch-mutation mutation ext-id wid node-key button)
        ;; Re-render immediately to show spinner
        (psi-emacs--upsert-projection-block)))))

(defun psi-widget-projection--timer-key (ext-id widget-id node-key)
  "Return hash key for a mutation timer: \"ext-id/widget-id:node-key\"."
  (format "%s/%s:%s" ext-id widget-id node-key))

(defun psi-widget-projection--effective-timeout-ms (button-node mutation)
  "Return effective timeout ms for BUTTON-NODE / MUTATION.
Priority: mutation :timeout-ms > button :timeout-ms > default."
  (or (alist-get :timeout-ms mutation   nil nil #'equal)
      (alist-get :timeout-ms button-node nil nil #'equal)
      psi-widget-projection--default-timeout-ms))

(defun psi-widget-projection--cancel-mutation-timer (state tkey)
  "Cancel and remove the mutation timer for TKEY in STATE's timer store.
Resolves the store solely from the passed STATE; never reads the dynamic
`psi-emacs--state'."
  (when state
    (let* ((timers (psi-emacs-state-projection-mutation-timers state))
           (timer (and (hash-table-p timers)
                       (gethash tkey timers))))
      (when (timerp timer)
        (cancel-timer timer))
      (when (hash-table-p timers)
        (remhash tkey timers)))))

(defun psi-widget-projection--clear-mutation-in-flight (ext-id widget-id node-key)
  "Clear the in-flight flag for EXT-ID/WIDGET-ID/NODE-KEY in the current buffer.
Reads/writes lstate via the buffer-local `psi-emacs--state'; callers run this
inside `with-current-buffer' so the originating buffer's lstate is targeted."
  (let ((lstate (psi-widget-projection--get-lstate ext-id widget-id)))
    (psi-widget-projection--set-lstate
     ext-id widget-id
     (psi-widget-renderer-lstate-set-in-flight lstate node-key nil))))

(defun psi-widget-projection--finalize-mutation (state ext-id widget-id node-key)
  "Finalize a settled mutation for EXT-ID/WIDGET-ID/NODE-KEY against STATE.
Cancels the watchdog timer in STATE's store, clears the in-flight flag, and
re-renders.  Callers run this inside `with-current-buffer' on the originating
buffer so the lstate/render target that buffer."
  (psi-widget-projection--cancel-mutation-timer
   state
   (psi-widget-projection--timer-key ext-id widget-id node-key))
  (psi-widget-projection--clear-mutation-in-flight ext-id widget-id node-key)
  (psi-emacs--upsert-projection-block))

(defun psi-widget-projection--arm-mutation-timer (state ext-id widget-id node-key timeout-ms)
  "Arm a watchdog timer for EXT-ID/WIDGET-ID/NODE-KEY firing after TIMEOUT-MS.
Arms against STATE's buffer-local timer store.  Captures the originating
buffer (`current-buffer') and STATE and threads both into the scheduled
callback args, mirroring `psi-emacs--schedule-notification-dismiss'."
  (let ((tkey (psi-widget-projection--timer-key ext-id widget-id node-key))
        (buffer (current-buffer)))
    (psi-widget-projection--cancel-mutation-timer state tkey)
    (puthash tkey
             (run-at-time (/ timeout-ms 1000.0) nil
                          #'psi-widget-projection--on-mutation-timeout
                          buffer state ext-id widget-id node-key timeout-ms)
             (psi-emacs-state-projection-mutation-timers state))))

(defun psi-widget-projection--on-mutation-timeout (buffer state ext-id widget-id node-key timeout-ms)
  "Watchdog callback: mutation for EXT-ID/WIDGET-ID/NODE-KEY timed out.
No-op unless BUFFER is live and STATE is non-nil; otherwise runs inside BUFFER
and cancels/clears against STATE's timer store, with no dependence on the
incidental current buffer.  Guard mirrors
`psi-emacs--schedule-notification-dismiss' (`(and (buffer-live-p buffer) st)')."
  (when (and (buffer-live-p buffer) state)
    (with-current-buffer buffer
      (psi-widget-projection--cancel-mutation-timer
       state
       (psi-widget-projection--timer-key ext-id widget-id node-key))
      (psi-widget-projection--clear-mutation-in-flight ext-id widget-id node-key)
      ;; Call global error handler
      (psi-widget-projection--call-error-handler
       `((:widget-id    . ,widget-id)
         (:extension-id . ,ext-id)
         (:error-code   . "mutation-timeout")
         (:message      . ,(format "Mutation timed out after %dms" timeout-ms))
         (:context      . mutation-timeout)))
      (psi-emacs--upsert-projection-block))))

(defun psi-widget-projection--dispatch-mutation (mutation ext-id widget-id node-key
                                                           &optional button-node)
  "Send MUTATION as an EQL mutation over RPC.
Arms a timeout watchdog; cancels it on any response."
  (when (functionp psi-emacs--send-request-function)
    (let* ((mut-name    (alist-get :name   mutation nil nil #'equal))
           (mut-params  (or (alist-get :params mutation nil nil #'equal) '()))
           (query-str   (format "[(%s %s)]"
                                mut-name
                                (psi-widget-projection--edn-encode mut-params)))
           (timeout-ms  (psi-widget-projection--effective-timeout-ms
                         button-node mutation))
           (buffer      (current-buffer))
           (state       psi-emacs--state))
      (psi-widget-projection--arm-mutation-timer state ext-id widget-id node-key timeout-ms)
      (funcall psi-emacs--send-request-function
               state
               "query_eql"
               `((:query . ,query-str))
               (lambda (_frame)
                 ;; Cancel watchdog and clear in-flight against the originating
                 ;; buffer's store on any response; no-op if it is dead.  Guard
                 ;; mirrors `psi-widget-projection--on-mutation-timeout' and the
                 ;; `psi-emacs--schedule-notification-dismiss' precedent
                 ;; (`(and (buffer-live-p buffer) state)').
                 (when (and (buffer-live-p buffer) state)
                   (with-current-buffer buffer
                     (psi-widget-projection--finalize-mutation
                      state ext-id widget-id node-key))))))))

(defun psi-widget-projection--on-collapsible-toggle (widget-id node-key)
  "Handle collapsible toggle for WIDGET-ID / NODE-KEY.
Updates local collapsed state and re-renders — no RPC round-trip."
  (when-let ((parsed (psi-widget-projection--parse-widget-id widget-id)))
    (let* ((ext-id (car parsed))
           (wid    (cdr parsed))
           (lstate (psi-widget-projection--get-lstate ext-id wid))
           (collapsed-p (psi-widget-renderer--collapsed-p lstate node-key))
           (new-lstate  (psi-widget-renderer-lstate-set-collapsed
                         lstate node-key (not collapsed-p))))
      (psi-widget-projection--set-lstate ext-id wid new-lstate)
      (psi-emacs--upsert-projection-block))))

;;; Node lookup

(defun psi-widget-projection--find-node-by-key (node key)
  "Find the first node in NODE tree with :key equal to KEY."
  (when (listp node)
    (if (equal (alist-get :key node nil nil #'equal) key)
        node
      (let ((children (alist-get :children node nil nil #'equal))
            (item-spec (alist-get :item-spec node nil nil #'equal))
            (found nil))
        (dolist (child (append (cond ((vectorp children) (append children nil))
                                     ((listp children) children))
                               (when item-spec (list item-spec))))
          (unless found
            (setq found (psi-widget-projection--find-node-by-key child key))))
        found))))

;;; EDN encoding (minimal — for mutation params)

(defun psi-widget-projection--edn-encode (x)
  "Encode X as a minimal EDN string for mutation params."
  (cond
   ((null x)     "nil")
   ((eq x t)     "true")
   ((eq x :false) "false")
   ((numberp x)  (number-to-string x))
   ((stringp x)  (format "%S" x))
   ((keywordp x) (symbol-name x))
   ((symbolp x)  (symbol-name x))
   ((vectorp x)
    (format "[%s]" (mapconcat #'psi-widget-projection--edn-encode
                              (append x nil) " ")))
   ((listp x)
    (if (and (consp (car x)) (not (listp (caar x))))
        ;; alist → EDN map
        (format "{%s}"
                (mapconcat
                 (lambda (pair)
                   (format "%s %s"
                           (psi-widget-projection--edn-encode (car pair))
                           (psi-widget-projection--edn-encode (cdr pair))))
                 x " "))
      (format "(%s)" (mapconcat #'psi-widget-projection--edn-encode x " "))))
   (t (format "%S" x))))

;;; Event subscriptions

(defun psi-widget-projection--specs-subscribed-to (event-name)
  "Return list of specs whose :subscriptions include EVENT-NAME."
  (when psi-emacs--state
    (let ((specs (psi-emacs-state-projection-widget-specs psi-emacs--state)))
      (cl-remove-if-not
       (lambda (spec)
         (let ((subs (alist-get :subscriptions spec nil nil #'equal)))
           (cl-some (lambda (sub)
                      (equal (alist-get :event-name sub nil nil #'equal)
                             event-name))
                    (if (vectorp subs) (append subs nil) subs))))
       specs))))

(defun psi-widget-projection--merge-event-payload (event-state local-state-map payload)
  "Merge PAYLOAD into EVENT-STATE using LOCAL-STATE-MAP.
LOCAL-STATE-MAP is an alist of (local-key . path-into-payload).
Returns updated event-state alist."
  (let ((result (copy-alist (or event-state nil))))
    (dolist (mapping (if (vectorp local-state-map)
                         (append local-state-map nil)
                       local-state-map))
      (let* ((local-key (car mapping))
             (path      (cdr mapping))
             (path-list (cond ((vectorp path) (append path nil))
                              ((listp path)   path)
                              (t              (list path))))
             (val       (psi-widget-renderer--get-path payload path-list)))
        (setq result (cons (cons local-key val)
                           (cl-remove-if (lambda (pair) (equal (car pair) local-key))
                                         result)))))
    result))

(defun psi-widget-projection--apply-subscribed-event (spec event-name payload)
  "Apply a subscribed EVENT-NAME with PAYLOAD to SPEC's lstate.
Merges local_state_map paths, clears in-flight keys, re-fires query."
  (let* ((ext-id    (alist-get :extension-id spec nil nil #'equal))
         (widget-id (alist-get :id           spec nil nil #'equal))
         (subs      (let ((s (alist-get :subscriptions spec nil nil #'equal)))
                      (if (vectorp s) (append s nil) s)))
         (sub       (cl-find-if
                     (lambda (s)
                       (equal (alist-get :event-name s nil nil #'equal) event-name))
                     subs))
         (lstate    (psi-widget-projection--get-lstate ext-id widget-id))
         (lsm       (alist-get :local-state-map sub nil nil #'equal))
         ;; Merge payload into event-state when local-state-map present
         (new-event-state
          (if lsm
              (psi-widget-projection--merge-event-payload
               (plist-get lstate :event-state) lsm payload)
            (plist-get lstate :event-state)))
         ;; Clear all in-flight keys, update event-state
         (cleared    (psi-widget-renderer-lstate-clear-in-flight lstate))
         (new-lstate (plist-put (copy-sequence cleared) :event-state new-event-state)))
    (psi-widget-projection--set-lstate ext-id widget-id new-lstate)
    ;; Re-fire query (re-renders after data arrives, or immediately if no query)
    (psi-widget-projection--fetch-spec-data (list spec))))

(defun psi-widget-projection-handle-event (event-name payload)
  "Dispatch EVENT-NAME with PAYLOAD to any subscribed widget specs.
Called from psi-events.el for every inbound RPC event."
  (when psi-emacs--state
    (dolist (spec (psi-widget-projection--specs-subscribed-to event-name))
      (psi-widget-projection--apply-subscribed-event spec event-name payload))))

;;; Setup — register interaction handlers

(defun psi-widget-projection-setup ()
  "Register widget interaction handlers with psi-widget-renderer.
Call once during frontend initialisation."
  (setq psi-widget-renderer-on-button-activate
        #'psi-widget-projection--on-button-activate)
  (setq psi-widget-renderer-on-collapsible-toggle
        #'psi-widget-projection--on-collapsible-toggle))

(provide 'psi-widget-projection)
;;; psi-widget-projection.el ends here
