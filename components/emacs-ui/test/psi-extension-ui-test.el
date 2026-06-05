;;; psi-extension-ui-test.el --- Extension UI tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

(ert-deftest psi-start-rpc-client-uses-default-topic-set ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((captured-topics :unset))
      (cl-letf (((symbol-function 'psi-rpc-start!)
                 (lambda (client _spawn-fn _command &optional topics)
                   (setq captured-topics topics)
                   client)))
        (psi-emacs--start-rpc-client (current-buffer)))
      (should (equal psi-rpc-default-topics captured-topics)))))

(ert-deftest psi-start-rpc-client-event-callback-captures-created-client ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((captured-client nil))
      (cl-letf (((symbol-function 'psi-rpc-start!)
                 (lambda (client _spawn-fn _command &optional _topics)
                   (setq captured-client client)
                   client))
                ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
        (psi-emacs--start-rpc-client (current-buffer))
        (funcall (psi-rpc-client-on-event captured-client)
                 '((:event . "footer/updated")
                   (:data . ((:path-line . "/tmp/project")
                             (:status-line . "ready"))))))
      (should (equal "/tmp/project\nready"
                     (substring-no-properties
                      (psi-emacs-state-projection-footer psi-emacs--state)))))))

(ert-deftest psi-extension-ui-widgets-updated-replaces-and-preserves-projection-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "right") (:extension-id . "ext-b") (:widget-id . "w-2") (:text . "Right-B"))
                              ((:placement . "left") (:extension-id . "ext-b") (:widget-id . "w-1") (:text . "Left-B"))
                              ((:placement . "left") (:extension-id . "ext-a") (:widget-id . "w-3") (:text . "Left-A"))])))))
    (let ((buf (buffer-string)))
      (should-not (string-match-p "Extension Widgets:" buf))
      (should (< (string-match-p "Right-B" buf)
                 (string-match-p "Left-B" buf)))
      (should (< (string-match-p "Left-B" buf)
                 (string-match-p "Left-A" buf))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left") (:extension-id . "ext-c") (:widget-id . "w-1") (:text . "Only"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Only" buf))
      (should-not (string-match-p "Left-A" buf))
      (should-not (string-match-p "Right-B" buf)))))

(ert-deftest psi-extension-ui-widgets-updated-renders-content-lines-stacked ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:content . ["Head line"
                                            "Detail line one"
                                            "Detail line two"]))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Head line" buf))
      (should (string-match-p "Detail line one\nDetail line two" buf))
      (should-not (string-match-p "\\[left/ext-a/w-1\\]" buf))
      (should-not (string-match-p "\\[\"Head line\"" buf)))))

(ert-deftest psi-extension-ui-widgets-updated-renders-structured-content-lines ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:content-lines . [((:text . "Plain line"))
                                                  ((:text . "Delete run-1")
                                                   (:action . ((:type . "command")
                                                               (:command . "/delegate remove run-1"))))]))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Plain line" buf))
      (should (string-match-p "Delete run-1" buf)))
    (goto-char (point-min))
    (should (search-forward "Delete run-1" nil t))
    (let ((cmd (get-text-property (max (point-min) (1- (point))) 'psi-widget-command)))
      (should (equal "/delegate remove run-1" cmd)))))

(ert-deftest psi-projection-widget-action-activates-command-via-command-op ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (insert (propertize "Delete run-1"
                            'psi-widget-command "/delegate remove run-1"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (should (equal '(("command" ((:text . "/delegate remove run-1"))))
                       calls))))))

(ert-deftest psi-projection-tree-widget-action-uses-switch-session-by-id ()
  "Widget action `/tree <id>` should switch directly by session id.

This preserves canonical switch rehydration semantics, including tool-row
metadata and toggle behavior, instead of routing through backend `/tree`
command rehydration."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (insert (propertize "switch to s2"
                            'psi-widget-command "/tree s2"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (should (equal '(("switch_session" ((:session-id . "s2"))))
                       calls))))))

