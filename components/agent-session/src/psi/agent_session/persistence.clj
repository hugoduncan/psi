(ns psi.agent-session.persistence
  "Compatibility wrapper over `psi.session-persistence.core`.

   Canonical session-facing journal and persistence ownership now lives in the
   lower `session-persistence` component. This namespace remains as a temporary
   migration seam for callers/tests that have not yet moved."
  (:require
   [psi.session-persistence.core :as persistence]))

(def session-dir-for persistence/session-dir-for)
(def new-session-file-path persistence/new-session-file-path)
(def write-header! persistence/write-header!)
(def append-entry-to-disk! persistence/append-entry-to-disk!)
(def flush-journal! persistence/flush-journal!)
(def load-session-file persistence/load-session-file)
(def find-most-recent-session persistence/find-most-recent-session)
(def list-sessions persistence/list-sessions)
(def list-all-sessions persistence/list-all-sessions)

(def session-journal-path persistence/session-journal-path)
(def session-flush-state-path persistence/session-flush-state-path)
(def flush-state persistence/flush-state)
(def create-flush-state persistence/create-flush-state)
(def persistence-state persistence/persistence-state)

(def create-journal persistence/create-journal)
(def append-entry! persistence/append-entry!)
(def all-entries persistence/all-entries)
(def entries-of-kind persistence/entries-of-kind)
(def entries-up-to persistence/entries-up-to)
(def last-entry-of-kind persistence/last-entry-of-kind)
(def messages-from-entries persistence/messages-from-entries)
(def messages-up-to persistence/messages-up-to)

(def append-journal-entry-in! persistence/append-journal-entry-in!)
(def append-entry-in! persistence/append-entry-in!)
(def all-entries-in persistence/all-entries-in)
(def entries-of-kind-in persistence/entries-of-kind-in)
(def entries-up-to-in persistence/entries-up-to-in)
(def last-entry-of-kind-in persistence/last-entry-of-kind-in)
(def messages-from-entries-in persistence/messages-from-entries-in)
(def messages-up-to-in persistence/messages-up-to-in)

(def persist-state-entry! persistence/persist-state-entry!)
(def persist-entry! persistence/persist-entry!)
(def persist-journal-in! persistence/persist-journal-in!)
(def persist-entry-in! persistence/persist-entry-in!)

(def message-entry persistence/message-entry)
(def thinking-level-entry persistence/thinking-level-entry)
(def model-entry persistence/model-entry)
(def compaction-entry persistence/compaction-entry)
(def branch-summary-entry persistence/branch-summary-entry)
(def custom-message-entry persistence/custom-message-entry)
(def label-entry persistence/label-entry)
(def session-info-entry persistence/session-info-entry)
