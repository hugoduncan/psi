;;; psi-streaming-render-optimization-test.el --- Streaming render optimization tests  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

(ert-deftest psi-assistant-streaming-incremental-delta-append-path-avoids-redraw-and-prefix-recreation ()
  ;; Incremental assistant delta chunks should append only the new suffix after
  ;; initial block creation.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraws nil)
          (prefixes nil)
          (property-texts nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-assistant-line))
          (orig-prefix (symbol-function 'psi-emacs--apply-prefix-overlay))
          (orig-props (symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-assistant-line)
                 (lambda (&rest args)
                   (push args redraws)
                   (apply orig-redraw args)))
                ((symbol-function 'psi-emacs--apply-prefix-overlay)
                 (lambda (&rest args)
                   (push args prefixes)
                   (apply orig-prefix args)))
                ((symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)
                 (lambda (start end)
                   (push (buffer-substring-no-properties start end) property-texts)
                   (funcall orig-props start end))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "lo")))))
        (should (equal "Hello"
                       (psi-emacs-state-assistant-in-progress psi-emacs--state)))
        (should (equal "ψ: Hello\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should-not redraws)
        (should (= 1 (length prefixes)))
        (should (equal '("lo" "Hel") property-texts))))))

(ert-deftest psi-assistant-streaming-cumulative-snapshot-suffix-append-path-avoids-redraw-and-prefix-recreation ()
  ;; Extending cumulative assistant snapshots should append only the suffix
  ;; that is not already rendered.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraws nil)
          (prefixes nil)
          (property-texts nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-assistant-line))
          (orig-prefix (symbol-function 'psi-emacs--apply-prefix-overlay))
          (orig-props (symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-assistant-line)
                 (lambda (&rest args)
                   (push args redraws)
                   (apply orig-redraw args)))
                ((symbol-function 'psi-emacs--apply-prefix-overlay)
                 (lambda (&rest args)
                   (push args prefixes)
                   (apply orig-prefix args)))
                ((symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)
                 (lambda (start end)
                   (push (buffer-substring-no-properties start end) property-texts)
                   (funcall orig-props start end))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hello!")))))
        (should (equal "Hello!"
                       (psi-emacs-state-assistant-in-progress psi-emacs--state)))
        (should (equal "ψ: Hello!\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should-not redraws)
        (should (= 1 (length prefixes)))
        (should (equal '("lo!" "Hel") property-texts))))))

(ert-deftest psi-assistant-streaming-divergent-merge-preservation-append-path-avoids-redraw-and-prefix-recreation ()
  ;; Divergent assistant payloads preserve the existing merge-as-delta contract,
  ;; then append only that effective suffix.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraws nil)
          (prefixes nil)
          (property-texts nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-assistant-line))
          (orig-prefix (symbol-function 'psi-emacs--apply-prefix-overlay))
          (orig-props (symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-assistant-line)
                 (lambda (&rest args)
                   (push args redraws)
                   (apply orig-redraw args)))
                ((symbol-function 'psi-emacs--apply-prefix-overlay)
                 (lambda (&rest args)
                   (push args prefixes)
                   (apply orig-prefix args)))
                ((symbol-function 'psi-emacs--apply-assistant-stream-verbatim-range)
                 (lambda (start end)
                   (push (buffer-substring-no-properties start end) property-texts)
                   (funcall orig-props start end))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hello")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Goodbye")))))
        (should (equal "HelloGoodbye"
                       (psi-emacs-state-assistant-in-progress psi-emacs--state)))
        (should (equal "ψ: HelloGoodbye\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should-not redraws)
        (should (= 1 (length prefixes)))
        (should (equal '("Goodbye" "Hello") property-texts))))))

(ert-deftest psi-assistant-streaming-tail-churn-uses-redraw-fallback ()
  ;; Tail-churn cumulative snapshots preserve existing replacement semantics and
  ;; use the explicit redraw fallback because the effective next text is not an
  ;; append of current rendered content.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraw-texts nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-assistant-line)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-assistant-line)
                 (lambda (range text stream-verbatim)
                   (push text redraw-texts)
                   (funcall orig-redraw range text stream-verbatim))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hello\n")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hello world")))))
        (should (equal "Hello world"
                       (psi-emacs-state-assistant-in-progress psi-emacs--state)))
        (should (equal "ψ: Hello world\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should (equal '("Hello world") redraw-texts))))))

(ert-deftest psi-thinking-streaming-append-path-avoids-redraw-and-prefix-recreation ()
  ;; Extending cumulative thinking snapshots should insert only the suffix after
  ;; initial block creation, with no full redraw or prefix overlay recreation.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraws nil)
          (prefixes nil)
          (suffixes nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-thinking-line))
          (orig-prefix (symbol-function 'psi-emacs--apply-prefix-overlay))
          (orig-append (symbol-function 'psi-emacs--append-thinking-line-suffix)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-thinking-line)
                 (lambda (&rest args)
                   (push args redraws)
                   (apply orig-redraw args)))
                ((symbol-function 'psi-emacs--apply-prefix-overlay)
                 (lambda (&rest args)
                   (push args prefixes)
                   (apply orig-prefix args)))
                ((symbol-function 'psi-emacs--append-thinking-line-suffix)
                 (lambda (range suffix)
                   (push suffix suffixes)
                   (funcall orig-append range suffix))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/thinking-delta") (:data . ((:text . "I")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/thinking-delta") (:data . ((:text . "I think")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/thinking-delta") (:data . ((:text . "I think more")))))
        (should (equal "I think more"
                       (psi-emacs-state-thinking-in-progress psi-emacs--state)))
        (should (equal "· I think more\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should-not redraws)
        (should (= 1 (length prefixes)))
        (should (equal '(" more" " think") suffixes))))))

(ert-deftest psi-thinking-streaming-divergent-snapshot-uses-redraw-fallback ()
  ;; Divergent thinking snapshots are cumulative replacements and must redraw
  ;; the single live thinking line instead of duplicating content.
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (let ((redraw-texts nil)
          (orig-redraw (symbol-function 'psi-emacs--redraw-thinking-line)))
      (cl-letf (((symbol-function 'psi-emacs--redraw-thinking-line)
                 (lambda (range text)
                   (push text redraw-texts)
                   (funcall orig-redraw range text))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/thinking-delta") (:data . ((:text . "plan")))))
        (psi-emacs--handle-rpc-event
         '((:event . "assistant/thinking-delta") (:data . ((:text . "new plan")))))
        (should (equal "new plan"
                       (psi-emacs-state-thinking-in-progress psi-emacs--state)))
        (should (equal "· new plan\n"
                       (buffer-substring-no-properties
                        (point-min)
                        (psi-emacs--input-separator-position))))
        (should (equal '("new plan") redraw-texts))))))

(provide 'psi-streaming-render-optimization-test)

;;; psi-streaming-render-optimization-test.el ends here
