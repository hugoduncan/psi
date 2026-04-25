;;; psi-session-commands.el --- Slash commands and session switching for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted slash command routing and session lifecycle helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(declare-function psi-emacs--append-assistant-message "psi-compose" (text))
(declare-function psi-emacs--dispatch-request "psi-compose" (op params &optional callback))
(declare-function psi-emacs--reset-transcript-state "psi-lifecycle" (&optional preserve-tool-output-view-mode))
(declare-function psi-emacs--set-run-state "psi-run-state" (state run-state))
(declare-function psi-emacs--upsert-tool-row "psi-tool-rows" (tool-id stage text &optional tool-name arguments parsed-args is-error details))
(declare-function psi-emacs--assistant-delta "psi-assistant-render" (text))
(declare-function psi-emacs--assistant-content->text "psi-assistant-render" (content))
(declare-function psi-emacs--draft-anchor-at-end-p "psi-compose")
(declare-function psi-emacs--ensure-newline-before-append "psi-compose")
(declare-function psi-emacs--mark-region-read-only "psi-compose" (start end))
(declare-function psi-emacs--apply-prefix-overlay "psi-assistant-render" (line-start prefix face))
(declare-function psi-emacs--set-draft-anchor-to-end "psi-compose")
(declare-function psi-emacs--refresh-header-line "psi-run-state")
(declare-function psi-emacs--set-last-error "psi-run-state" (state message))
(declare-function psi-emacs--event-data-get "psi-events" (data keys))
(declare-function psi-emacs--session-tree-line-label "psi-events" (slot))
(declare-function psi-emacs--ordered-completing-read "psi-events" (prompt candidates &optional default))
(declare-function psi-emacs--reset-stream-watchdog "psi-run-state" (state))
(declare-function psi-emacs--status-diagnostics-string "psi-run-state" (state))
(declare-function psi-emacs--request-frontend-exit "psi-session-commands")

(defcustom psi-emacs-model-selector-provider-scope 'all
  "Provider scope used by `psi-emacs-set-model` when opening the model picker.

When set to `all`, the picker lists all runtime models.
When set to `authenticated`, the picker only lists models whose provider
appears in `:psi.agent-session/authenticated-providers`."
  :type '(choice (const :tag "All providers" all)
                 (const :tag "Providers with configured auth" authenticated))
  :group 'psi-emacs)

(defun psi-emacs--trim-optional-input (value)
  "Return trimmed VALUE text, or nil when VALUE is blank."
  (let ((text (string-trim (format "%s" (or value "")))))
    (unless (string-empty-p text)
      text)))

(defun psi-emacs--normalize-provider-id (value)
  "Return canonical provider id string for VALUE, or nil."
  (when-let ((text (psi-emacs--trim-optional-input value)))
    (downcase (string-remove-prefix ":" text))))

