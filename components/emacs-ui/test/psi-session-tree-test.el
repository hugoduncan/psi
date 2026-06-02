;;; psi-session-tree-test.el --- Session tree tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)

;;; ── context/updated + session tree widget ─────────────────────────────────────

(ert-deftest psi-context-updated-stores-snapshot-on-state ()
  "context/updated stores context snapshot on frontend state."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . "s1"))
                               ])))))
    (let ((snap (psi-emacs-state-context-snapshot psi-emacs--state)))
      (should (listp snap))
      (should (equal "s1" (alist-get :active-session-id snap nil nil #'equal))))))

(ert-deftest psi-context-updated-renders-session-tree-widget ()
  "context/updated with multiple sessions adds session-tree widget to projection."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1")
                                (:name . "main")
                                (:is-active . t)
                                (:runtime-state . "waiting")
                                (:is-streaming . nil)
                                (:parent-session-id . nil)
                                (:worktree-path . "/repo/main")
                                (:created-at . "2026-03-16T09:00:00Z")
                                (:updated-at . "2026-03-16T09:15:00Z"))
                               ((:id . "s2")
                                (:name . "fork-1")
                                (:is-active . nil)
                                (:runtime-state . "waiting")
                                (:is-streaming . nil)
                                (:parent-session-id . "s1")
                                (:worktree-path . "/repo/child")
                                (:created-at . "2026-03-16T08:00:00Z")
                                (:updated-at . "2026-03-16T08:10:00Z"))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] — 09:00 / 09:15 — /repo/main ← current [waiting]")
                                                              (:runtime-state . "waiting")
                                                              (:is-current . t))
                                                             ((:text . "  fork-1 [s2] — 08:00 / 08:10 — /repo/child [waiting]")
                                                              (:runtime-state . "waiting")
                                                              (:is-current . nil)
                                                              (:action . ((:type . "command")
                                                                          (:command . "/tree s2"))))])))))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find (lambda (w)
                               (and (equal "psi-session"
                                           (psi-emacs--event-data-get w '(:extension-id extension-id)))
                                    (equal "session-tree"
                                           (psi-emacs--event-data-get w '(:widget-id widget-id)))))
                             widgets)))
      (should tree-w)
      (let* ((lines (append (psi-emacs--event-data-get tree-w '(:content-lines content-lines)) nil))
             (texts (mapcar (lambda (l) (alist-get :text l nil nil #'equal)) lines)))
        (should (= 2 (length lines)))
        (should (cl-some (lambda (txt) (string-match-p "main" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "/repo/main" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "← current" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "fork-1" txt)) texts))))))

(ert-deftest psi-context-updated-session-tree-applies-current-and-waiting-faces ()
  "Structured session tree metadata drives current and waiting faces in the buffer."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:runtime-state . "running") (:is-streaming . t) (:parent-session-id . "s1"))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] ← current [waiting]")
                                                              (:runtime-state . "waiting")
                                                              (:is-current . t))
                                                             ((:text . "  fork-1 [s2] [running]")
                                                              (:runtime-state . "running")
                                                              (:is-current . nil)
                                                              (:action . ((:type . "command")
                                                                          (:command . "/tree s2"))))])))))))
    (goto-char (point-min))
    (search-forward "← current")
    (should (eq 'psi-emacs-session-current-face
                (get-text-property (1- (point)) 'face)))
    (search-forward "[waiting]")
    (should (eq 'psi-emacs-session-waiting-face
                (get-text-property (1- (point)) 'face)))
    (search-forward "[running]")
    (should (eq 'psi-emacs-session-running-face
                (get-text-property (1- (point)) 'face)))))

(ert-deftest psi-context-updated-single-session-omits-widget ()
  "context/updated with only one session does not add session-tree widget."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] ← current [waiting]"))])))))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find (lambda (w)
                               (equal "psi-session"
                                      (psi-emacs--event-data-get w '(:extension-id extension-id))))
                             widgets)))
      (should-not tree-w))))

(ert-deftest psi-session-tree-survives-unrelated-widget-refreshes ()
  "Unrelated ui/widgets updates must not blank the backend-owned session tree."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . "s1"))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] ← current [waiting]"))
                                                             ((:text . "  fork-1 [s2] [waiting]"))])))))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "right")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:text . "Background jobs"))])))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find #'psi-emacs--session-tree-widget-p widgets))
           (ext-w (seq-find (lambda (w)
                              (equal "ext-a"
                                     (psi-emacs--event-data-get w '(:extension-id extension-id))))
                            widgets))
           (buf (buffer-string)))
      (should tree-w)
      (should ext-w)
      (should (string-match-p "main \\[s1\\] ← current" buf))
      (should (string-match-p "fork-1 \\[s2\\]" buf))
      (should (string-match-p "Background jobs" buf)))))