(ert-deftest psi-projection-fork-widget-action-uses-fork-op ()
  "Widget action `/fork <entry-id>` should dispatch the fork op directly."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (insert (propertize "fork here"
                            'psi-widget-command "/fork e1"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (should (equal '(("fork" ((:entry-id . "e1"))))
                       calls))))))

(ert-deftest psi-projection-tree-widget-action-clears-transcript-via-rehydrate-events ()
  "Widget `/tree <id>` activation should clear stale transcript via switch-session rehydration."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (insert "old transcript")
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (1+ (length "old transcript")) nil))
    (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
    (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
             (psi-emacs-state-tool-rows psi-emacs--state))
    (cl-letf (((symbol-function 'psi-emacs--request-switch-session-by-id)
               (lambda (_state sid)
                 (should (equal "s2" sid))
                 (psi-emacs--reset-transcript-state)
                 (setf (psi-emacs-state-session-id psi-emacs--state) sid)
                 (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
                 (psi-emacs--replay-session-messages '(((:role . :assistant) (:text . "switched")))))))
      (insert (propertize "switch to s2"
                          'psi-widget-command "/tree s2"
                          'keymap psi-emacs--projection-widget-action-keymap))
      (goto-char (max (point-min) (1- (point-max))))
      (psi-emacs--projection-activate-widget-action)
      (let ((text (buffer-string)))
        (should (string-match-p "ψ: switched\n" text))
        (should-not (string-match-p "old transcript\(?:\n\|$\)" text)))
      (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
      (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
      (should (psi-emacs--input-separator-marker-valid-p)))))

(ert-deftest psi-extension-ui-status-updated-replaces-and-preserves-backend-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-z") (:text . "Zeta"))
                               ((:extension-id . "ext-a") (:text . "Alpha"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Extension Statuses:" buf))
      (should (< (string-match-p "\\[ext-z\\] Zeta" buf)
                 (string-match-p "\\[ext-a\\] Alpha" buf))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-b") (:text . "Only status"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "\\[ext-b\\] Only status" buf))
      (should-not (string-match-p "\\[ext-a\\] Alpha" buf)))))

(ert-deftest psi-extension-ui-widgets-render-divider-before-footer-when-present ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/widgets-updated")
         (:data . ((:widgets . [((:placement . "before-edit")
                                 (:extension-id . "ext-a")
                                 (:widget-id . "w-1")
                                 (:content . ["Widget line"]))])))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:usage-parts . ["latency" "12ms"])))))
      (let ((buf (buffer-string)))
        (should (string-match-p "Widget line\n────────────────────\n~/psi-main" buf))))))

(ert-deftest psi-extension-ui-status-updated-with-input-area-preserves-separator ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-b") (:text . "ok"))])))))
    (should (string-match-p "Extension Statuses:" (buffer-string)))
    (should (string-match-p "\\[ext-b\\] ok" (buffer-string)))
    (should (psi-emacs--input-separator-marker-valid-p))))

(ert-deftest psi-extension-ui-footer-updated-updates-projection-and-preserves-transcript ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (let ((before-anchor (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:status-line . "mode: parity")))))
      (should (string-match-p "ψ: hello" (buffer-string)))
      (should (string-match-p "~/psi-main\nmode: parity" (buffer-string)))
      (should (= (psi-emacs--draft-end-position)
                 (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (should (>= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
                  before-anchor)))))

(ert-deftest psi-extension-ui-footer-updated-uses-canonical-payload-shape ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (let ((before-anchor (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:usage-parts . ["latency" "12ms"])
                   (:session-activity-line . "sessions: waiting helper · running main")
                   (:session-activity-buckets . [((:state . "waiting") (:labels . ["helper"]))
                                                ((:state . "running") (:labels . ["main"]))])
                   (:status-line . "connected")))))
      (should (string-match-p "ψ: hello" (buffer-string)))
      (should (string-match-p "~/psi-main\nlatency 12ms\nsessions: waiting helper . running main\nconnected" (buffer-string)))
      (should-not (string-match-p "mode: parity" (buffer-string)))
      (should (= (psi-emacs--draft-end-position)
                 (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (should (>= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
                  before-anchor)))))

(ert-deftest psi-extension-ui-footer-updated-renders-retry-text-visibly ()
  ;; Active retry footer events render backend-projected text in the buffer.
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["↑4" "↓2"])
                 (:status-line . "retry in 8s · remaining 0/5000")))))
    (let ((footer (substring-no-properties
                   (psi-emacs-state-projection-footer psi-emacs--state)))
          (buf (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p "retry in 8s" footer))
      (should (string-match-p "remaining 0/5000" footer))
      (should (string-match-p "retry in 8s" buf))
      (should (string-match-p "remaining 0/5000" buf)))))

