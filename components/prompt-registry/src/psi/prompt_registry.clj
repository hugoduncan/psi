(ns psi.prompt-registry
  "Thin compatibility wrappers over the extracted psi.prompt-registry.contributions owner."
  (:require
   [psi.prompt-registry.contributions :as contributions]))

(def normalize-identity contributions/normalize-identity)
(def normalize-contribution contributions/normalize-contribution)
(def merge-contribution-patch contributions/merge-contribution-patch)
(def all-contributions contributions/all-contributions)
(def sort-contributions contributions/sort-contributions)
(def contribution-count contributions/contribution-count)
(def find-contribution contributions/find-contribution)
(def register-contribution contributions/register-contribution)
(def update-contribution contributions/update-contribution)
(def unregister-contribution contributions/unregister-contribution)
