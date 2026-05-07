(ns psi.app-runtime.output
  (:require
   [clojure.string :as str]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.message-text :as message-text]
   [psi.prompt-assets.skills :as skills]
   [psi.agent-session.tools :as tools]))

(defn print-banner [model templates loaded-skills ctx]
  (println)
  (println "╔══════════════════════════════════════╗")
  (println "║  ψ  Psi Agent Session                ║")
  (println "╚══════════════════════════════════════╝")
  (println (str "  Model   : " (:name model)))
  (println (str "  Tools   : " (str/join ", " (map :name tools/all-tool-schemas))))
  (when (seq templates)
    (println (str "  Prompts : " (count templates) " loaded"))
    (doseq [t templates]
      (println (str "    /" (:name t) " — " (:description t)))))
  (when (seq loaded-skills)
    (let [visible (skills/visible-skills loaded-skills)
          hidden  (skills/hidden-skills loaded-skills)]
      (println (str "  Skills  : " (count visible) " available"
                    (when (seq hidden) (str ", " (count hidden) " hidden"))))))
  (let [ext-details (ext/extension-details-in (:extension-registry ctx))]
    (when (seq ext-details)
      (println (str "  Exts    : " (count ext-details) " loaded"))
      (doseq [d ext-details]
        (let [parts (cond-> []
                      (pos? (:tool-count d))    (conj (str (:tool-count d) " tools"))
                      (pos? (:command-count d)) (conj (str (:command-count d) " cmds"))
                      (pos? (:handler-count d)) (conj (str (:handler-count d) " handlers")))
              suffix (when (seq parts) (str " (" (str/join ", " parts) ")"))]
          (println (str "    " (.getName (java.io.File. ^String (:path d))) suffix))))))
  (println "  /help for commands, /quit to exit")
  (println))

(defn print-assistant-message
  [msg]
  (let [text   (some->> (message-text/content-text-parts (:content msg)) seq (str/join ""))
        errors (message-text/content-error-parts (:content msg))]
    (when (seq text)
      (println (str "\nψ: " text "\n")))
    (doseq [err errors]
      (println (str "\n[Provider error: " err "]\n")))
    (when (and (empty? (or text "")) (empty? errors))
      (println "\nψ: (no response)\n"))))

(defn print-initial-transcript!
  [rehydrate]
  (doseq [m (:messages rehydrate)]
    (case (:role m)
      :user (println (str "刀(startup): " (:text m)))
      :assistant (println (str "ψ(startup): " (:text m)))
      nil)))

(defn print-expansion-banner!
  [expansion]
  (when expansion
    (case (:kind expansion)
      :skill (println (str "[Skill: " (:name expansion) "]\n"))
      :template (println (str "[Template: " (:name expansion) "]\n"))
      nil)))

(defn print-command-message! [message]
  (println (str "\n" message "\n")))