(ert-deftest psi-extension-ui-footer-updated-replaces-visible-retry-text ()
  ;; Changed retry footer events replace stale projected text with the latest text.
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:status-line . "retry in 8s · remaining 0/5000")))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:status-line . "retry in 4s · remaining 2/5000")))))
    (let ((footer (substring-no-properties
                   (psi-emacs-state-projection-footer psi-emacs--state)))
          (buf (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p "retry in 4s" footer))
      (should (string-match-p "remaining 2/5000" footer))
      (should-not (string-match-p "retry in 8s" footer))
      (should (string-match-p "retry in 4s" buf))
      (should-not (string-match-p "retry in 8s" buf)))))

(ert-deftest psi-extension-ui-footer-updated-clears-visible-retry-text ()
  ;; Cleared footer events remove stale retry text from rendered projection.
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:status-line . "retry in 8s · remaining 0/5000")))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["idle"])))))
    (let ((footer (substring-no-properties
                   (psi-emacs-state-projection-footer psi-emacs--state)))
          (buf (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p "~/psi-main" footer))
      (should (string-match-p "idle" footer))
      (should-not (string-match-p "retry in" footer))
      (should-not (string-match-p "remaining 0/5000" footer))
      (should-not (string-match-p "retry in" buf)))))

(ert-deftest psi-extension-ui-footer-structured-activity-applies-state-faces ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["latency" "12ms"])
                 (:session-activity-buckets . [((:state . "waiting") (:labels . ["helper"]))
                                              ((:state . "running") (:labels . ["main"]))])))))
    (goto-char (point-min))
    (search-forward "waiting helper")
    (should (eq 'psi-emacs-session-waiting-face
                (get-text-property (1- (point)) 'face)))
    (search-forward "running main")
    (should (eq 'psi-emacs-session-running-face
                (get-text-property (1- (point)) 'face)))))

(ert-deftest psi-extension-ui-footer-renders-blank-line-and-separator-before-block ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:usage-parts . ["latency" "12ms"]))))))
    (let* ((buf (buffer-string))
           (lines (split-string buf "\n")))
      (should (equal "ψ: hello" (nth 0 lines)))
      (should (equal "" (nth 1 lines)))
      (should (= 20 (string-width (or (nth 2 lines) ""))))
      (should (equal "~/psi-main" (nth 3 lines))))))

(ert-deftest psi-extension-ui-footer-draft-before-projection-sends-non-empty-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["latency" "12ms"])))))
    ;; Point remains in draft area above projection/footer block.
    (insert "second prompt")
    (let ((rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) rpc-calls))))
        (psi-emacs-send-from-buffer nil))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '(("prompt" ((:message . "second prompt"))))
                     rpc-calls)))))

(ert-deftest psi-extension-ui-footer-updated-right-aligns-provider-model-when-detectable ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 70)))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:usage-parts . ["↑4.6k" "↓14" "$0.008" "1.7%/272k"])
                   (:model-text . "(openai) gpt-5.3-codex • thinking off"))))))
    (let* ((lines (split-string (buffer-string) "\n" t))
           (stats-line (seq-find (lambda (line)
                                   (string-match-p "↑4\\.6k" line))
                                 lines)))
      (should (stringp stats-line))
      (should (string-match-p "  (openai) gpt-5\\.3-codex • thinking off$" stats-line))
      (should (= 70 (string-width stats-line))))))


