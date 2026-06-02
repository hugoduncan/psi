;;; psi-widget-projection-test.el --- Tests for psi-widget-projection  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-widget-renderer)
(require 'psi-widget-projection)

;;; Helpers

(defun pwpt--make-spec (id &optional root-node)
  "Build a minimal widget spec alist."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ,(or root-node
                  `((:type . text) (:content . ,id))))))

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

(defun pwpt--make-collapsible-spec (id key &optional initially-expanded)
  "Build a spec with a collapsible root node."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ((:type . collapsible)
              (:title . "Section")
              (:key . ,key)
              (:initially-expanded . ,(or initially-expanded :false))
              (:children . (((:type . text) (:content . "inner"))))))))

(defmacro pwpt--with-state (&rest body)
  "Run BODY with a fresh psi-emacs-state bound as psi-emacs--state."
  `(let ((psi-emacs--state (psi-emacs--initialize-state nil)))
     ,@body))

(defun pwpt--capture-query-sends (thunk)
  "Run THUNK, capture (op params) pairs sent via send-request-function."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _cb)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

(defun pwpt--capture-query-sends-with-callback (thunk)
  "Run THUNK, capture (op params callback) triples."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional cb)
                 (push (list op params cb) calls))))
      (funcall thunk))
    (nreverse calls)))

;;; ─── psi-widget-projection-request-specs ────────────────────────────────────

(ert-deftest pwpt-request-specs-sends-query-eql ()
  (pwpt--with-state
   (let ((calls (pwpt--capture-query-sends
                 #'psi-widget-projection-request-specs)))
     (should (= 1 (length calls)))
     (should (equal "query_eql" (car (car calls))))
     (let ((params (cadr (car calls))))
       (should (string-match-p "psi.ui/widget-specs" (cdr (assq :query params))))))))

(ert-deftest pwpt-request-specs-noop-when-no-state ()
  (let ((psi-emacs--state nil))
    (let ((calls (pwpt--capture-query-sends
                  #'psi-widget-projection-request-specs)))
      (should (= 0 (length calls))))))

(ert-deftest pwpt-request-specs-noop-when-no-send-function ()
  (pwpt--with-state
   (let ((psi-emacs--send-request-function nil))
     ;; Should not error
     (should-not (condition-case err
                     (progn (psi-widget-projection-request-specs) nil)
                   (error err))))))

;;; ─── lstate management ───────────────────────────────────────────────────────

(ert-deftest pwpt-sync-lstates-inits-new-specs ()
  (pwpt--with-state
   (let ((spec (pwpt--make-spec "w1")))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state)))
       (should (hash-table-p lstates))
       (should (not (null (gethash "ext/w1" lstates))))))))

(ert-deftest pwpt-sync-lstates-preserves-existing-lstate ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-collapsible-spec "w1" "s1" nil))
          (_      (psi-widget-projection--sync-lstates (list spec)))
          (lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state))
          ;; Manually modify lstate
          (lstate (gethash "ext/w1" lstates))
          (lstate (psi-widget-renderer-lstate-set-collapsed lstate "s1" nil)))
     (puthash "ext/w1" lstate lstates)
     ;; Re-sync — existing lstate should be preserved
     (psi-widget-projection--sync-lstates (list spec))
     (let* ((lstates2 (psi-emacs-state-projection-widget-lstates psi-emacs--state))
            (lstate2  (gethash "ext/w1" lstates2)))
       (should-not (psi-widget-renderer--collapsed-p lstate2 "s1"))))))

(ert-deftest pwpt-sync-lstates-removes-stale-specs ()
  (pwpt--with-state
   (let ((spec1 (pwpt--make-spec "w1"))
         (spec2 (pwpt--make-spec "w2")))
     (psi-widget-projection--sync-lstates (list spec1 spec2))
     ;; Remove spec2
     (psi-widget-projection--sync-lstates (list spec1))
     (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state)))
       (should     (gethash "ext/w1" lstates))
       (should-not (gethash "ext/w2" lstates))))))