(ert-deftest psi-session-tree-can-still-be-removed-by-context-update ()
  "A later single-session context update remains authoritative and removes the tree."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . "s1"))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] ← current [waiting]"))
                                                             ((:text . "  fork-1 [s2]"))])))))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:text . "Other widget"))])))))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))])
                 (:session-tree-widget . ((:placement . "left")
                                          (:extension-id . "psi-session")
                                          (:widget-id . "session-tree")
                                          (:content-lines . [((:text . "main [s1] ← current [waiting]"))])))))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find #'psi-emacs--session-tree-widget-p widgets))
           (buf (buffer-string)))
      (should-not tree-w)
      (should-not (string-match-p "fork-1 \[s2\]" buf))
      (should (string-match-p "Other widget" buf)))))

(ert-deftest psi-session-tree-line-label-prefers-backend-label ()
  "Session tree label rendering prefers backend-provided canonical labels."
  (should (equal "main [s1] — 09:00 / 09:15 — /repo/main"
                 (psi-emacs--session-tree-line-label
                  '((:label . "main [s1] — 09:00 / 09:15 — /repo/main")
                    (:id . "s1")
                    (:name . "ignored"))))))

(ert-deftest psi-tree-slash-command-dispatches-switch-by-id ()
  "'/tree <id>' slash command calls switch_session with session-id."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-context-snapshot psi-emacs--state)
          '((:active-session-id . "s1")
            (:sessions . (((:id . "s1") (:name . "main")   (:is-active . t)   (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))
                          ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))))))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t)))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree s2"))
      (should (= 1 (length calls)))
      (should (equal "switch_session" (caar calls)))
      (should (equal "s2" (alist-get :session-id (cadar calls) nil nil #'equal))))))

(ert-deftest psi-tree-session-candidates-support-fork-points ()
  "Fork-point slots produce canonical fork action payloads."
  (let* ((slots '(((:id . "s1")
                   (:item-kind . "session")
                   (:name . "main")
                   (:is-active . t))
                  ((:id . "s1")
                   (:item-kind . "fork-point")
                   (:entry-id . "e1")
                   (:display-name . "Branch from here")
                   (:item/default-label . "⎇ Branch from here")
                   (:parent-session-id . "s1"))))
         (candidates (psi-emacs--tree-session-candidates slots "s1"))
         (fork-value (cdr (cadr candidates))))
    (should (stringp (cdar candidates)))
    (should (equal :fork-session (alist-get :action/kind fork-value nil nil #'equal)))
    (should (equal "e1" (alist-get :action/entry-id fork-value nil nil #'equal)))))

(ert-deftest psi-tree-session-candidates-preserve-backend-order ()
  "Fork-point candidates preserve backend order instead of re-sorting locally."
  (let* ((slots '(((:id . "s1") (:item-kind . "session") (:name . "main") (:label . "main [s1] ← current [waiting]") (:is-active . t) (:runtime-state . "waiting"))
                  ((:id . "s2") (:item-kind . "session") (:name . "child") (:label . "child [s2] [waiting]") (:parent-session-id . "s1") (:runtime-state . "waiting"))
                  ((:id . "s1") (:item-kind . "fork-point") (:entry-id . "e1")
                   (:display-name . "Branch from here")
                   (:label . "⎇ Branch from here")
                   (:parent-session-id . "s1"))))
         (candidates (psi-emacs--tree-session-candidates slots "s1"))
         (labels (mapcar #'car candidates)))
    (should (equal '("main [s1] ← current [waiting]"
                     "  child [s2] [waiting]"
                     "  ⎇ Branch from here")
                   labels))))

(ert-deftest psi-tree-picker-dispatches-fork-for-fork-point-selection ()
  "Selecting a fork-point candidate dispatches the fork op with entry-id."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (&rest _args)
                   "  ⎇ Branch from here"))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t))
                ((symbol-function 'psi-emacs--session-tree-line-label)
                 (lambda (slot)
                   (let ((item-kind (or (alist-get :item-kind slot nil nil #'equal) "session")))
                     (if (equal item-kind "fork-point")
                         (concat "⎇ " (or (alist-get :display-name slot nil nil #'equal)
                                           "(unknown fork point)"))
                       (or (alist-get :display-name slot nil nil #'equal)
                           (alist-get :name slot nil nil #'equal)
                           "(unknown)"))))))
        (psi-emacs--tree-select-and-switch
         psi-emacs--state
         "s1"
         '(((:id . "s1") (:item-kind . "session") (:name . "main") (:is-active . t))
           ((:id . "s1") (:item-kind . "fork-point") (:entry-id . "e1")
            (:display-name . "Branch from here") (:parent-session-id . "s1")))))
      (setq calls (nreverse calls))
      (should (equal '(("fork" ((:entry-id . "e1")))) calls)))))

(ert-deftest psi-switch-rehydration-query-restores-completed-and-live-session-output ()
  "Switch rehydration should restore completed tool rows and in-progress stream text.

Regression: after fixing wrong-session event leakage, switching back lost output
that arrived while another session was selected because the frontend only
replayed persisted messages."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'expanded)
    (psi-emacs--replay-session-messages '(((:role . :assistant) (:text . "persisted reply"))))
    (let ((frame '((:kind . :response)
                   (:ok . t)
                   (:data . ((:result . ((:psi.agent-session/tool-lifecycle-summaries .
                                          [((:psi.tool-lifecycle.summary/tool-id . "tool-1")
                                            (:psi.tool-lifecycle.summary/tool-name . "bash")
                                            (:psi.tool-lifecycle.summary/arguments . "{\"command\":\"ls\"}")
                                            (:psi.tool-lifecycle.summary/parsed-args . nil)
                                            (:psi.tool-lifecycle.summary/result-text . "tool output")
                                            (:psi.tool-lifecycle.summary/is-error . nil)
                                            (:psi.tool-lifecycle.summary/completed? . t))])
                                         (:psi.turn/is-streaming . t)
                                         (:psi.turn/text . "live tail")
                                         (:psi.turn/tool-calls .
                                          [((:id . "tool-2")
                                            (:name . "read")
                                            (:arguments . "{\"path\":\"foo.clj\"}"))]))))))))
      (psi-emacs--rehydrate-switch-state-from-query-frame psi-emacs--state frame))
    (let ((text (buffer-string)))
      (should (string-match-p "persisted reply" text))
      (should (string-match-p "\\$ ls success" text))
      (should (string-match-p "tool output" text))
      (should (string-match-p "read foo\\.clj pending" text))
      (should (string-match-p "ψ: live tail" text)))))

(ert-deftest psi-switch-session-rehydration-targets-selected-session-explicitly ()
  "Switch rehydration must target the selected session explicitly.

Regression: if the backend focus drifts before follow-up reads run, Emacs can
rehydrate only the tail/live extras from the intended session while missing the
persisted transcript that existed before switching away."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (cond
                    ((and callback (equal op "get_messages"))
                     (should (equal '((:session-id . "s-b")) params))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "persisted from b"))])))))
                     t)
                    ((and callback (equal op "query_eql"))
                     (should (equal `((:session-id . "s-b")
                                       (:query . ,(psi-emacs--rehydrate-switch-extras-query)))
                                    params))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:result . ((:psi.agent-session/tool-lifecycle-summaries . [])
                                                      (:psi.turn/is-streaming . t)
                                                      (:psi.turn/text . "live tail from b")
                                                      (:psi.turn/tool-calls . [])))))))
                     t)
                    (t t)))))
        (psi-emacs--request-switch-rehydration psi-emacs--state "s-b"))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '("get_messages" "query_eql") (mapcar #'car rpc-calls)))
      (let ((text (buffer-string)))
        (should (string-match-p "persisted from b" text))
        (should (string-match-p "live tail from b" text))))))

(ert-deftest psi-projection-tree-widget-switch-preserves-tool-row-metadata-and-toggle ()
  "Projection `/tree <id>` switching should preserve switch-session rehydration semantics.

Regression: routing tree widget actions through backend `/tree` command used the
canonical event rehydrate path, which restores less tool-row metadata than the
explicit `switch_session` + targeted rehydration flow. That dropped argument
summaries and made toggling body visibility ineffective after returning."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (cond
                    ((and callback (equal op "switch_session"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:session-id . "s2")))))
                     t)
                    ((and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "back on s2"))])))))
                     t)
                    ((and callback (equal op "query_eql"))
                     (funcall callback
                              `((:kind . :response)
                                (:ok . t)
                                (:data . ((:result . ((:psi.agent-session/tool-lifecycle-summaries .
                                                       [((:psi.tool-lifecycle.summary/tool-id . "tool-1")
                                                         (:psi.tool-lifecycle.summary/tool-name . "bash")
                                                         (:psi.tool-lifecycle.summary/arguments . "{\"command\":\"ls\"}")
                                                         (:psi.tool-lifecycle.summary/parsed-args . nil)
                                                         (:psi.tool-lifecycle.summary/result-text . "tool output")
                                                         (:psi.tool-lifecycle.summary/is-error . nil)
                                                         (:psi.tool-lifecycle.summary/completed? . t))])
                                                      (:psi.turn/is-streaming . nil)
                                                      (:psi.turn/text . nil)
                                                      (:psi.turn/tool-calls . [])))))))
                     t)
                    (t t)))))
        (insert (propertize "switch to s2"
                            'psi-widget-command "/tree s2"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (setq rpc-calls (nreverse rpc-calls))
        (should (equal '("switch_session" "get_messages" "query_eql")
                       (mapcar #'car rpc-calls)))
        (let ((text (buffer-string)))
          (should (string-match-p "\\$ ls success" text))
          (should-not (string-match-p "tool output" text))
          (psi-emacs-toggle-tool-output-view)
          (let ((expanded (buffer-string)))
            (should (string-match-p "\\$ ls success\nCall\nTool: bash\nArguments: ((command . \"ls\"))\nResponse\ntool output" expanded))))))))

(ert-deftest psi-tree-slash-command-no-op-when-already-active ()
  "'/tree <active-id>' appends a message and sends no RPC."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-context-snapshot psi-emacs--state)
          '((:active-session-id . "s1")
            (:sessions . (((:id . "s1") (:name . "main") (:is-active . t) (:runtime-state . "waiting") (:is-streaming . nil) (:parent-session-id . nil))))))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t)))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree s1"))
      (should (= 1 (length calls))))))

(ert-deftest psi-tree-command-uses-backend-selector-flow-even-with-context-snapshot ()
  "Bare `/tree` should use backend command flow so fork points/messages can appear."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-context-snapshot psi-emacs--state)
          '((:active-session-id . "s1")
            (:sessions . (((:id . "s1") (:name . "main") (:is-active . t) (:parent-session-id . nil))
                          ((:id . "s2") (:name . "child") (:is-active . nil) (:parent-session-id . "s1"))))))
    (let ((calls nil)
          (watchdog-reset nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t))
                ((symbol-function 'psi-emacs--reset-stream-watchdog)
                 (lambda (&rest _args)
                   (setq watchdog-reset t))))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree"))
      (setq calls (nreverse calls))
      (should (equal '(("command" ((:text . "/tree")))) calls))
      (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
      (should watchdog-reset))))

(ert-deftest psi-session-display-name-uses-name-slot ()
  "psi-emacs--session-display-name returns slot name when present."
  (should (equal "my-session"
                 (psi-emacs--session-display-name
                  '((:id . "abc12345") (:name . "my-session"))))))

(ert-deftest psi-session-display-name-falls-back-to-id-prefix ()
  "psi-emacs--session-display-name returns '(session <prefix>)' when name nil."
  (should (equal "(session abc12345)"
                 (psi-emacs--session-display-name
                  '((:id . "abc123456789") (:name . nil))))))

(ert-deftest psi-session-display-name-supports-command-session-keys ()
  "psi-emacs--session-display-name supports backend command payload key names."
  (should (equal "Alpha"
                 (psi-emacs--session-display-name
                  '((:session-id . "abc123456789") (:session-name . "Alpha")))))
  (should (equal "(session abc12345)"
                 (psi-emacs--session-display-name
                  '((:session-id . "abc123456789") (:session-name . nil))))))

(ert-deftest psi-session-display-name-prefers-derived-display-name ()
  "psi-emacs--session-display-name prefers explicit derived display-name fields."
  (should (equal "Investigate failing tests"
                 (psi-emacs--session-display-name
                  '((:session-id . "abc123456789")
                    (:session-display-name . "Investigate failing tests")
                    (:session-name . nil)))))
  (should (equal "Tree label"
                 (psi-emacs--session-display-name
                  '((:id . "abc123456789")
                    (:display-name . "Tree label")
                    (:name . nil))))))

(ert-deftest psi-tree-session-candidates-include-id-and-support-command-keys ()
  "tree candidates expose backend labels and support command payload keys."
  (let ((candidates
         (psi-emacs--tree-session-candidates
          '(((:session-id . "abc123456789")
             (:session-name . "Alpha")
             (:label . "Alpha [abc12345] — 05:12 / 06:47 — /repo/alpha")
             (:worktree-path . "/repo/alpha")
             (:created-at . "2026-03-16T09:12:00Z")
             (:updated-at . "2026-03-16T10:47:00Z")
             (:is-active . t)
             (:parent-session-id . nil))
            ((:session-id . "def987654321")
             (:session-name . nil)
             (:label . "(session def98765) [def98765] — 04:00 / 04:05 — /repo/beta")
             (:worktree-path . "/repo/beta")
             (:created-at . "2026-03-16T08:00:00Z")
             (:updated-at . "2026-03-16T08:05:00Z")
             (:parent-session-id . "abc123456789")
             (:runtime-state . "waiting")))
          "abc123456789")))
    (should (= 2 (length candidates)))
    (should (equal "abc123456789" (cdar candidates)))
    (should (equal "Alpha [abc12345] — 05:12 / 06:47 — /repo/alpha" (caar candidates)))
    (should (equal "def987654321" (cdadr candidates)))
    (should (equal "  (session def98765) [def98765] — 04:00 / 04:05 — /repo/beta"
                   (car (cadr candidates))))))

(ert-deftest psi-tree-capf-includes-tree-command ()
  "'/tree' appears in slash CAPF candidates (sourced from backend built-in specs)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state
                 :builtin-command-specs
                 '((( :name . "tree") (:description . "open/switch live session tree (TUI)")))))
    (insert "/tr")
    (let* ((capf (psi-emacs-prompt-capf))
           (cands (all-completions "/tr" (nth 2 capf))))
      (should (member "/tree" cands)))))

(provide 'psi-session-tree-test)

;;; psi-session-tree-test.el ends here