(ert-deftest psi-footer-projection-width-prefers-window-text-width ()
  (with-temp-buffer
    (cl-letf (((symbol-function 'get-buffer-window)
               (lambda (&rest _args) 'fake-win))
              ((symbol-function 'window-text-width)
               (lambda (_win) 96))
              ((symbol-function 'window-body-width)
               (lambda (_win &optional _pixelwise) 120))
              ((symbol-function 'window-margins)
               (lambda (_win) '(2 . 1))))
      (should (= 96 (psi-emacs--projection-window-width))))))

(ert-deftest psi-footer-projection-width-falls-back-to-margins-when-text-width-unavailable ()
  (with-temp-buffer
    (cl-letf (((symbol-function 'get-buffer-window)
               (lambda (&rest _args) 'fake-win))
              ((symbol-function 'window-text-width)
               (lambda (_win) nil))
              ((symbol-function 'window-body-width)
               (lambda (_win &optional _pixelwise) 120))
              ((symbol-function 'window-margins)
               (lambda (_win) '(2 . 1))))
      (should (= 117 (psi-emacs--projection-window-width))))))

(ert-deftest psi-window-configuration-change-refreshes-width-sensitive-separators ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--ensure-input-area)
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:usage-parts . ["latency" "12ms"])))))
      (should (= 20 (psi-emacs--input-separator-current-width))))
    ;; Simulate input-separator drift: marker exists but no longer points at a
    ;; separator character. `psi-emacs--ensure-input-area` should repair it.
    (let ((sep (psi-emacs--input-separator-marker-cache)))
      (save-excursion
        (let ((inhibit-read-only t))
          (goto-char (marker-position sep))
          (delete-char 1)
          (insert "X"))))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 33)))
      (psi-emacs--handle-window-configuration-change)
      (should (= 33 (psi-emacs--input-separator-current-width)))
      (let* ((lines (split-string (buffer-string) "\n"))
             (separator-lines (seq-filter (lambda (line)
                                            (and (not (string-empty-p line))
                                                 (string-match-p "^─+$" line)))
                                          lines)))
        ;; input separator + projection separator should both refresh to width 33
        (should (>= (length separator-lines) 2))
        (should (seq-every-p (lambda (line)
                               (= 33 (string-width line)))
                             separator-lines))))))

(ert-deftest psi-extension-ui-footer-refresh-preserves-assistant-transcript ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["stats1"])))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:text . "reply")))))
    (should (string-match-p "ψ: reply" (buffer-string)))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["stats2"])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "ψ: reply" buf))
      (should (string-match-p "stats2" buf)))))

(ert-deftest psi-extension-ui-tool-rows-render-before-footer-projection ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["stats1"])))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start")
       (:data . ((:tool-id . "t-footer") (:text . "start")))))
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "t-footer pending" buf))
           (path-pos (string-match-p "~/psi-main" buf)))
      (should tool-pos)
      (should path-pos)
      (should (< tool-pos path-pos)))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-footer") (:result-text . "done")))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["stats2"])))))
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "t-footer success" buf))
           (path-pos (string-match-p "~/psi-main" buf)))
      (should tool-pos)
      (should path-pos)
      (should (< tool-pos path-pos)))))

(ert-deftest psi-toggle-tool-output-view-preserves-footer-and-widgets ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:text . "Widget line"))])))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:usage-parts . ["stats"])))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-toggle-preserve")
                 (:tool-name . "bash")
                 (:result-text . "ok")))))
    ;; Toggle twice to exercise both collapsed->expanded and expanded->collapsed rerenders.
    (psi-emacs-toggle-tool-output-view)
    (psi-emacs-toggle-tool-output-view)
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "\\$ .*success" buf))
           (widget-pos (string-match-p "Widget line" buf))
           (path-pos (string-match-p "~/psi-main" buf))
           (bounds (psi-emacs--region-bounds 'projection 'main)))
      (should tool-pos)
      (should widget-pos)
      (should path-pos)
      (should (< tool-pos widget-pos))
      (should (< widget-pos path-pos))
      (should (consp bounds))
      (should (< (car bounds) (cdr bounds))))))