(ert-deftest pwpt-get-lstate-returns-fresh-when-missing ()
  (pwpt--with-state
   (let ((lstate (psi-widget-projection--get-lstate "ext" "unknown")))
     (should (listp lstate))
     (should (hash-table-p (plist-get lstate :collapsed-keys))))))

(ert-deftest pwpt-set-lstate-stores-and-retrieves ()
  (pwpt--with-state
   (let* ((lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-collapsed lstate "k1" t)))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (let ((retrieved (psi-widget-projection--get-lstate "ext" "w1")))
       (should (psi-widget-renderer--collapsed-p retrieved "k1"))))))

;;; ─── psi-widget-projection-render-specs ─────────────────────────────────────

(ert-deftest pwpt-render-specs-returns-empty-when-no-specs ()
  (pwpt--with-state
   (should (equal "" (psi-widget-projection-render-specs psi-emacs--state)))))

(ert-deftest pwpt-render-specs-renders-registered-spec ()
  (pwpt--with-state
   (let ((spec (pwpt--make-spec "w1" '((:type . text) (:content . "hello from spec")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((result (psi-widget-projection-render-specs psi-emacs--state)))
       (should (string-match-p "hello from spec" result))))))

(ert-deftest pwpt-render-specs-renders-multiple-specs-separated ()
  (pwpt--with-state
   (let ((spec1 (pwpt--make-spec "w1" '((:type . text) (:content . "first"))))
         (spec2 (pwpt--make-spec "w2" '((:type . text) (:content . "second")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
           (list spec1 spec2))
     (psi-widget-projection--sync-lstates (list spec1 spec2))
     (let ((result (psi-widget-projection-render-specs psi-emacs--state)))
       (should (string-match-p "first" result))
       (should (string-match-p "second" result))))))

;;; ─── Collapsible toggle handler ──────────────────────────────────────────────

(ert-deftest pwpt-collapsible-toggle-flips-collapsed-state ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-collapsible-spec "w1" "s1" nil)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     ;; Initially collapsed
     (should (psi-widget-renderer--collapsed-p
              (psi-widget-projection--get-lstate "ext" "w1") "s1"))
     ;; Toggle — should expand (no upsert needed, just lstate check)
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))
     (should-not (psi-widget-renderer--collapsed-p
                  (psi-widget-projection--get-lstate "ext" "w1") "s1"))
     ;; Toggle again — should collapse
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))
     (should (psi-widget-renderer--collapsed-p
              (psi-widget-projection--get-lstate "ext" "w1") "s1")))))

(ert-deftest pwpt-collapsible-toggle-does-not-send-rpc ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-collapsible-spec "w1" "s1" nil)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))))))
       (should (= 0 (length calls)))))))

;;; ─── Button activation handler ───────────────────────────────────────────────

(ert-deftest pwpt-button-activate-marks-in-flight ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (pwpt--capture-query-sends
      (lambda ()
        (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
          (psi-widget-projection--on-button-activate "ext/w1" "b1"))))
     (should (psi-widget-renderer--in-flight-p
              (psi-widget-projection--get-lstate "ext" "w1") "b1")))))

(ert-deftest pwpt-button-activate-sends-query-eql-mutation ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 1 (length calls)))
       (should (equal "query_eql" (car (car calls))))
       (let ((query (cdr (assq :query (cadr (car calls))))))
         (should (string-match-p "ext/do-thing" query)))))))

(ert-deftest pwpt-button-activate-noop-when-already-in-flight ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
          (lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 0 (length calls)))))))

