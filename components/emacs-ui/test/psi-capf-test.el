;;; psi-capf-test.el --- CAPF tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)

(ert-deftest psi-capf-installed-in-psi-mode-buffer ()
  (with-temp-buffer
    (psi-emacs-mode)
    (should (memq #'psi-emacs-prompt-capf completion-at-point-functions))))

(ert-deftest psi-capf-slash-context-returns-command-candidates-with-category ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/re")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (category (plist-get (nthcdr 3 capf) :category))
           (cands (all-completions "/re" table)))
      (should capf)
      (should (eq category 'psi_prompt))
      (should (member "/resume" cands)))))

(ert-deftest psi-capf-slash-context-includes-background-job-commands ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/j")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/j" table)))
      (should capf)
      (should (member "/jobs" cands))
      (should (member "/job" cands)))))

(ert-deftest psi-capf-slash-context-includes-server-command-candidates ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/re")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/re" table)))
      (should capf)
      (should (member "/remember" cands))))
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/hi")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/hi" table)))
      (should capf)
      (should (member "/history" cands)))))

(ert-deftest psi-capf-slash-includes-extension-commands-from-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state :extension-command-names '("delegate" "/delegate-reload")))
    (insert "/de")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/de" table)))
      (should capf)
      (should (member "/delegate" cands))
      (should (member "/delegate-reload" cands)))))

(ert-deftest psi-capf-slash-includes-prompt-templates-from-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state
                 :prompt-templates '((( :name . "gh-issue-work-on")
                                      (:description . "Work on a GitHub issue")))))
    (insert "/gh")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/gh" table)))
      (should capf)
      (should (member "/gh-issue-work-on" cands)))))

(ert-deftest psi-capf-slash-dedupes-command-template-collision-by-command-name ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state
                 :prompt-templates '((( :name . "resume")
                                      (:description . "Shadow resume template")))))
    (insert "/re")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/re" table)))
      (should capf)
      (should (= 1 (length (seq-filter (lambda (cand) (equal cand "/resume")) cands)))))))

(ert-deftest psi-session-updated-applies-inline-slash-completion-state-when-changed ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (make-psi-emacs-state :session-id "s1" :prompt-templates nil :extension-command-names nil :slash-completion-token nil))
    (psi-emacs--handle-session-updated-event
     '((:session-id . "s1")
       (:phase . "idle")
       (:is-streaming . nil)
       (:is-compacting . nil)
       (:pending-message-count . 0)
       (:retry-attempt . 0)
       (:interrupt-pending . nil)
       (:extension-command-names . ["delegate"])
       (:prompt-templates . [((:name . "gh-issue-work-on")
                              (:description . "Work on a GitHub issue"))])))
    (should (equal '("delegate")
                   (psi-emacs-state-extension-command-names psi-emacs--state)))
    (should (equal "gh-issue-work-on"
                   (alist-get :name (car (psi-emacs-state-prompt-templates psi-emacs--state)) nil nil #'equal)))
    (should (equal '(:commands ("delegate")
                     :templates (("gh-issue-work-on" "Work on a GitHub issue")))
                   (psi-emacs-state-slash-completion-token psi-emacs--state)))))

(ert-deftest psi-session-updated-does-not-touch-slash-completion-state-when-unrelated ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state
                 :session-id "s1"
                 :extension-command-names '("delegate")
                 :prompt-templates '((( :name . "gh-issue-work-on")
                                      (:description . "Work on a GitHub issue")))
                 :slash-completion-token '(:commands ("delegate")
                                           :templates (("gh-issue-work-on" "Work on a GitHub issue")))))
    (let ((before-token (psi-emacs-state-slash-completion-token psi-emacs--state)))
      (psi-emacs--handle-session-updated-event
       '((:session-id . "s1")
         (:phase . "idle")
         (:is-streaming . nil)
         (:is-compacting . nil)
         (:pending-message-count . 1)
         (:retry-attempt . 0)
         (:interrupt-pending . nil)))
      (should (equal before-token
                     (psi-emacs-state-slash-completion-token psi-emacs--state)))
      (should (equal '("delegate")
                     (psi-emacs-state-extension-command-names psi-emacs--state))))))