(ert-deftest psi-extension-ui-dialog-requested-confirm-sends-resolve-boolean ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'y-or-n-p)
                 (lambda (&rest _args) t))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-1") (:kind . "confirm") (:prompt . "Proceed?"))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-1") (:result . t))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-select-sends-selected-option-value ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (&rest _args) "Beta"))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-2")
                     (:kind . "select")
                     (:prompt . "Pick")
                     (:options . [((:label . "Alpha") (:value . "a"))
                                  ((:label . "Beta") (:value . "b"))]))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-2") (:result . "b"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-input-sends-resolve-string ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'read-string)
                 (lambda (&rest _args) "typed value"))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-3") (:kind . "input") (:prompt . "Enter"))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-3") (:result . "typed value"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-cancel-on-quit-sends-cancel-dialog ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'y-or-n-p)
                 (lambda (&rest _args)
                   (signal 'quit nil)))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-4") (:kind . "confirm") (:prompt . "Proceed?"))))))
      (setq calls (nreverse calls))
      (should (equal '(("cancel_dialog" ((:dialog-id . "d-4"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-malformed-payload-no-rpc-response ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:kind . "input") (:prompt . "missing id"))))))
      (should (equal '() calls))
      (should (string-match-p "Ignored malformed ui/dialog-requested event" (buffer-string))))))

(ert-deftest psi-extension-ui-notification-adds-visible-entry-in-receive-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (cl-letf (((symbol-function 'run-at-time)
               (lambda (&rest _args) nil)))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/notification")
         (:data . ((:extension-id . "ext-a") (:text . "First")))))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/notification")
         (:data . ((:extension-id . "ext-b") (:text . "Second"))))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Extension Notifications:" buf))
      (should (< (string-match-p "\\[ext-a\\] First" buf)
                 (string-match-p "\\[ext-b\\] Second" buf))))))

(ert-deftest psi-extension-ui-notification-enforces-max-visible-three ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (cl-letf (((symbol-function 'run-at-time)
               (lambda (&rest _args) nil)))
      (dotimes (i 4)
        (psi-emacs--handle-rpc-event
         `((:event . "ui/notification")
           (:data . ((:extension-id . "ext") (:text . ,(format "N%s" (1+ i)))))))))
    (let ((buf (buffer-string)))
      (should-not (string-match-p "\\[ext\\] N1" buf))
      (should (string-match-p "\\[ext\\] N2" buf))
      (should (string-match-p "\\[ext\\] N3" buf))
      (should (string-match-p "\\[ext\\] N4" buf)))))

(ert-deftest psi-extension-ui-notification-auto-dismisses-after-timeout ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((scheduled-seconds nil)
          (scheduled-callback nil)
          (scheduled-args nil))
      (cl-letf (((symbol-function 'run-at-time)
                 (lambda (seconds _repeat fn &rest args)
                   (setq scheduled-seconds seconds)
                   (setq scheduled-callback fn)
                   (setq scheduled-args args)
                   nil)))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/notification")
           (:data . ((:extension-id . "ext-t") (:text . "Temp"))))))
      (should (= psi-emacs-notification-timeout-seconds scheduled-seconds))
      (should (string-match-p "\\[ext-t\\] Temp" (buffer-string)))
      (apply scheduled-callback scheduled-args)
      (should-not (string-match-p "\\[ext-t\\] Temp" (buffer-string))))))

(ert-deftest psi-extension-ui-ignores-stale-client-notification-events ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((active-client (psi-rpc-make-client))
          (stale-client (psi-rpc-make-client)))
      (setf (psi-emacs-state-rpc-client psi-emacs--state) active-client)
      (cl-letf (((symbol-function 'run-at-time)
                 (lambda (&rest _args) nil)))
        (psi-emacs--on-rpc-event
         (current-buffer)
         '((:event . "ui/notification")
           (:data . ((:extension-id . "ext-stale") (:text . "Ignore me"))))
         stale-client))
      (should-not (string-match-p "ext-stale" (buffer-string)))
      (should-not (psi-emacs-state-projection-notifications psi-emacs--state)))))


(provide 'psi-extension-ui-test)

;;; psi-extension-ui-test.el ends here