(ert-deftest pwpt-button-activate-response-clears-in-flight ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((captured-cb nil))
       (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                  (lambda (_state _op _params &optional cb)
                    (setq captured-cb cb)))
                 ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
         (psi-widget-projection--on-button-activate "ext/w1" "b1"))
       ;; In-flight now
       (should (psi-widget-renderer--in-flight-p
                (psi-widget-projection--get-lstate "ext" "w1") "b1"))
       ;; Simulate response
       (when captured-cb
         (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
           (funcall captured-cb '((:data . ((:ok . t)))))))
       ;; In-flight cleared
       (should-not (psi-widget-renderer--in-flight-p
                    (psi-widget-projection--get-lstate "ext" "w1") "b1"))))))

(ert-deftest pwpt-button-activate-noop-when-disabled ()
  (pwpt--with-state
   (let* ((spec `((:id . "w1") (:extension-id . "ext")
                  (:spec . ((:type . button) (:label . "Go") (:key . "b1")
                            (:disabled . t)
                            (:mutation . ((:name . ext/do-thing))))))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 0 (length calls)))))))

;;; ─── Widget ID parsing ───────────────────────────────────────────────────────

(ert-deftest pwpt-parse-widget-id-splits-correctly ()
  (should (equal '("ext" . "w1")
                 (psi-widget-projection--parse-widget-id "ext/w1")))
  (should (equal '("my-ext" . "delegate-run")
                 (psi-widget-projection--parse-widget-id "my-ext/delegate-run"))))

(ert-deftest pwpt-parse-widget-id-returns-nil-without-slash ()
  (should (null (psi-widget-projection--parse-widget-id "nowidgetid")))
  (should (null (psi-widget-projection--parse-widget-id nil))))

;;; ─── EDN encoding ────────────────────────────────────────────────────────────

(ert-deftest pwpt-edn-encode-primitives ()
  (should (equal "nil"    (psi-widget-projection--edn-encode nil)))
  (should (equal "true"   (psi-widget-projection--edn-encode t)))
  (should (equal "false"  (psi-widget-projection--edn-encode :false)))
  (should (equal "42"     (psi-widget-projection--edn-encode 42)))
  (should (equal ":foo"   (psi-widget-projection--edn-encode :foo)))
  (should (equal "foo"    (psi-widget-projection--edn-encode 'foo))))

(ert-deftest pwpt-edn-encode-string ()
  (should (equal "\"hello\"" (psi-widget-projection--edn-encode "hello"))))

(ert-deftest pwpt-edn-encode-alist-as-map ()
  (let ((result (psi-widget-projection--edn-encode '((:a . 1) (:b . 2)))))
    (should (string-match-p ":a 1" result))
    (should (string-match-p ":b 2" result))
    (should (string-prefix-p "{" result))
    (should (string-suffix-p "}" result))))

(ert-deftest pwpt-edn-encode-vector ()
  (should (equal "[1 2 3]"
                 (psi-widget-projection--edn-encode [1 2 3]))))

;;; ─── Node lookup ─────────────────────────────────────────────────────────────

(ert-deftest pwpt-find-node-by-key-finds-root ()
  (let ((node '((:type . button) (:key . "b1") (:label . "Go"))))
    (should (equal node
                   (psi-widget-projection--find-node-by-key node "b1")))))

(ert-deftest pwpt-find-node-by-key-finds-nested ()
  (let* ((child '((:type . button) (:key . "b1") (:label . "Go")))
         (root  `((:type . vstack) (:children . (,child)))))
    (should (equal child
                   (psi-widget-projection--find-node-by-key root "b1")))))

(ert-deftest pwpt-find-node-by-key-returns-nil-when-missing ()
  (let ((node '((:type . text) (:key . "t1") (:content . "x"))))
    (should (null (psi-widget-projection--find-node-by-key node "missing")))))

;;; ─── Per-spec query data ─────────────────────────────────────────────────────

(defun pwpt--make-spec-with-query (id query-vec &optional root-node)
  "Build a spec alist with :query set to QUERY-VEC."
  `((:id . ,id)
    (:extension-id . "ext")
    (:query . ,query-vec)
    (:spec . ,(or root-node `((:type . text) (:content . ,id))))))

