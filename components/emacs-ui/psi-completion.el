;;; psi-completion.el --- CAPF completion for psi compose input  -*- lexical-binding: t; -*-

;;; Commentary:
;; Standard Emacs completion-at-point integration for slash commands and
;; @file references in psi compose input.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'seq)
(require 'project nil t)
(require 'psi-globals)

(defgroup psi-emacs-completion nil
  "Completion settings for psi Emacs frontend."
  :group 'psi-emacs)

(defcustom psi-emacs-slash-command-specs
  '(("/quit" . "Exit this psi buffer")
    ("/exit" . "Exit this psi buffer")
    ("/resume" . "Resume a prior session")
    ("/tree" . "Switch between live sessions")
    ("/new" . "Start a fresh backend session")
    ("/status" . "Show frontend diagnostics")
    ("/worktree" . "Show git worktree context")
    ("/jobs" . "List background jobs")
    ("/job" . "Inspect a background job")
    ("/cancel-job" . "Request background job cancellation")
    ("/model" . "Open model selector or set directly")
    ("/thinking" . "Open thinking selector or set directly")
    ("/help" . "Show help")
    ("/?" . "Show help")
    ("/history" . "Show message history")
    ("/prompts" . "List prompt templates")
    ("/skills" . "List loaded skills")
    ("/login" . "Login with an OAuth provider")
    ("/logout" . "Logout from an OAuth provider")
    ("/remember" . "Capture a memory note for future ψ")
    ("/skill:" . "Invoke a skill (append skill name)"))
  "Slash command completion candidates for `psi-emacs-prompt-capf'."
  :type '(repeat (cons string string))
  :group 'psi-emacs-completion)

(defcustom psi-emacs-slash-max-candidates 50
  "Maximum slash command candidates returned by CAPF."
  :type 'integer
  :group 'psi-emacs-completion)

(defcustom psi-emacs-reference-max-candidates 200
  "Maximum @reference candidates returned by CAPF."
  :type 'integer
  :group 'psi-emacs-completion)

(defcustom psi-emacs-reference-match-style 'substring
  "Matching style for @reference candidates.

- `substring` => case-insensitive containment
- `prefix`    => case-insensitive prefix match"
  :type '(choice (const :tag "Substring" substring)
                 (const :tag "Prefix" prefix))
  :group 'psi-emacs-completion)

(defcustom psi-emacs-reference-include-hidden t
  "When non-nil, include hidden files/directories in @reference completion.

`.git` paths remain excluded by `psi-emacs-reference-excluded-path-prefixes`."
  :type 'boolean
  :group 'psi-emacs-completion)

(defcustom psi-emacs-reference-excluded-path-prefixes '(".git" ".git/")
  "Path prefixes excluded from @reference completion candidates."
  :type '(repeat string)
  :group 'psi-emacs-completion)

(defun psi-emacs--completion-token-bounds ()
  "Return token bounds at point as (START . END), or nil.

A token is delimited by whitespace/newlines."
  (let* ((end (point))
         (start (save-excursion
                  (skip-chars-backward "^ \t\n\r")
                  (point))))
    (when (< start end)
      (cons start end))))

(defun psi-emacs--slash-command-context-p (token)
  "Return non-nil when TOKEN is a slash-command completion context."
  (and (stringp token)
       (string-prefix-p "/" token)))

(defun psi-emacs--file-reference-context-p (token)
  "Return non-nil when TOKEN is an @file-reference completion context."
  (and (stringp token)
       (string-prefix-p "@" token)))

(declare-function psi-emacs--state-prompt-template-specs "psi-session-commands")

(defun psi-emacs--state-slash-command-specs ()
  "Return merged built-in + backend extension + prompt-template slash command specs."
  (let* ((base psi-emacs-slash-command-specs)
         (ext-names (and psi-emacs--state
                         (psi-emacs-state-extension-command-names psi-emacs--state)))
         (ext-specs
          (mapcar (lambda (name)
                    (let ((cmd (string-trim (format "%s" (or name "")))))
                      (cons (if (string-prefix-p "/" cmd) cmd (concat "/" cmd))
                            "Extension command")))
                  (or ext-names [])))
         (template-specs (delq nil (and (fboundp 'psi-emacs--state-prompt-template-specs)
                                        (psi-emacs--state-prompt-template-specs)))))
    (seq-uniq (append base ext-specs template-specs)
              (lambda (a b) (equal (car a) (car b))))))

(defun psi-emacs--slash-annotation (candidate)
  "Return annotation text for slash CANDIDATE."
  (let ((desc (cdr (assoc candidate (psi-emacs--state-slash-command-specs)))))
    (when (and (stringp desc) (not (string-empty-p desc)))
      (format "  %s" desc))))

(defun psi-emacs--candidate-excluded-p (candidate)
  "Return non-nil when CANDIDATE should be excluded by path policy."
  (let* ((path (string-remove-prefix "@" (or candidate "")))
         (prefixes psi-emacs-reference-excluded-path-prefixes)
         (excluded-by-prefix
          (seq-some
           (lambda (prefix)
             (let ((trimmed (string-trim-right (or prefix "") "/+")))
               (and (not (string-empty-p trimmed))
                    (string-match-p (format "\\(?:^\\|/\\)%s\\(?:/\\|$\\)"
                                             (regexp-quote trimmed))
                                    path))))
           prefixes)))
    (or excluded-by-prefix
        (and (not psi-emacs-reference-include-hidden)
             (string-match-p "\\(?:^\\|/\\)\\.[^/]+" path)))))

(defun psi-emacs--fuzzy-match-p (needle haystack)
  "Return non-nil when NEEDLE matches HAYSTACK by configured style."
  (let* ((n (downcase (or needle "")))
         (h (downcase (or haystack ""))))
    (pcase psi-emacs-reference-match-style
      ('prefix (string-prefix-p n h))
      (_ (string-match-p (regexp-quote n) h)))))

(defun psi-emacs--directory-entries (dir)
  "Return stable directory entries in DIR, excluding . and ..."
  (when (file-directory-p dir)
    (sort
     (cl-remove-if (lambda (entry) (member entry '("." "..")))
                   (directory-files dir nil nil t))
     #'string<)))

(defun psi-emacs--completion-project-root-directory ()
  "Return canonical project root directory for completion, or nil."
  (let* ((base (and (stringp default-directory)
                    (not (string-empty-p default-directory))
                    (expand-file-name default-directory)))
         (project-root
          (when (and base (fboundp 'project-current))
            (let ((proj (ignore-errors (project-current nil base))))
              (cond
               ((and proj (fboundp 'project-root))
                (ignore-errors (project-root proj)))
               ((and proj (fboundp 'project-roots))
                (car (ignore-errors (project-roots proj))))
               (t nil))))))
    (when (and (stringp project-root)
               (not (string-empty-p project-root)))
      (file-name-as-directory (expand-file-name project-root)))))

(defun psi-emacs--reference-search-dirs (raw-prefix)
  "Return ordered search dirs for RAW-PREFIX.

Searches current `default-directory` first, then project root when distinct.
When RAW-PREFIX has explicit dir part that does not exist in current dir,
project-root resolution is still attempted."
  (let* ((cwd (and (stringp default-directory)
                   (file-name-as-directory (expand-file-name default-directory))))
         (project-root (psi-emacs--completion-project-root-directory))
         (dirpart (file-name-directory raw-prefix))
         (target-rel (or dirpart ""))
         (cwd-target (and cwd (expand-file-name target-rel cwd)))
         (project-target (and project-root (expand-file-name target-rel project-root)))
         (dirs nil))
    (when cwd-target (push cwd-target dirs))
    (when (and project-target
               (not (equal (file-name-as-directory project-target)
                           (and cwd-target (file-name-as-directory cwd-target)))))
      (push project-target dirs))
    (nreverse (cl-remove-duplicates dirs :test #'equal))))

(defun psi-emacs--reference-candidates (token)
  "Return @reference completion candidates for TOKEN.

TOKEN includes the leading @ marker."
  (let* ((raw-prefix (string-remove-prefix "@" (or token "")))
         (dirpart (file-name-directory raw-prefix))
         (basename (file-name-nondirectory raw-prefix))
         (display-dir (or dirpart ""))
         (search-dirs (psi-emacs--reference-search-dirs raw-prefix))
         (seen (make-hash-table :test #'equal))
         (candidates nil)
         (limit (max 1 psi-emacs-reference-max-candidates)))
    (catch 'done
      (dolist (search-dir search-dirs)
        (dolist (entry (psi-emacs--directory-entries search-dir))
          (let* ((abs (expand-file-name entry search-dir))
                 (rel (concat display-dir entry (when (file-directory-p abs) "/")))
                 (cand (concat "@" rel)))
            (when (and (not (gethash cand seen))
                       (not (psi-emacs--candidate-excluded-p cand))
                       (psi-emacs--fuzzy-match-p basename entry))
              (puthash cand t seen)
              (push cand candidates)
              (when (>= (length candidates) limit)
                (throw 'done nil)))))))
    (nreverse candidates)))

(defun psi-emacs--reference-kind (candidate)
  "Return kind symbol for @ CANDIDATE."
  (if (string-suffix-p "/" (string-remove-prefix "@" candidate))
      'dir
    'file))

(defun psi-emacs--reference-annotation (candidate)
  "Return annotation text for @ CANDIDATE."
  (format "  %s" (symbol-name (psi-emacs--reference-kind candidate))))

(defun psi-emacs--slash-affixation (candidates)
  "Return affixation triples for slash CANDIDATES."
  (mapcar (lambda (candidate)
            (list candidate
                  ""
                  (or (psi-emacs--slash-annotation candidate) "")))
          candidates))

(defun psi-emacs--reference-affixation (candidates)
  "Return affixation triples for @reference CANDIDATES."
  (mapcar (lambda (candidate)
            (list candidate
                  ""
                  (psi-emacs--reference-annotation candidate)))
          candidates))

(defun psi-emacs--reference-exit (candidate status)
  "Finalize accepted @reference CANDIDATE for completion STATUS.

Adds a trailing space when a non-directory reference is accepted."
  (when (and (eq status 'finished)
             (stringp candidate)
             (not (eq (psi-emacs--reference-kind candidate) 'dir)))
    (let ((next (char-after)))
      (unless (and next (memq next '(?\s ?\t ?\n ?\r)))
        (insert " ")))))

(defun psi-emacs-prompt-capf ()
  "Return completion data for slash commands and @ references.

Returns nil outside recognized contexts to allow CAPF fallthrough."
  (let* ((bounds (psi-emacs--completion-token-bounds))
         (start (car-safe bounds))
         (end (cdr-safe bounds))
         (token (and start end (buffer-substring-no-properties start end))))
    (cond
     ((and token (psi-emacs--slash-command-context-p token))
      (list start end
            (completion-table-dynamic
             (lambda (_prefix)
               (let ((specs (psi-emacs--state-slash-command-specs)))
                 (cl-subseq (mapcar #'car specs)
                            0
                            (min (length specs)
                                 (max 1 psi-emacs-slash-max-candidates))))))
            :category 'psi_prompt
            :annotation-function #'psi-emacs--slash-annotation
            :affixation-function #'psi-emacs--slash-affixation
            :exclusive 'no))
     ((and token (psi-emacs--file-reference-context-p token))
      (list start end
            (completion-table-dynamic #'psi-emacs--reference-candidates)
            :category 'psi_reference
            :annotation-function #'psi-emacs--reference-annotation
            :affixation-function #'psi-emacs--reference-affixation
            :exit-function #'psi-emacs--reference-exit
            :exclusive 'no))
     (t nil))))

(defun psi-emacs--install-prompt-capf ()
  "Install `psi-emacs-prompt-capf' in current buffer CAPF list."
  (setq-local completion-at-point-functions
              (cons #'psi-emacs-prompt-capf
                    (remove #'psi-emacs-prompt-capf completion-at-point-functions))))

(provide 'psi-completion)

;;; psi-completion.el ends here