(defun psi-emacs--alist-get-any (alist keys)
  "Return first non-nil value in ALIST for any of KEYS."
  (let ((value nil))
    (while (and keys (null value))
      (setq value (alist-get (car keys) alist nil nil #'equal)
            keys (cdr keys)))
    value))

(defun psi-emacs--model-provider (model)
  "Return normalized provider id text from MODEL entry."
  (psi-emacs--normalize-provider-id
   (psi-emacs--alist-get-any model '(:provider provider :model-provider model-provider))))

(defun psi-emacs--model-id (model)
  "Return normalized model id text from MODEL entry."
  (psi-emacs--trim-optional-input
   (psi-emacs--alist-get-any model '(:id id :model-id model-id))))

(defun psi-emacs--model-name (model)
  "Return optional display name text from MODEL entry."
  (psi-emacs--trim-optional-input
   (psi-emacs--alist-get-any model '(:name name))))

(defun psi-emacs--model-reasoning-p (model)
  "Return non-nil when MODEL reports reasoning support."
  (not (null (psi-emacs--alist-get-any
              model
              '(:reasoning reasoning :supports-reasoning supports-reasoning)))))

(defun psi-emacs--model-selector-query ()
  "Return canonical EQL query string for `/model` selector data."
  "[:psi.agent-session/model-catalog
    :psi.agent-session/authenticated-providers]")

(defun psi-emacs--query-result-from-frame (frame)
  "Extract `query_eql` result payload map from FRAME."
  (let ((data (alist-get :data frame nil nil #'equal)))
    (and (listp data)
         (alist-get :result data nil nil #'equal))))

(defun psi-emacs--model-catalog-from-query-frame (frame)
  "Extract model catalog list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (catalog (and (listp result)
                       (alist-get :psi.agent-session/model-catalog result nil nil #'equal))))
    (cond
     ((vectorp catalog) (append catalog nil))
     ((listp catalog) catalog)
     (t nil))))

(defun psi-emacs--authenticated-providers-from-query-frame (frame)
  "Extract authenticated provider id list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (providers (and (listp result)
                         (alist-get :psi.agent-session/authenticated-providers result nil nil #'equal))))
    (cond
     ((vectorp providers) (append providers nil))
     ((listp providers) providers)
     (t nil))))

(defun psi-emacs--normalize-provider-list (providers)
  "Return deduplicated normalized provider id list from PROVIDERS."
  (delete-dups
   (delq nil (mapcar #'psi-emacs--normalize-provider-id providers))))

(defun psi-emacs--filter-model-catalog (catalog authenticated-providers)
  "Return filtered CATALOG using AUTHENTICATED-PROVIDERS and user scope setting."
  (if (eq psi-emacs-model-selector-provider-scope 'authenticated)
      (let ((allowed (psi-emacs--normalize-provider-list authenticated-providers))
            (out nil))
        (dolist (model catalog)
          (when (member (psi-emacs--model-provider model) allowed)
            (push model out)))
        (nreverse out))
    catalog))

(defun psi-emacs--sort-model-catalog (catalog)
  "Return CATALOG sorted by provider id then model id."
  (sort (copy-sequence catalog)
        (lambda (a b)
          (let ((ap (or (psi-emacs--model-provider a) ""))
                (bp (or (psi-emacs--model-provider b) ""))
                (ai (or (psi-emacs--model-id a) ""))
                (bi (or (psi-emacs--model-id b) "")))
            (if (string= ap bp)
                (string< ai bi)
              (string< ap bp))))))

(defun psi-emacs--model-candidate-label (model)
  "Return deterministic completion label for MODEL entry."
  (let* ((provider (or (psi-emacs--model-provider model) "?"))
         (model-id (or (psi-emacs--model-id model) "?"))
         (name (psi-emacs--model-name model))
         (reasoning? (psi-emacs--model-reasoning-p model))
         (name-part (if name (format " — %s" name) ""))
         (reasoning-part (if reasoning? " • reasoning" "")))
    (format "(%s) %s%s%s" provider model-id name-part reasoning-part)))

(defun psi-emacs--model-selector-candidates (catalog)
  "Build deterministic completion candidates from model CATALOG.

Return list of (DISPLAY . MODEL-ENTRY)."
  (let ((seen (make-hash-table :test #'equal))
        (candidates nil))
    (dolist (model (psi-emacs--sort-model-catalog catalog))
      (let ((provider (psi-emacs--model-provider model))
            (model-id (psi-emacs--model-id model)))
        (when (and provider model-id)
          (let* ((base (psi-emacs--model-candidate-label model))
                 (count (1+ (gethash base seen 0)))
                 (label (if (= count 1)
                            base
                          (format "%s (%d)" base count))))
            (puthash base count seen)
            (push (cons label model) candidates)))))
    (nreverse candidates)))

(defun psi-emacs--model-selector-default-label (candidates)
  "Return default completion label from CANDIDATES based on active session model."
  (let ((provider (psi-emacs--normalize-provider-id (psi-emacs--session-model-default-provider)))
        (model-id (psi-emacs--trim-optional-input (psi-emacs--session-model-default-id)))
        (default nil))
    (dolist (candidate candidates)
      (let* ((model (cdr candidate))
             (candidate-provider (psi-emacs--model-provider model))
             (candidate-id (psi-emacs--model-id model)))
        (when (and (null default)
                   provider
                   model-id
                   (equal candidate-provider provider)
                   (equal candidate-id model-id))
          (setq default (car candidate)))))
    default))

(defun psi-emacs--select-model-candidate (candidates)
  "Prompt for model selection from CANDIDATES.

CANDIDATES is a list of (DISPLAY . MODEL-ENTRY).
Returns selected MODEL-ENTRY map or nil when cancelled/no selection."
  (condition-case _
      (let* ((labels (mapcar #'car candidates))
             (default (or (psi-emacs--model-selector-default-label candidates)
                          (car labels)))
             (chosen (completing-read "Model: " labels nil t nil nil default)))
        (when (and (stringp chosen)
                   (not (string-empty-p chosen)))
          (cdr (assoc chosen candidates))))
    (quit nil)))

(defun psi-emacs--model-selector-error-message (frame)
  "Return deterministic model-selector error text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to open model selector: %s" details)
      "Unable to open model selector.")))

(defun psi-emacs--model-selector-empty-message ()
  "Return deterministic empty-model-selector message for current scope."
  (if (eq psi-emacs-model-selector-provider-scope 'authenticated)
      "No models available for providers with configured auth. Use /login or set provider API keys, or customize `psi-emacs-model-selector-provider-scope`."
    "No models available from backend model catalog."))

(defun psi-emacs--request-model-selector-data (callback)
  "Fetch model selector payload via `query_eql` and invoke CALLBACK."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--model-selector-query)))
   callback))

(defun psi-emacs--handle-model-selector-response (frame)
  "Handle model selector `query_eql` FRAME and dispatch selected model."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (let* ((catalog (psi-emacs--model-catalog-from-query-frame frame))
             (authenticated-providers
              (psi-emacs--authenticated-providers-from-query-frame frame))
             (filtered (psi-emacs--filter-model-catalog catalog authenticated-providers))
             (candidates (psi-emacs--model-selector-candidates filtered)))
        (if (null candidates)
            (psi-emacs--append-assistant-message (psi-emacs--model-selector-empty-message))
          (when-let* ((selected (psi-emacs--select-model-candidate candidates))
                      (provider (psi-emacs--model-provider selected))
                      (model-id (psi-emacs--model-id selected)))
            (psi-emacs-set-model provider model-id))))
    (psi-emacs--append-assistant-message
     (psi-emacs--model-selector-error-message frame))))

(defun psi-emacs--open-model-selector ()
  "Open standard Emacs completion UI for selecting a runtime model."
  (let ((buffer (current-buffer))
        (state psi-emacs--state))
    (psi-emacs--request-model-selector-data
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--handle-model-selector-response frame))))))))

(defun psi-emacs--prompt-template-query ()
  "Return canonical EQL query string for slash completion data."
  "[:psi.extension/command-names :psi.agent-session/prompt-templates]")

(defun psi-emacs--request-slash-completion-data (callback)
  "Fetch slash completion data via `query_eql` and invoke CALLBACK."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--prompt-template-query)))
   callback))

(defun psi-emacs--extension-command-names-from-query-frame (frame)
  "Extract extension command names vector/list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (names (and (listp result)
                     (alist-get :psi.extension/command-names result nil nil #'equal))))
    (cond
     ((vectorp names) (append names nil))
     ((listp names) names)
     (t nil))))

(defun psi-emacs--prompt-templates-from-query-frame (frame)
  "Extract prompt templates vector/list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (templates (and (listp result)
                         (alist-get :psi.agent-session/prompt-templates result nil nil #'equal))))
    (cond
     ((vectorp templates) (append templates nil))
     ((listp templates) templates)
     (t nil))))

(defun psi-emacs--normalize-slash-completion-names (names)
  "Return canonical normalized command name list from NAMES."
  (mapcar (lambda (name)
            (string-trim (format "%s" (or name ""))))
          (or names [])))

(defun psi-emacs--slash-completion-token (names templates)
  "Return deterministic token representing slash completion source state."
  (list :commands (psi-emacs--normalize-slash-completion-names names)
        :templates (mapcar (lambda (tpl)
                             (list (psi-emacs--trim-optional-input
                                    (psi-emacs--alist-get-any tpl '(:name name)))
                                   (psi-emacs--trim-optional-input
                                    (psi-emacs--alist-get-any tpl '(:description description)))))
                           (or templates []))))

(defun psi-emacs--apply-slash-completion-data (names templates)
  "Store slash completion NAMES and TEMPLATES on frontend state."
  (let ((normalized-names (psi-emacs--normalize-slash-completion-names names)))
    (setf (psi-emacs-state-extension-command-names psi-emacs--state)
          normalized-names)
    (setf (psi-emacs-state-prompt-templates psi-emacs--state)
          (or templates []))
    (setf (psi-emacs-state-slash-completion-token psi-emacs--state)
          (psi-emacs--slash-completion-token normalized-names templates))))

(defun psi-emacs--refresh-slash-completion-data ()
  "Refresh cached extension command names and prompt templates for slash completion."
  (let ((buffer (current-buffer))
        (state psi-emacs--state))
    (psi-emacs--request-slash-completion-data
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let ((names (psi-emacs--extension-command-names-from-query-frame frame))
                   (templates (psi-emacs--prompt-templates-from-query-frame frame)))
               (psi-emacs--apply-slash-completion-data names templates)))))))))

(defun psi-emacs--state-prompt-template-specs ()
  "Return prompt-template slash specs sourced from frontend state."
  (let ((templates (and psi-emacs--state
                        (psi-emacs-state-prompt-templates psi-emacs--state))))
    (mapcar (lambda (tpl)
              (let* ((name (psi-emacs--trim-optional-input
                            (psi-emacs--alist-get-any tpl '(:name name))))
                     (description (psi-emacs--trim-optional-input
                                   (psi-emacs--alist-get-any tpl '(:description description)))))
                (when name
                  (cons (concat "/" name)
                        (or description "Prompt template")))))
            (or templates []))))

(defun psi-emacs--slash-help-text ()
  "Return deterministic help text for supported slash commands."
  (string-join
   (list "Supported slash commands:"
         "/quit, /exit  Exit this psi buffer"
         "/resume [path] Resume a prior session (selector when path omitted)"
         "/tree         Switch between live sessions (completing-read picker)"
         "/new          Start a fresh backend session"
         "/status       Show frontend diagnostics"
         "/worktree     Show git worktree context"
         "/jobs [status ...]   List background jobs"
         "/job <job-id>        Inspect a background job"
         "/cancel-job <job-id> Request cancellation for a background job"
         "/model [provider model-id]    Open model selector or set directly"
         "/thinking [level]             Open thinking selector or set directly"
         "/help, /?     Show this help")
   "\n"))

(defun psi-emacs--new-session-error-message (frame)
  "Return deterministic /new error text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to start a fresh backend session: %s" details)
      "Unable to start a fresh backend session.")))

(defun psi-emacs--handle-new-session-response (state frame)
  "Apply /new callback FRAME effects to the current frontend buffer."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (when (and state (eq state psi-emacs--state))
        ;; /new is a non-reconnect session operation, so keep current tool view.
        ;; Clear stale transcript state, then fetch canonical messages so startup
        ;; prompts are replayed in the frontend transcript.
        ;; footer/updated + session/updated events arrive before the response frame
        ;; and correctly set projection-footer. Capture it before reset clears it.
        ;; Pin the selected session id locally before follow-up reads so any
        ;; already-arrived footer/session events for the new session are accepted
        ;; deterministically instead of being filtered as stale-session traffic.
        (let* ((data (alist-get :data frame nil nil #'equal))
               (target-session-id (or (alist-get :session-id data nil nil #'equal)
                                      (alist-get 'session-id data nil nil #'equal)
                                      (and psi-emacs--state
                                           (psi-emacs-state-session-id psi-emacs--state))))
               (saved-footer (and psi-emacs--state
                                  (psi-emacs-state-projection-footer psi-emacs--state))))
          (psi-emacs--reset-transcript-state t)
          (when (and target-session-id psi-emacs--state)
            (setf (psi-emacs-state-session-id psi-emacs--state) target-session-id))
          (when (and saved-footer psi-emacs--state)
            (setf (psi-emacs-state-projection-footer psi-emacs--state) saved-footer)
            (when (fboundp 'psi-emacs--upsert-projection-block)
              (psi-emacs--upsert-projection-block))))
        ;; Only focus input — footer already correctly set from footer/updated event.
        (when (fboundp 'psi-emacs--focus-input-area)
          (psi-emacs--focus-input-area (current-buffer)))
        (psi-emacs--set-run-state state 'streaming)
        (psi-emacs--request-get-messages-for-switch state))
    (psi-emacs--append-assistant-message
     (psi-emacs--new-session-error-message frame))))

(defun psi-emacs--request-get-messages-for-switch (state)
  "Request canonical messages after a session switch or /new response.

Refresh transcript state for STATE."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "get_messages"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (and state (eq state psi-emacs--state))
             (let ((messages (psi-emacs--frame-messages-list frame)))
               (psi-emacs--replay-session-messages messages)
               (psi-emacs--refresh-header-line)))))))))

(defun psi-emacs--request-new-session (state)
  "Request a fresh backend session for /new and rehydrate transcript for STATE."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "new_session"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (psi-emacs--handle-new-session-response state frame)))))))

(defun psi-emacs--session-model-default-provider ()
  "Return current session model provider for interactive defaults."
  (or (and psi-emacs--state
           (psi-emacs-state-session-model-provider psi-emacs--state))
      ""))

(defun psi-emacs--session-model-default-id ()
  "Return current session model id for interactive defaults."
  (or (and psi-emacs--state
           (psi-emacs-state-session-model-id psi-emacs--state))
      ""))

(defun psi-emacs--session-thinking-level-default-text ()
  "Return current session thinking level text for interactive defaults."
  (let ((level (and psi-emacs--state
                    (psi-emacs-state-session-thinking-level psi-emacs--state))))
    (if level
        (format "%s" level)
      "")))

(defun psi-emacs--trim-required-input (label value)
  "Return trimmed VALUE text; raise user error when blank for LABEL."
  (let ((text (string-trim (format "%s" (or value "")))))
    (when (string-empty-p text)
      (user-error "%s is required" label))
    text))

(defun psi-emacs-set-model (&optional provider model-id)
  "Select PROVIDER/MODEL-ID via `set_model` RPC op.

When PROVIDER/MODEL-ID are omitted, open a completion picker backed by
runtime model catalog query data."
  (interactive)
  (let ((provider* (psi-emacs--normalize-provider-id provider))
        (model-id* (psi-emacs--trim-optional-input model-id)))
    (if (and provider* model-id*)
        (when (psi-emacs--dispatch-request
               "set_model"
               `((:provider . ,provider*)
                 (:model-id . ,model-id*)))
          (message "psi: requested model (%s) %s" provider* model-id*))
      (psi-emacs--open-model-selector))))

(defun psi-emacs-cycle-model (&optional direction)
  "Cycle model in DIRECTION (`next` or `prev`) via `cycle_model` RPC."
  (interactive
   (list (completing-read "Cycle model direction: " '("next" "prev") nil t nil nil "next")))
  (let ((direction* (string-trim (format "%s" (or direction "next")))))
    (unless (member direction* '("next" "prev"))
      (user-error "Direction must be \"next\" or \"prev\""))
    (when (psi-emacs--dispatch-request
           "cycle_model"
           `((:direction . ,direction*)))
      (message "psi: requested model cycle (%s)" direction*))))

(defun psi-emacs-cycle-model-next ()
  "Cycle to the next available model via `cycle_model`."
  (interactive)
  (psi-emacs-cycle-model "next"))

(defun psi-emacs-cycle-model-prev ()
  "Cycle to the previous available model via `cycle_model`."
  (interactive)
  (psi-emacs-cycle-model "prev"))

(defun psi-emacs-set-thinking-level (level)
  "Set thinking LEVEL via `set_thinking_level` RPC op."
  (interactive
   (list (read-string "Thinking level: " (psi-emacs--session-thinking-level-default-text))))
  (let ((level* (psi-emacs--trim-required-input "Thinking level" level)))
    (when (psi-emacs--dispatch-request
           "set_thinking_level"
           `((:level . ,level*)))
      (message "psi: requested thinking level %s" level*))))

(defun psi-emacs-cycle-thinking-level ()
  "Cycle thinking level via `cycle_thinking_level` RPC op."
  (interactive)
  (when (psi-emacs--dispatch-request "cycle_thinking_level" nil)
    (message "psi: requested thinking level cycle")))

(defun psi-emacs--slash-command-args (message)
  "Return MESSAGE slash command tail as token list."
  (cdr (split-string (string-trim (or message "")) "[ \t\n\r]+" t)))

(defun psi-emacs--handle-idle-model-command (_state message)
  "Handle idle `/model` MESSAGE."
  (let* ((args (psi-emacs--slash-command-args message))
         (argc (length args)))
    (cond
     ((= argc 0)
      (call-interactively #'psi-emacs-set-model))
     ((= argc 2)
      (let ((provider (nth 0 args))
            (model-id (nth 1 args)))
        (when (psi-emacs--dispatch-request
               "set_model"
               `((:provider . ,provider)
                 (:model-id . ,model-id)))
          (message "psi: requested model (%s) %s" provider model-id))))
     (t
      (psi-emacs--append-assistant-message
       "Usage: /model OR /model <provider> <model-id>")))))

(defun psi-emacs--handle-idle-thinking-command (_state message)
  "Handle idle `/thinking` MESSAGE."
  (let* ((args (psi-emacs--slash-command-args message))
         (argc (length args)))
    (cond
     ((= argc 0)
      (call-interactively #'psi-emacs-set-thinking-level))
     ((= argc 1)
      (let ((level (car args)))
        (when (psi-emacs--dispatch-request
               "set_thinking_level"
               `((:level . ,level)))
          (message "psi: requested thinking level %s" level))))
     (t
      (psi-emacs--append-assistant-message
       "Usage: /thinking OR /thinking <level>")))))

(defun psi-emacs--resume-args-from-message (message)
  "Extract `/resume` argument tail from MESSAGE.

Return nil for no argument. Otherwise return trimmed argument string."
  (let* ((trimmed (string-trim (or message "")))
         (tail (string-trim (string-remove-prefix "/resume" trimmed))))
    (unless (string-empty-p tail)
      tail)))

(defun psi-emacs--resume-session-list-query ()
  "Return canonical EQL query string for `/resume` session discovery."
  "[{:psi.session/list [:psi.session-info/path
                        :psi.session-info/name
                        :psi.session-info/worktree-path
                        :psi.session-info/first-message
                        :psi.session-info/modified]}]")

(defun psi-emacs--resume-session-list-from-query-frame (frame)
  "Extract session list vector from `query_eql` FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (result (and (listp data) (alist-get :result data nil nil #'equal)))
         (sessions (and (listp result)
                        (alist-get :psi.session/list result nil nil #'equal))))
    (cond
     ((vectorp sessions) (append sessions nil))
     ((listp sessions) sessions)
     (t nil))))

(defun psi-emacs--resume-session-description (session)
  "Return description-first label seed for SESSION."
  (let ((name (string-trim (or (alist-get :psi.session-info/name session nil nil #'equal) "")))
        (first-message (string-trim (or (alist-get :psi.session-info/first-message session nil nil #'equal) "")))
        (path (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) ""))))
    (cond
     ((not (string-empty-p name)) name)
     ((not (string-empty-p first-message)) first-message)
     ((not (string-empty-p path)) (file-name-nondirectory path))
     (t "(unnamed session)"))))

(defun psi-emacs--resume-session-worktree-path (session)
  "Return trimmed worktree path for SESSION, or empty string."
  (string-trim (or (alist-get :psi.session-info/worktree-path session nil nil #'equal)
                   (alist-get :psi.session-info/cwd session nil nil #'equal)
                   "")))

(defun psi-emacs--resume-session-modified-seconds (session)
  "Return SESSION modified timestamp as seconds since epoch.

Unreadable/missing timestamps normalize to 0."
  (let ((modified (alist-get :psi.session-info/modified session nil nil #'equal)))
    (cond
     ((numberp modified) (float modified))
     ((stringp modified)
      (or (ignore-errors (float-time (date-to-time modified))) 0.0))
     (t
      (or (ignore-errors (float-time modified)) 0.0)))))

(defun psi-emacs--resume-session-path (session)
  "Return trimmed canonical session path for SESSION, or empty string."
  (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) "")))

(defun psi-emacs--sort-resume-sessions (sessions)
  "Sort SESSIONS by modified desc (newest first), then path asc."
  (sort (copy-sequence sessions)
        (lambda (a b)
          (let ((am (psi-emacs--resume-session-modified-seconds a))
                (bm (psi-emacs--resume-session-modified-seconds b))
                (ap (psi-emacs--resume-session-path a))
                (bp (psi-emacs--resume-session-path b)))
            (if (/= am bm)
                (> am bm)
              (string< ap bp))))))

(defun psi-emacs--resume-session-candidates (sessions)
  "Build deterministic selector candidates from SESSIONS.

Returns list of cons cells (DISPLAY . CANONICAL-PATH)."
  (let ((seen (make-hash-table :test #'equal))
        (candidates nil))
    (dolist (session (psi-emacs--sort-resume-sessions sessions))
      (let ((path (psi-emacs--resume-session-path session)))
        (when (not (string-empty-p path))
          (let* ((description (psi-emacs--resume-session-description session))
                 (worktree (psi-emacs--resume-session-worktree-path session))
                 (base (if (string-empty-p worktree)
                           (format "%s — %s" description path)
                         (format "%s — %s — %s" description worktree path)))
                 (count (1+ (gethash base seen 0)))
                 (label (if (= count 1)
                            base
                          (format "%s (%d)" base count))))
            (puthash base count seen)
            (push (cons label path) candidates)))))
    (nreverse candidates)))

(defun psi-emacs--resume-select-session-path (candidates)
  "Prompt for session selection from CANDIDATES.

CANDIDATES is a list of (DISPLAY . CANONICAL-PATH).
Returns canonical path string, or nil when cancelled/no selection."
  (condition-case _
      (let* ((labels (mapcar #'car candidates))
             (chosen (completing-read "Resume session: " labels nil t)))
        (when (and (stringp chosen)
                   (not (string-empty-p chosen)))
          (cdr (assoc chosen candidates))))
    (quit nil)))

(defun psi-emacs--request-resume-session-list (callback)
  "Fetch session list via `query_eql` and invoke CALLBACK with response frame."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--resume-session-list-query)))
   callback))

(defun psi-emacs--rpc-frame-success-p (frame)
  "Return non-nil when FRAME is a successful RPC response."
  (and (eq (alist-get :kind frame) :response)
       (eq (alist-get :ok frame) t)))

(defun psi-emacs--frame-messages-list (frame)
  "Extract `:messages` list from FRAME payload.

Returns a proper list in canonical order, or nil when missing/unreadable."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (messages (and (listp data)
                        (alist-get :messages data nil nil #'equal))))
    (cond
     ((vectorp messages) (append messages nil))
     ((listp messages) messages)
     (t nil))))

(defun psi-emacs--frame-result-map (frame)
  "Extract `:result` map from a successful `query_eql` FRAME, or nil."
  (let ((data (alist-get :data frame nil nil #'equal)))
    (and (listp data)
         (alist-get :result data nil nil #'equal))))

(defun psi-emacs--rehydrate-switch-query ()
  "Return EQL query for switch-time transcript + live turn reconstruction."
  "[:psi.agent-session/messages
    {:psi.agent-session/tool-lifecycle-summaries
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/completed?]}
    :psi.turn/phase
    :psi.turn/is-streaming
    :psi.turn/text
    {:psi.turn/tool-calls [:id :name :arguments]}
    :psi.turn/tool-call-count]")

(defun psi-emacs--rehydrate-switch-extras-query ()
  "Return EQL query for switch-time non-message reconstruction only."
  "[{:psi.agent-session/tool-lifecycle-summaries
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/completed?]}
    :psi.turn/phase
    :psi.turn/is-streaming
    :psi.turn/text
    {:psi.turn/tool-calls [:id :name :arguments]}
    :psi.turn/tool-call-count]")

(defun psi-emacs--rehydrate-tool-summaries (tool-summaries)
  "Replay completed TOOL-SUMMARIES into tool rows."
  (dolist (summary tool-summaries)
    (when (listp summary)
      (let ((tool-id (or (alist-get :psi.tool-lifecycle.summary/tool-id summary nil nil #'equal)
                         (alist-get 'psi.tool-lifecycle.summary/tool-id summary nil nil #'equal)))
            (tool-name (or (alist-get :psi.tool-lifecycle.summary/tool-name summary nil nil #'equal)
                           (alist-get 'psi.tool-lifecycle.summary/tool-name summary nil nil #'equal)))
            (arguments (or (alist-get :psi.tool-lifecycle.summary/arguments summary nil nil #'equal)
                           (alist-get 'psi.tool-lifecycle.summary/arguments summary nil nil #'equal)))
            (parsed-args (or (alist-get :psi.tool-lifecycle.summary/parsed-args summary nil nil #'equal)
                             (alist-get 'psi.tool-lifecycle.summary/parsed-args summary nil nil #'equal)))
            (result-text (or (alist-get :psi.tool-lifecycle.summary/result-text summary nil nil #'equal)
                             (alist-get 'psi.tool-lifecycle.summary/result-text summary nil nil #'equal)
                             ""))
            (is-error (or (alist-get :psi.tool-lifecycle.summary/is-error summary nil nil #'equal)
                          (alist-get 'psi.tool-lifecycle.summary/is-error summary nil nil #'equal)))
            (completed? (or (alist-get :psi.tool-lifecycle.summary/completed? summary nil nil #'equal)
                            (alist-get 'psi.tool-lifecycle.summary/completed? summary nil nil #'equal))))
        (when (and tool-id completed?)
          (psi-emacs--upsert-tool-row tool-id "result" result-text tool-name arguments parsed-args is-error nil))))))

(defun psi-emacs--rehydrate-live-turn-tool-calls (tool-calls)
  "Replay in-progress TOOL-CALLS into pending tool rows."
  (dolist (tool-call tool-calls)
    (when (listp tool-call)
      (let ((tool-id (or (alist-get :id tool-call nil nil #'equal)
                         (alist-get 'id tool-call nil nil #'equal)))
            (tool-name (or (alist-get :name tool-call nil nil #'equal)
                           (alist-get 'name tool-call nil nil #'equal)))
            (arguments (or (alist-get :arguments tool-call nil nil #'equal)
                           (alist-get 'arguments tool-call nil nil #'equal)
                           "")))
        (when tool-id
          (psi-emacs--upsert-tool-row tool-id "start" "" tool-name arguments nil nil nil))))))

(defun psi-emacs--rehydrate-switch-state-from-query-frame (state frame)
  "Restore transcript-adjacent switch state for STATE from `query_eql` FRAME."
  (when (and state (eq state psi-emacs--state))
    (let* ((result (psi-emacs--frame-result-map frame))
           (messages (or (alist-get :psi.agent-session/messages result nil nil #'equal)
                         (alist-get 'psi.agent-session/messages result nil nil #'equal)
                         '()))
           (tool-summaries (or (alist-get :psi.agent-session/tool-lifecycle-summaries result nil nil #'equal)
                               (alist-get 'psi.agent-session/tool-lifecycle-summaries result nil nil #'equal)
                               '()))
           (turn-is-streaming (or (alist-get :psi.turn/is-streaming result nil nil #'equal)
                                  (alist-get 'psi.turn/is-streaming result nil nil #'equal)))
           (turn-text (or (alist-get :psi.turn/text result nil nil #'equal)
                          (alist-get 'psi.turn/text result nil nil #'equal)))
           (turn-tool-calls (or (alist-get :psi.turn/tool-calls result nil nil #'equal)
                                (alist-get 'psi.turn/tool-calls result nil nil #'equal)
                                '())))
      (psi-emacs--replay-session-messages
       (cond
        ((vectorp messages) (append messages nil))
        ((listp messages) messages)
        (t nil)))
      (psi-emacs--rehydrate-tool-summaries
       (cond
        ((vectorp tool-summaries) (append tool-summaries nil))
        ((listp tool-summaries) tool-summaries)
        (t nil)))
      (when turn-is-streaming
        (psi-emacs--rehydrate-live-turn-tool-calls
         (cond
          ((vectorp turn-tool-calls) (append turn-tool-calls nil))
          ((listp turn-tool-calls) turn-tool-calls)
          (t nil)))
        (when (and (stringp turn-text)
                   (not (string-empty-p turn-text)))
          (setf (psi-emacs-state-assistant-in-progress state) turn-text)
          (psi-emacs--assistant-delta turn-text))))))

(defun psi-emacs--message-text-from-content (content)
  "Extract display text from message CONTENT payload."
  (cond
   ((stringp content) content)
   ((and (listp content)
         (or (alist-get :text content nil nil #'equal)
             (alist-get 'text content nil nil #'equal)))
    (or (alist-get :text content nil nil #'equal)
        (alist-get 'text content nil nil #'equal)
        ""))
   (t (psi-emacs--assistant-content->text content))))

(defun psi-emacs--role-is-user-p (role-raw)
  "Return non-nil when ROLE-RAW represents the user role.
Handles string \"user\", bare symbol \\='user, and keyword :user,
since the backend serialises role as the string \"user\" which
`intern' converts to the bare symbol \\='user, not the keyword :user."
  (or (equal role-raw "user")
      (eq role-raw 'user)
      (eq role-raw :user)))

(defun psi-emacs--message->transcript-line (message)
  "Render MESSAGE as one deterministic transcript line."
  (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                       (alist-get 'role message nil nil #'equal)
                       :assistant))
         (content (or (alist-get :content message nil nil #'equal)
                      (alist-get 'content message nil nil #'equal)))
         (error-message (or (alist-get :error-message message nil nil #'equal)
                            (alist-get 'error-message message nil nil #'equal)))
         (content-text (psi-emacs--message-text-from-content content))
         (text (or (alist-get :text message nil nil #'equal)
                   (alist-get 'text message nil nil #'equal)
                   (alist-get :message message nil nil #'equal)
                   (alist-get 'message message nil nil #'equal)
                   (when (and (stringp content-text)
                              (not (string-empty-p (string-trim content-text))))
                     content-text)
                   (when (and (stringp error-message)
                              (not (string-empty-p (string-trim error-message))))
                     (format "[error] %s" error-message))
                   "")))
    (format "%s: %s\n"
            (if (psi-emacs--role-is-user-p role-raw) "User" "ψ")
            text)))

(defun psi-emacs--replay-session-messages (messages)
  "Replay MESSAGES into transcript in deterministic input order."
  (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p)))
    (save-excursion
      (dolist (message messages)
        (when (listp message)
          (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                               (alist-get 'role message nil nil #'equal)
                               :assistant)))
            (let ((inhibit-read-only t))
              (psi-emacs--ensure-newline-before-append)
              (let ((line-start (point)))
                (insert (psi-emacs--message->transcript-line message))
                (psi-emacs--mark-region-read-only line-start (point))
                (save-excursion
                  (goto-char line-start)
                  (if (psi-emacs--role-is-user-p role-raw)
                      (psi-emacs--apply-prefix-overlay line-start "User: " 'psi-emacs-user-prompt-face)
                    (psi-emacs--apply-prefix-overlay line-start "ψ: " 'psi-emacs-assistant-reply-face)))))))))
    (when follow-anchor
      (psi-emacs--set-draft-anchor-to-end))))

(defun psi-emacs--request-switch-rehydration (state &optional target-session-id)
  "Request transcript + tool/live-turn rehydration for switched STATE.

When TARGET-SESSION-ID is non-nil, target both reads explicitly so transcript
rehydration remains correct even if backend focus changes concurrently."
  (let ((buffer (current-buffer))
        (target-params (when (and (stringp target-session-id)
                                  (not (string-empty-p target-session-id)))
                         `((:session-id . ,target-session-id)))))
    ;; Keep canonical message replay on get_messages, then layer in non-message
    ;; reconstruction from query_eql so output produced while another session was
    ;; selected becomes visible when switching back.
    (psi-emacs--dispatch-request
     "get_messages"
     target-params
     (lambda (messages-frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--replay-session-messages
              (psi-emacs--frame-messages-list messages-frame))
             (psi-emacs--dispatch-request
              "query_eql"
              (append target-params
                      `((:query . ,(psi-emacs--rehydrate-switch-extras-query))))
              (lambda (frame)
                (when (buffer-live-p buffer)
                  (with-current-buffer buffer
                    (when (eq state psi-emacs--state)
                      (psi-emacs--rehydrate-switch-state-from-query-frame state frame)
                      (psi-emacs--set-run-state state 'idle)
                      (psi-emacs--refresh-header-line)))))))))))))

(defun psi-emacs--switch-session-error-message (frame)
  "Return deterministic `/resume` switch failure text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (alist-get :message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to switch session: %s" details)
      "Unable to switch session.")))

(defun psi-emacs--handle-switch-session-response (state _session-path frame)
  "Handle `switch_session` callback FRAME for STATE.

Success path clears stale transcript/render state, then requests and replays
messages for deterministic rehydration.

Failure path appends deterministic assistant-visible feedback, sets
`last-error`, and does not run success-only side effects."
  (when (and state (eq state psi-emacs--state))
    (if (psi-emacs--rpc-frame-success-p frame)
        (let* ((data (alist-get :data frame nil nil #'equal))
               (target-session-id (or (alist-get :session-id data nil nil #'equal)
                                      (alist-get 'session-id data nil nil #'equal)
                                      (and psi-emacs--state
                                           (psi-emacs-state-session-id psi-emacs--state)))))
          ;; footer/updated + session/updated events arrive before the response frame
          ;; and correctly set projection-footer. Capture it before reset-transcript-state
          ;; clears it, then restore after so the footer survives the buffer wipe.
          ;;
          ;; Also pin the selected session id locally before follow-up reads so
          ;; explicit rehydration targets the switched session deterministically,
          ;; even if another session emits activity concurrently.
          (let ((saved-footer (and psi-emacs--state
                                   (psi-emacs-state-projection-footer psi-emacs--state))))
            (psi-emacs--reset-transcript-state)
            (when (and target-session-id psi-emacs--state)
              (setf (psi-emacs-state-session-id psi-emacs--state) target-session-id))
            (when (and saved-footer psi-emacs--state)
              (setf (psi-emacs-state-projection-footer psi-emacs--state) saved-footer)
              (when (fboundp 'psi-emacs--upsert-projection-block)
                (psi-emacs--upsert-projection-block))))
          ;; Only focus input — footer already correctly set from footer/updated event.
          (when (fboundp 'psi-emacs--focus-input-area)
            (psi-emacs--focus-input-area (current-buffer)))
          (psi-emacs--set-run-state state 'streaming)
          (psi-emacs--request-switch-rehydration state target-session-id))
      (let ((message (psi-emacs--switch-session-error-message frame)))
        (psi-emacs--append-assistant-message message)
        (psi-emacs--set-last-error state message)))))

(defun psi-emacs--request-switch-session (state session-path)
  "Dispatch `switch_session` for SESSION-PATH from STATE."
  (when (and state
             (stringp session-path)
             (not (string-empty-p session-path)))
    (let ((buffer (current-buffer)))
      (psi-emacs--dispatch-request
       "switch_session"
       `((:session-path . ,session-path))
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (psi-emacs--handle-switch-session-response state session-path frame)))))))))

(defun psi-emacs--handle-idle-resume-no-arg (state)
  "Handle `/resume` without explicit session path."
  (let ((buffer (current-buffer)))
    (psi-emacs--request-resume-session-list
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let* ((sessions (psi-emacs--resume-session-list-from-query-frame frame))
                    (candidates (psi-emacs--resume-session-candidates sessions))
                    (selected-path (psi-emacs--resume-select-session-path candidates)))
               (when selected-path
                 (psi-emacs--handle-idle-resume-explicit-path state selected-path))))))))))

(defun psi-emacs--handle-idle-resume-explicit-path (state session-path)
  "Handle `/resume <session-path>`."
  (psi-emacs--request-switch-session state session-path))

(defun psi-emacs--handle-idle-resume-command (state message)
  "Handle `/resume` MESSAGE by path (when provided) or interactive selector."
  (let ((session-path (psi-emacs--resume-args-from-message message)))
    (if session-path
        (psi-emacs--handle-idle-resume-explicit-path state session-path)
      (psi-emacs--handle-idle-resume-no-arg state))))

;;; ── /tree session picker ─────────────────────────────────────────────────

(defun psi-emacs--tree-slot-item-kind (slot)
  "Return normalized item kind string for tree SLOT."
  (let ((raw (or (psi-emacs--event-data-get slot '(:item/kind item/kind))
                 (psi-emacs--event-data-get slot '(:item-kind item-kind :itemKind itemKind))
                 "session")))
    (if (keywordp raw)
        (string-remove-prefix ":" (format "%s" raw))
      (format "%s" raw))))

(defun psi-emacs--tree-slot-parent-session-id (slot)
  "Return parent session id for tree SLOT, or nil."
  (let ((raw (or (psi-emacs--event-data-get slot '(:item/parent-id item/parent-id))
                 (psi-emacs--event-data-get slot '(:parent-session-id parent-session-id :parentSessionId parentSessionId)))))
    (cond
     ((and (vectorp raw) (= 2 (length raw))) (aref raw 1))
     ((and (listp raw) (= 2 (length raw))) (nth 1 raw))
     (t raw))))

(defun psi-emacs--tree-slot-session-id (slot)
  "Return session id for tree SLOT, or empty string."
  (or (psi-emacs--event-data-get slot '(:item/session-id item/session-id))
      (psi-emacs--event-data-get slot '(:id id :session-id session-id))
      ""))

(defun psi-emacs--tree-slot-entry-id (slot)
  "Return fork entry id for tree SLOT, or nil."
  (or (psi-emacs--event-data-get slot '(:item/entry-id item/entry-id))
      (psi-emacs--event-data-get slot '(:entry-id entry-id :entryId entryId))))

(defun psi-emacs--tree-slot-display-name (slot)
  "Return display name for tree SLOT, or nil."
  (or (psi-emacs--event-data-get slot '(:item/display-name item/display-name))
      (psi-emacs--event-data-get slot '(:display-name display-name
                                        :session-display-name session-display-name
                                        :name name
                                        :session-name session-name))))

(defun psi-emacs--tree-slot-label (slot item-kind id entry-id)
  "Return label base for tree SLOT."
  (cond
   ((listp slot)
    (psi-emacs--session-tree-line-label slot))
   ((equal item-kind "fork-point")
    (concat "⎇ " (or (psi-emacs--tree-slot-display-name slot) entry-id "(unknown fork point)")))
   (t (or id "(unknown)"))))

(defun psi-emacs--tree-slot-value (slot item-kind id entry-id)
  "Return completion payload value for tree SLOT."
  (let ((canonical-action (psi-emacs--event-data-get slot '(:item/action item/action))))
    (cond
     ((and canonical-action (listp canonical-action)) canonical-action)
     ((equal item-kind "fork-point")
      `((:action/kind . :fork-session)
        (:action/entry-id . ,entry-id)
        (:action/session-id . ,id)))
     (t id))))

(defun psi-emacs--tree-slot-runtime-state (slot)
  "Return canonical runtime-state label for tree SLOT, or nil."
  (psi-emacs--event-data-get slot '(:runtime-state runtime-state :item/runtime-state item/runtime-state)))

(defun psi-emacs--tree-runtime-suffix (slot item-kind is-active)
  "Return runtime/current suffix text for tree SLOT."
  (if (not (equal item-kind "session"))
      ""
    (concat
     (when is-active " ← current")
     (let ((runtime-state (psi-emacs--tree-slot-runtime-state slot)))
       (if (and (stringp runtime-state)
                (not (string-empty-p (string-trim runtime-state))))
           (concat " [" (string-trim runtime-state) "]")
         "")))))

(defun psi-emacs--tree-session-candidates (slots active-id)
  "Build completing-read candidates from backend-ordered SLOTS with ACTIVE-ID.

The backend owns `/tree` hierarchy and item ordering. This frontend function
only renders labels and preserves incoming order exactly.

Returns an alist of (label . value), where value is either a session-id string
or a canonical action map."
  (mapcar
   (lambda (slot)
     (let* ((item-kind (psi-emacs--tree-slot-item-kind slot))
            (id (psi-emacs--tree-slot-session-id slot))
            (entry-id (psi-emacs--tree-slot-entry-id slot))
            (parent-id (psi-emacs--tree-slot-parent-session-id slot))
            (is-active-raw (or (psi-emacs--event-data-get slot '(:item/is-active item/is-active))
                               (psi-emacs--event-data-get slot '(:is-active is-active))))
            (is-active (and (equal item-kind "session")
                            (or is-active-raw
                                (and id active-id (equal id active-id)))))
            (explicit-label (psi-emacs--event-data-get slot '(:label label)))
            (name (or explicit-label
                      (psi-emacs--tree-slot-label slot item-kind id entry-id)))
            (indent (if (or parent-id (equal item-kind "fork-point")) "  " ""))
            (suffix (if explicit-label
                        ""
                      (psi-emacs--tree-runtime-suffix slot item-kind is-active)))
            (label (concat indent name suffix))
            (value (psi-emacs--tree-slot-value slot item-kind id entry-id)))
       (cons label value)))
   (append slots nil)))

(defun psi-emacs--request-switch-session-by-id (state session-id)
  "Dispatch `switch_session` for SESSION-ID from STATE.

SESSION-ID identifies an in-process context session."
  (when (and state
             (stringp session-id)
             (not (string-empty-p session-id)))
    (let ((buffer (current-buffer)))
      (psi-emacs--dispatch-request
       "switch_session"
       `((:session-id . ,session-id))
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (psi-emacs--handle-switch-session-response state session-id frame)))))))))

(defun psi-emacs--tree-select-and-switch (state active-id slots)
  "Prompt from SLOTS (ACTIVE-ID default) and switch selected session or fork-point."
  (let ((candidates (psi-emacs--tree-session-candidates slots active-id)))
    (if (null candidates)
        (psi-emacs--append-assistant-message "No live sessions available.")
      (let* ((selected-label
              (psi-emacs--ordered-completing-read
               "Switch session: "
               candidates
               ;; default: active session label
               (car (rassoc active-id candidates))))
             (selected-value (cdr (assoc selected-label candidates))))
        (cond
         ((null selected-value)
          nil)
         ((and (stringp selected-value)
               (equal selected-value active-id))
          (psi-emacs--append-assistant-message
           (format "Already on session: %s" selected-label)))
         ((and (listp selected-value)
               (equal (alist-get :action/kind selected-value nil nil #'equal) :fork-session))
          (let ((entry-id (alist-get :action/entry-id selected-value nil nil #'equal)))
            (when (and (stringp entry-id) (not (string-empty-p entry-id)))
              (psi-emacs--dispatch-request "fork" `((:entry-id . ,entry-id))))))
         ((and (listp selected-value)
               (equal (alist-get :action/kind selected-value nil nil #'equal) :switch-session))
          (let ((sid (alist-get :action/session-id selected-value nil nil #'equal)))
            (when (and (stringp sid) (not (string-empty-p sid)))
              (psi-emacs--request-switch-session-by-id state sid))))
         (t
          (psi-emacs--request-switch-session-by-id state selected-value)))))))

(defun psi-emacs--handle-idle-tree-command (state)
  "Handle `/tree` command via backend selector flow.

Always route bare `/tree` through the backend `command` path so the picker can
include current-session fork points/messages in addition to the live session
snapshot. Direct `/tree <id>` still switches locally via `switch_session`."
  (let ((sent? (psi-emacs--dispatch-request
                "command"
                '((:text . "/tree")))))
    (when sent?
      (psi-emacs--set-run-state state 'streaming)
      (psi-emacs--reset-stream-watchdog state))))

(defun psi-emacs--default-handle-slash-command (state message)
  "Default slash handler.

Return non-nil when MESSAGE is handled and should not fall through to
normal prompt dispatch."
  (let* ((trimmed (string-trim (or message "")))
         (command (car (split-string trimmed "[ \t\n\r]+" t))))
    (pcase command
      ((or "/quit" "/exit")
       (psi-emacs--request-frontend-exit)
       t)
      ("/resume"
       (psi-emacs--handle-idle-resume-command state message)
       t)
      ("/tree"
       (let* ((trimmed* (string-trim (or message "")))
              (tail (string-trim (string-remove-prefix "/tree" trimmed*)))
              (session-id (when (and (stringp tail) (not (string-empty-p tail))) tail)))
         (if session-id
             (psi-emacs--request-switch-session-by-id state session-id)
           (psi-emacs--handle-idle-tree-command state)))
       t)
      ("/new"
       (psi-emacs--request-new-session state)
       t)
      ("/status"
       (psi-emacs--append-assistant-message
        (psi-emacs--status-diagnostics-string state))
       t)
      ("/worktree"
       (let ((sent? (psi-emacs--dispatch-request
                     "prompt"
                     `((:message . "/worktree")))))
         (when sent?
           (psi-emacs--set-run-state state 'streaming)
           (psi-emacs--reset-stream-watchdog state)))
       t)
      ((or "/jobs" "/job" "/cancel-job")
       (let ((sent? (psi-emacs--dispatch-request
                     "prompt"
                     `((:message . ,trimmed)))))
         (when sent?
           (psi-emacs--set-run-state state 'streaming)
           (psi-emacs--reset-stream-watchdog state)))
       t)
      ("/model"
       (psi-emacs--handle-idle-model-command state message)
       t)
      ("/thinking"
       (psi-emacs--handle-idle-thinking-command state message)
       t)
      ((or "/help" "/?")
       (psi-emacs--append-assistant-message
        (psi-emacs--slash-help-text))
       t)
      (_ nil))))

(defun psi-emacs--slash-command-candidate-p (message)
  "Return non-nil when MESSAGE is a slash command candidate."
  (let ((trimmed (string-trim (or message ""))))
    (and (not (string-empty-p trimmed))
         (string-prefix-p "/" trimmed))))

(defun psi-emacs--dispatch-compose-message (message &optional behavior)
  "Dispatch compose MESSAGE using slash-first routing.

Slash-prefixed input is always sent to backend `command` handling,
independent of frontend run-state. Non-slash input is sent via normal
`prompt` when idle, or `prompt_while_streaming` with BEHAVIOR when the
frontend is streaming.

Returns plist:
  :dispatched?  non-nil when dispatched remotely
  :local-only?  always nil in the backend-owned slash architecture."
  (let* ((message* (or message ""))
         (slash-candidate? (and psi-emacs--state
                                (psi-emacs--slash-command-candidate-p message*)))
         (blank-non-slash? (and (not slash-candidate?)
                                (string-empty-p (string-trim message*))))
         (streaming? (and psi-emacs--state
                          (memq (psi-emacs-state-run-state psi-emacs--state)
                                '(streaming interrupt_pending))))
         (sent? (cond
                 (blank-non-slash?
                  (let ((msg "Cannot send empty input."))
                    (psi-emacs--set-last-error psi-emacs--state msg)
                    (message "psi: %s" msg)
                    nil))
                 (slash-candidate?
                  (psi-emacs--dispatch-request "command" `((:text . ,message*))))
                 (streaming?
                  (psi-emacs--dispatch-request
                   "prompt_while_streaming"
                   `((:message . ,message*)
                     (:behavior . ,(or behavior "steer")))))
                 (t
                  (psi-emacs--dispatch-request "prompt" `((:message . ,message*)))))))
    (when sent?
      (psi-emacs--set-run-state psi-emacs--state 'streaming)
      (psi-emacs--reset-stream-watchdog psi-emacs--state))
    (list :dispatched? sent? :local-only? nil)))

(provide 'psi-session-commands)

;;; psi-session-commands.el ends here