(ert-deftest pwpt-spec-key-formats-correctly ()
  (let ((spec (pwpt--make-spec "w1")))
    (should (equal "ext/w1"
                   (psi-widget-projection--spec-key spec)))))

(ert-deftest pwpt-spec-query-string-nil-when-no-query ()
  (let ((spec (pwpt--make-spec "w1")))
    (should (null (psi-widget-projection--spec-query-string spec)))))

(ert-deftest pwpt-spec-query-string-nil-when-empty-query ()
  (let ((spec (pwpt--make-spec-with-query "w1" [])))
    (should (null (psi-widget-projection--spec-query-string spec)))))

(ert-deftest pwpt-spec-query-string-formats-keywords ()
  (let* ((spec   (pwpt--make-spec-with-query "w1" [:psi.workflow/definitions]))
         (result (psi-widget-projection--spec-query-string spec)))
    (should (stringp result))
    (should (string-prefix-p "[" result))
    (should (string-suffix-p "]" result))
    (should (string-match-p "psi.workflow/definitions" result))))

(ert-deftest pwpt-spec-query-string-formats-multiple-keywords ()
  (let* ((spec   (pwpt--make-spec-with-query "w1" [:psi.workflow/definitions :psi.workflow/runs]))
         (result (psi-widget-projection--spec-query-string spec)))
    (should (string-match-p "psi.workflow/definitions" result))
    (should (string-match-p "psi.workflow/runs" result))))

(ert-deftest pwpt-spec-query-string-formats-join-map ()
  "A join {:psi.extension.workflow/detail [...]} encodes as EDN map."
  (let* ((join  `((:psi.extension.workflow/detail
                   . [,:psi.extension.workflow/id
                      ,:psi.extension.workflow/phase])))
         (spec  (pwpt--make-spec-with-query "w1" (vector join)))
         (result (psi-widget-projection--spec-query-string spec)))
    (should (string-match-p "psi.extension.workflow/detail" result))
    (should (string-match-p "{" result))))

(ert-deftest pwpt-fetch-spec-data-passes-entity-when-present ()
  "Spec with :entity sends entity param alongside query."
  (pwpt--with-state
   (let* ((spec `((:id . "w1") (:extension-id . "ext")
                  (:query . [:psi.extension.workflow/detail])
                  (:entity . ((:psi.extension/path . "/ext") (:psi.extension.workflow/id . 1)))
                  (:spec . ((:type . text) (:content . "x"))))))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (let ((calls (pwpt--capture-query-sends
                     (lambda () (psi-widget-projection--fetch-spec-data (list spec))))))
         (should (= 1 (length calls)))
         (let ((params (cadr (car calls))))
           (should (assq :entity params))))))))

(ert-deftest pwpt-get-set-spec-data-roundtrip ()
  (pwpt--with-state
   (let ((data '((:psi.workflow/run-count . 3))))
     (psi-widget-projection--set-spec-data "ext/w1" data)
     (should (equal data (psi-widget-projection--get-spec-data "ext/w1"))))))

(ert-deftest pwpt-get-spec-data-nil-when-absent ()
  (pwpt--with-state
   (should (null (psi-widget-projection--get-spec-data "ext/missing")))))

(ert-deftest pwpt-fetch-spec-data-noop-when-no-query ()
  "Spec with no :query fires no query_eql, renders immediately."
  (pwpt--with-state
   (let* ((spec  (pwpt--make-spec "w1"))
          (render-called nil))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                (lambda () (setq render-called t))))
       (let ((calls (pwpt--capture-query-sends
                     (lambda () (psi-widget-projection--fetch-spec-data (list spec))))))
         (should (= 0 (length calls)))
         (should render-called))))))