(ert-deftest psi-capf-at-reference-context-returns-file-candidates-and-category ()
  (let* ((tmp (make-temp-file "psi-capf-ref-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name "README.md" tmp) nil 'silent)
          (make-directory (expand-file-name "src" tmp))
          (make-directory (expand-file-name ".git" tmp))
          (write-region "" nil (expand-file-name ".git/config" tmp) nil 'silent)
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let* ((capf (psi-emacs-prompt-capf))
                   (table (nth 2 capf))
                   (category (plist-get (nthcdr 3 capf) :category))
                   (cands (all-completions "@" table)))
              (should capf)
              (should (eq category 'psi_reference))
              (should (member "@README.md" cands))
              (should (member "@src/" cands))
              (should-not (seq-some (lambda (cand)
                                      (string-match-p "@\\.git" cand))
                                    cands)))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-falls-through-outside-slash-or-at-context ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "hello")
    (should-not (psi-emacs-prompt-capf))))

(ert-deftest psi-capf-slash-and-reference-provide-affixation-functions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/h")
    (let* ((slash-capf (psi-emacs-prompt-capf))
           (slash-affix (plist-get (nthcdr 3 slash-capf) :affixation-function)))
      (should (functionp slash-affix)))
    (let ((inhibit-read-only t))
      (erase-buffer))
    (insert "@")
    (let* ((ref-capf (psi-emacs-prompt-capf))
           (ref-affix (plist-get (nthcdr 3 ref-capf) :affixation-function))
           (ref-exit (plist-get (nthcdr 3 ref-capf) :exit-function)))
      (should (functionp ref-affix))
      (should (functionp ref-exit)))))

(ert-deftest psi-capf-reference-candidates-include-project-root-when-distinct ()
  (let* ((project-root (make-temp-file "psi-capf-proj-" t))
         (nested (expand-file-name "sub/dir" project-root))
         (default-directory (file-name-as-directory nested)))
    (unwind-protect
        (progn
          (make-directory nested t)
          (write-region "" nil (expand-file-name "root-only.md" project-root) nil 'silent)
          (cl-letf (((symbol-function 'project-current)
                     (lambda (&rest _)
                       'psi-test-proj))
                    ((symbol-function 'project-root)
                     (lambda (_proj)
                       project-root)))
            (with-temp-buffer
              (psi-emacs-mode)
              (insert "@root")
              (let* ((capf (psi-emacs-prompt-capf))
                     (table (nth 2 capf))
                     (cands (all-completions "@root" table)))
                (should (member "@root-only.md" cands))))))
      (when (file-directory-p project-root)
        (delete-directory project-root t)))))

(ert-deftest psi-capf-reference-exit-adds-trailing-space-for-files ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "@README.md")
    (goto-char (point-max))
    (psi-emacs--reference-exit "@README.md" 'finished)
    (should (string-suffix-p " " (buffer-string)))))

(ert-deftest psi-capf-reference-hidden-policy-respects-defcustom ()
  (let* ((tmp (make-temp-file "psi-capf-hidden-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name ".env" tmp) nil 'silent)
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let ((psi-emacs-reference-include-hidden nil)
                  (capf (psi-emacs-prompt-capf)))
              (should-not (member "@.env" (all-completions "@" (nth 2 capf))))))
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let ((psi-emacs-reference-include-hidden t)
                  (capf (psi-emacs-prompt-capf)))
              (should (member "@.env" (all-completions "@" (nth 2 capf)))))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-reference-match-style-prefix-vs-substring ()
  (let* ((tmp (make-temp-file "psi-capf-match-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name "alpha.txt" tmp) nil 'silent)
          (let ((psi-emacs-reference-match-style 'substring))
            (should (member "@alpha.txt"
                            (psi-emacs--reference-candidates "@ha"))))
          (let ((psi-emacs-reference-match-style 'prefix))
            (should-not (member "@alpha.txt"
                                (psi-emacs--reference-candidates "@ha")))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-reference-candidate-limit-respects-defcustom ()
  (let* ((tmp (make-temp-file "psi-capf-limit-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (dotimes (i 4)
            (write-region "" nil (expand-file-name (format "file-%d.txt" i) tmp) nil 'silent))
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let* ((psi-emacs-reference-max-candidates 2)
                   (capf (psi-emacs-prompt-capf))
                   (cands (all-completions "@" (nth 2 capf))))
              (should (= 2 (length cands))))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))


(provide 'psi-capf-test)

;;; psi-capf-test.el ends here
