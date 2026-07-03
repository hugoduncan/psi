(ns extensions.context-manager-test-support
  "Shared fixtures and test doubles for the context-manager entity-resolution
   test suite (split across several test namespaces). Extracted so the
   turn-projection fixture, the augmenter collaborators stub, the
   settle-await poll, and the `default-run-helper` collaborator double are
   defined once and stay in lock-step rather than drifting across files."
  (:require
   [extensions.context-manager :as context-manager]))

(def base-tp
  "A minimal well-formed turn projection eligible for the entity-resolution
   augmenter: non-tracked session, non-blank cwd, non-slash-command text."
  {:turn-augmentation/session-id "s1"
   :turn-augmentation/effective-cwd "/repo"
   :turn-augmentation/user-text "please look at the resolver"
   :turn-augmentation/history []})

(defn stub
  "Build an `entity-resolution-augmentation` collaborators map.

   `:select-model` returns the supplied `:model` (default a local ollama
   model; pass `:model nil` for the no-local-model path). `:run-helper`
   returns `{:child-session-id child-id :text text}`, or throws when
   `:throw?` is set. When a `:calls` atom is supplied, records `:select` /
   `:run` invocation counts under those keys."
  [{:keys [model text child-id throw? calls]
    :or   {model {:provider :ollama :id "qwen"} child-id "helper-1"}}]
  {:select-model (fn [_parent]
                   (when calls (swap! calls (fnil update {}) :select (fnil inc 0)))
                   model)
   :run-helper   (fn [_opts]
                   (when calls (swap! calls (fnil update {}) :run (fnil inc 0)))
                   (if throw?
                     (throw (ex-info "boom" {}))
                     {:child-session-id child-id :text text}))})

(defn await-untracked
  "Block (up to ~2s) until `id` is no longer tracked in the
   entity-resolution helper-session atom. The settled run future closes +
   untracks on its own thread, so tests must await it."
  [id]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (while (and (contains? @context-manager/entity-resolution-helper-session-ids id)
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 5))))

(defn fake-run-api
  "A configurable `api` map for exercising `default-run-helper`.

   Records the create-child-session params (`:create-calls`), the
   run-agent-loop-in-session params (`:run-calls`), and the close-session id
   (`:closed`) into the supplied atoms when present.

   create-child-session yields `{:psi.agent-session/session-id child-id}`
   unless `:create-result` overrides it (e.g. `{}` for the nil-child failure
   path) or `:create-throws?` is set (models a dispatch/create failure).

   run-agent-loop-in-session returns `:run-result`, unless `:run-throws?` is
   set (surfaces as a thrown run through `deref`).

   For timeout / recursion tests, `:block-until` (an atom) and `:run-began`
   (a promise) model a live, NOT reliably interruptible model/HTTP call: the
   run delivers `:run-began`, then busy-spins (clearing interrupt status so
   `future-cancel` cannot unwind it) until `:block-until` is set, so the
   orphan outlives an injected wall-clock budget deterministically."
  [{:keys [run-result create-calls run-calls closed child-id
           create-result create-throws? run-throws? block-until run-began]
    :or   {child-id "child-1"}}]
  {:mutate-session
   (fn [_sid op params]
     (case op
       psi.extension/create-child-session
       (do (when create-calls (reset! create-calls params))
           (when create-throws? (throw (ex-info "create boom" {})))
           (or create-result {:psi.agent-session/session-id child-id}))
       psi.extension/run-agent-loop-in-session
       (do (when run-calls (reset! run-calls params))
           (when run-began (deliver run-began true))
           (when block-until
             (while (not @block-until)
               (Thread/interrupted)
               (Thread/onSpinWait)))
           (when run-throws? (throw (ex-info "run boom" {})))
           run-result)))
   :mutate (fn [op params]
             (when (and closed (= op 'psi.extension/close-session))
               (reset! closed (:session-id params)))
             nil)})