(ert-deftest pwpt-fetch-spec-data-sends-query-for-spec-with-query ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec-with-query "w1" [:psi.workflow/definitions])))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (let ((calls (pwpt--capture-query-sends
                     (lambda () (psi-widget-projection--fetch-spec-data (list spec))))))
         (should (= 1 (length calls)))
         (should (equal "query_eql" (car (car calls))))
         (let ((query (cdr (assq :query (cadr (car calls))))))
           (should (string-match-p "psi.workflow/definitions" query))))))))

(ert-deftest pwpt-fetch-spec-data-stores-result-and-rerenders ()
  (pwpt--with-state
   (let* ((spec         (pwpt--make-spec-with-query "w1" [:psi.workflow/run-count]))
          (render-count 0)
          (captured-cb  nil))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                (lambda (_state _op _params &optional cb) (setq captured-cb cb)))
               ((symbol-function 'psi-emacs--upsert-projection-block)
                (lambda () (cl-incf render-count))))
       (psi-widget-projection--fetch-spec-data (list spec)))
     ;; Simulate response
     (when captured-cb
       (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                  (lambda () (cl-incf render-count))))
         (funcall captured-cb
                  '((:data . ((:result . ((:psi.workflow/run-count . 5)))))))))
     (should (equal '((:psi.workflow/run-count . 5))
                    (psi-widget-projection--get-spec-data "ext/w1")))
     (should (> render-count 0)))))

(ert-deftest pwpt-fetch-spec-data-multiple-specs-fires-per-spec ()
  "Two specs with queries → two query_eql sends."
  (pwpt--with-state
   (let* ((spec1 (pwpt--make-spec-with-query "w1" [:psi.workflow/definitions]))
          (spec2 (pwpt--make-spec-with-query "w2" [:psi.workflow/run-count])))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (let ((calls (pwpt--capture-query-sends
                     (lambda ()
                       (psi-widget-projection--fetch-spec-data (list spec1 spec2))))))
         (should (= 2 (length calls))))))))

(ert-deftest pwpt-render-specs-passes-data-to-renderer ()
  "render-specs passes stored data for content-path resolution."
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec-with-query
                 "w1" [:psi.workflow/run-count]
                 '((:type . text) (:content-path . (:psi.workflow/run-count))))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-spec-data "ext/w1" '((:psi.workflow/run-count . 42)))
     (let ((result (substring-no-properties
                    (psi-widget-projection-render-specs psi-emacs--state))))
       (should (string-match-p "42" result))))))

(ert-deftest pwpt-render-specs-nil-data-when-no-query ()
  "render-specs passes nil data when no query result stored."
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec "w1" '((:type . text) (:content . "static")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((result (substring-no-properties
                    (psi-widget-projection-render-specs psi-emacs--state))))
       (should (string-match-p "static" result))))))

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
originating buffer's store, not the current buffer's."
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
          ;; Arm + dispatch from the origin buffer.
          (with-current-buffer origin-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (psi-widget-projection--dispatch-mutation
             '((:name . ext/do-thing) (:params . ())) "ext" "w1" "b1" nil))
          ;; Other buffer has its own (independent) store.
          (with-current-buffer other-buffer
            (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
            (puthash "ext/w1:b1" 'sentinel
                     (psi-emacs-state-projection-mutation-timers psi-emacs--state)))
          ;; Fire the response while the OTHER buffer is current.
          (with-current-buffer other-buffer
            (funcall captured-cb '((:data . ((:ok . t))))))
          ;; Origin store cleared; other store untouched.
          (with-current-buffer origin-buffer
            (should (null (gethash "ext/w1:b1"
                                   (psi-emacs-state-projection-mutation-timers
                                    psi-emacs--state)))))
          (with-current-buffer other-buffer
            (should (eq 'sentinel
                        (gethash "ext/w1:b1"
                                 (psi-emacs-state-projection-mutation-timers
                                  psi-emacs--state))))))
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

;;; Event subscription tests moved to psi-widget-projection-events-test.el

(provide 'psi-widget-projection-test)
;;; psi-widget-projection-test.el ends here
