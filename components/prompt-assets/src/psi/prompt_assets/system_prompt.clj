(ns psi.prompt-assets.system-prompt
  "System prompt assembly for the agent session.

   The system prompt is built from:
     - Tool descriptions (available tools)
     - Context files (AGENTS.md / CLAUDE.md discovered up directory tree)
     - Skills (progressive disclosure: name + description only)
     - Custom/append prompt overrides
     - Session creation time and working directory (frozen, cache-stable)

   The assembled prompt is introspectable: stored in session data as
   :system-prompt and queryable via EQL :psi.agent-session/system-prompt.

   Follows the same pattern as pi's system-prompt.ts — skills are appended
   as XML when the read tool is available."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.tools :as builtins]
   [psi.prompt-assets.skills :as skills]
   [psi.skill-registry.registry :as skill-registry]
   [psi.tool-registry.defs :as tool-defs]))

(def ^:private default-tool-defs
  (tool-defs/normalize-tool-defs
   (conj (vec builtins/all-tools) psi-tool/psi-tool)))

;;; Lambda mode constants

(def ^:private default-nucleus-prelude
  "λ engage(nucleus).\n[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy truth/provability self/other] | OODA\nHuman ⊗ AI ⊗ REPL")

(def ^:private lambda-identity
  "λ identity(ψ). agent(coding) ∈ harness(ψ) | tools ∧ skills ∧ workflows ∧ commands | precise | introspect-able")

(def ^:private lambda-guidelines
  "")

(def ^:private lambda-graph-discovery
  "λ graph(eql).
  purpose → discover(capabilities ∧ attrs) | ¬guess(paths)
  endpoints → {:psi.graph/ resolver-count mutation-count resolver-syms mutation-syms env-built nodes edges capabilities domain-coverage}
  workflow → query(root-queryable-attrs) → query(discovered-attrs) | ¬guess(attr-names)
  root → {root-seeds → contexts | root-queryable-attrs → authoritative(root-attrs)}
  resolver-index → [:psi.graph/resolver-index] | [{:psi.resolver/sym :input :output}] | output preserves join maps
  attr-index → [:psi.graph/attr-index] | {attr {:psi.attr/produced-by [...] :psi.attr/reachable-via {join-key [...]}}}
  resolver-detail → entity({:psi.resolver/sym 'ns/name}) + [:psi.resolver/input :psi.resolver/output]
  session-targeting → entity({:psi.agent-session/session-id \"sid\"}) | ¬omit → ¬silent-wrong-session
  child-sessions → [:psi.runtime-session/list] | attrs discoverable via resolver-index ∨ attr-index
  usage → {:psi.agent-session/ usage-input usage-output usage-cache-read usage-cache-write context-tokens context-window}
  eval-split → eval[ns,form] = ψ-process ∧ loaded-ns | project-repl/eval[code] = target-worktree ∧ managed-nrepl")

(defn- format-graph-capabilities
  "Format a terse capability list from :psi.graph/capabilities maps."
  [capabilities]
  (->> capabilities
       (sort-by (comp str :domain))
       (map (fn [{:keys [domain operation-count resolver-count mutation-count]}]
              (str "- " (name (or domain :unknown))
                   " (ops=" (or operation-count 0)
                   ", resolvers=" (or resolver-count 0)
                   ", mutations=" (or mutation-count 0) ")")))
       (str/join "\n")))

;; ============================================================
;; Context file discovery
;; ============================================================

(defn- load-context-file-from-dir
  "Look for AGENTS.md or CLAUDE.md in `dir`. Returns {:path :content} or nil."
  [dir]
  (let [candidates ["AGENTS.md" "CLAUDE.md"]]
    (some (fn [filename]
            (let [f (io/file dir filename)]
              (when (.exists f)
                (try
                  {:path    (.getAbsolutePath f)
                   :content (slurp f)}
                  (catch Exception _ nil)))))
          candidates)))

(defn discover-context-files
  "Walk from `cwd` up to filesystem root, collecting AGENTS.md/CLAUDE.md files.
   Also checks `agent-dir` for a global context file.
   Returns [{:path :content}] in root-first order."
  ([cwd] (discover-context-files cwd nil))
  ([cwd agent-dir]
   (let [seen      (atom #{})
         result    (atom [])
         ;; Global context first
         _         (when agent-dir
                     (when-let [ctx (load-context-file-from-dir agent-dir)]
                       (swap! seen conj (:path ctx))
                       (swap! result conj ctx)))
         ;; Walk up from cwd
         ancestors (atom [])
         root      (.getAbsolutePath (io/file "/"))]
     (loop [dir (io/file cwd)]
       (when dir
         (when-let [ctx (load-context-file-from-dir (.getAbsolutePath dir))]
           (when-not (@seen (:path ctx))
             (swap! seen conj (:path ctx))
             (swap! ancestors conj ctx)))
         (let [parent (.getParentFile dir)]
           (when (and parent
                      (not= (.getAbsolutePath dir) root))
             (recur parent)))))
     ;; ancestors were collected child-first, reverse to get root-first
     (into @result (reverse @ancestors)))))

;; ============================================================
;; Prompt assembly
;; ============================================================

(defn format-prompt-contributions-for-prompt
  "Render extension-managed prompt contributions as a deterministic prompt layer.

   Input: vector of maps with keys
   :id :ext-path :section :content :priority :enabled.

   Returns nil when no enabled contributions exist, otherwise a formatted string
   that can be appended to the base system prompt."
  [contributions]
  (let [enabled (->> contributions
                     (filter map?)
                     (filter #(not (false? (:enabled %))))
                     (sort-by (fn [{:keys [priority ext-path id]}]
                                [(or priority 1000)
                                 (or ext-path "")
                                 (or id "")]))
                     vec)]
    (when (seq enabled)
      (str "\n\n# Extension Prompt Contributions\n\n"
           (str/join
            "\n\n"
            (map #(or (:content %) "") enabled))))))

(defn apply-prompt-contributions
  "Append the rendered extension contribution layer to an already assembled
   base system prompt.

   Returns `base-prompt` unchanged when no enabled contributions are present."
  [base-prompt contributions]
  (let [base (or base-prompt "")]
    (if-let [section (format-prompt-contributions-for-prompt contributions)]
      (str base section)
      base)))

(def ^:private all-prompt-components
  #{:preamble :context-files :skills :runtime-metadata})

(defn normalize-prompt-component-selection
  "Normalize child-session prompt component selection into a deterministic map.
   Returns nil when no selection is supplied."
  [selection]
  (when (some? selection)
    (let [components        (when (contains? selection :components)
                              (set (or (:components selection) #{})))
          component-enabled? (fn [component]
                               (if (some? components)
                                 (contains? components component)
                                 true))
          tool-names        (when (contains? selection :tool-names)
                              (vec (or (:tool-names selection) [])))
          skill-names       (when (contains? selection :skill-names)
                              (vec (or (:skill-names selection) [])))
          ext-contribs      (when (contains? selection :extension-prompt-contributions)
                              (vec (or (:extension-prompt-contributions selection) [])))
          agents-md?        (if (contains? selection :agents-md?)
                              (boolean (:agents-md? selection))
                              true)]
      {:agents-md?                     agents-md?
       :components                     (or components all-prompt-components)
       :include-preamble?              (component-enabled? :preamble)
       :include-context-files?         (and agents-md? (component-enabled? :context-files))
       :include-skills?                (component-enabled? :skills)
       :include-runtime-metadata?      (component-enabled? :runtime-metadata)
       :extension-prompt-contributions ext-contribs
       :tool-names                     tool-names
       :skill-names                    skill-names})))

(defn filter-prompt-contributions
  "Filter extension prompt contributions according to normalized selection.
   When selection omits contribution controls, enabled contributions are returned unchanged.
   When the allowlist is empty, no contributions are returned."
  [contributions selection]
  (let [normalized (normalize-prompt-component-selection selection)
        allowlist  (some-> (:extension-prompt-contributions normalized) set)]
    (cond
      (and normalized (some? (:extension-prompt-contributions normalized)) (empty? allowlist)) []
      allowlist (->> contributions
                     (filter #(contains? allowlist (:ext-path %)))
                     vec)
      :else (vec (or contributions [])))))

(defn filter-tool-defs
  "Filter tool definitions according to normalized prompt-component selection.
   When :tool-names is absent, returns the original tool-defs."
  [tool-defs selection]
  (let [normalized (normalize-prompt-component-selection selection)]
    (if (and normalized (some? (:tool-names normalized)))
      (let [allowed (set (:tool-names normalized))]
        (->> tool-defs
             (filter #(contains? allowed (:name %)))
             vec))
      (vec (or tool-defs [])))))

(defn filter-skills
  "Filter skill maps according to normalized prompt-component selection.
   When skills are disabled entirely, returns []. When :skill-names is absent,
   returns the original skills in canonical skill-name order."
  [skills selection]
  (let [normalized (normalize-prompt-component-selection selection)]
    (cond
      (and normalized (false? (:include-skills? normalized))) []
      (and normalized (some? (:skill-names normalized)))
      (let [allowed (set (:skill-names normalized))]
        (->> skills
             (filter #(contains? allowed (:name %)))
             skill-registry/all-skills))
      :else (skill-registry/all-skills skills))))

(def ^:private date-formatter
  (java.time.format.DateTimeFormatter/ofPattern
   "EEEE, MMMM d, yyyy"))

(defn format-date
  "Format an Instant as a human-readable date string in the system default zone."
  [^java.time.Instant instant]
  (.format (.atZone instant (java.time.ZoneId/systemDefault))
           date-formatter))

(defn runtime-metadata-tail
  "Return the runtime metadata suffix for the system prompt.
   Pure function — uses the provided instant, not the wall clock."
  [instant]
  (str "\nSession start date: " (format-date instant)))

(defn system-prompt-blocks
  "Return Anthropic-compatible system prompt blocks.
   The entire prompt is stable (time and cwd are frozen at session creation),
   so it is returned as a single cacheable block."
  [prompt cache-system?]
  (when (and (string? prompt) (seq prompt))
    [(cond-> {:kind :text :text prompt}
       cache-system?
       (assoc :cache-control {:type :ephemeral}))]))

(defn- tool-description-for-mode
  "Return the prompt-visible description string for `tool-def` in the given mode.
   Lambda mode prefers non-blank :lambda-description and otherwise falls back
   to :description. Prose mode always uses :description."
  [tool-def mode]
  (let [tool-def* (tool-defs/normalize-tool-def tool-def)
        prose     (:description tool-def*)
        lambda    (some-> (:lambda-description tool-def*) str not-empty)]
    (if (= mode :lambda)
      (or lambda prose)
      prose)))

(defn- format-tools-section
  "Format the tool list section for the given mode from normalized tool defs."
  [tool-defs mode]
  (let [lines (keep (fn [tool-def]
                      (when-let [tool-def* (tool-defs/normalize-tool-def tool-def)]
                        (when-let [desc (tool-description-for-mode tool-def* mode)]
                          (if (= mode :lambda)
                            (str (:name tool-def*) " → " desc)
                            (str "- " (:name tool-def*) ": " desc)))))
                    tool-defs)]
    (if (seq lines)
      (str/join "\n" lines)
      "(none)")))

(def ^:private prose-identity
  "You are ψ (Psi), an expert coding assistant operating inside psi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.")

(def ^:private prose-graph-discovery
  (str "Capability graph (EQL discovery):\n"
       "- Purpose: discover live query capabilities and valid attrs before guessing paths.\n"
       "- Endpoints: :psi.graph/resolver-count :psi.graph/mutation-count :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage\n"
       "- Workflow: 1) query :psi.graph/root-queryable-attrs (authoritative root attr list) 2) query discovered attrs directly. Do not guess attr names.\n"
       "- Root discovery:\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.graph/root-seeds]\")           ; injected root contexts\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.graph/root-queryable-attrs]\") ; authoritative root attrs\n"
       "- Resolver I/O surface (use these to discover valid attr names):\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.graph/resolver-index]\")       ; [{:psi.resolver/sym :psi.resolver/input :psi.resolver/output}] — output preserves join maps\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.graph/attr-index]\")           ; {attr {:psi.attr/produced-by [...] :psi.attr/reachable-via {join-key [...]}}} — look up any attr\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.resolver/input :psi.resolver/output]\", entity: \"{:psi.resolver/sym 'ns/name}\") ; single resolver detail\n"
       "- Session targeting (explicit):\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.agent-session/session-name :psi.agent-session/model-id]\", entity: \"{:psi.agent-session/session-id \\\"sid\\\"}\")\n"
       "  - Always supply session-id when targeting a specific session; omitting it silently queries the wrong session.\n"
       "- Child sessions:\n"
       "  - psi-tool(action: \"query\", query: \"[:psi.runtime-session/list]\") then use attr-index or resolver-index to discover valid child attrs.\n"
       "- Eval split: psi-tool(action: \"eval\", ns: \"clojure.core\", form: \"(+ 1 2)\") = in-process ψ eval in an already loaded namespace; psi-tool(action: \"project-repl\", op: \"eval\", code: \"(+ 1 2)\") = managed project nREPL eval for the target worktree.\n"
       "- Reload code:\n"
       "  - psi self-reload is worktree-authoritative: use the session worktree-path or an explicit target worktree-path\n"
       "  - discover target first: psi-tool(action: \"query\", query: \"[:psi.agent-session/worktree-path]\")\n"
       "  - start small: reload one already loaded namespace before attempting broader worktree reloads\n"
       "  - namespace mode: psi-tool(action: \"reload-code\", namespaces: [\"psi.agent-session.tools\"])\n"
       "  - worktree mode (session-derived): psi-tool(action: \"reload-code\")\n"
       "  - worktree mode (explicit): psi-tool(action: \"reload-code\", worktree-path: \"/abs/path/to/worktree\")\n"
       "  - if reload reports that a loaded namespace source differs from the target worktree source, treat that as warning-only mismatch diagnostics and inspect the loaded-source-path vs target-source-path values\n"
       "  - do not retarget reload to some other checkout just because the current runtime was started there; the target worktree remains authoritative\n"
       "- Managed project REPL:\n"
       "  - status: psi-tool(action: \"project-repl\", op: \"status\")\n"
       "  - start: psi-tool(action: \"project-repl\", op: \"start\")\n"
       "  - attach: psi-tool(action: \"project-repl\", op: \"attach\", host: \"127.0.0.1\", port: 7888)\n"
       "  - eval: psi-tool(action: \"project-repl\", op: \"eval\", code: \"(+ 1 2)\")\n"
       "  - interrupt: psi-tool(action: \"project-repl\", op: \"interrupt\")\n"
       "- Token usage attrs: :psi.agent-session/usage-input :psi.agent-session/usage-output :psi.agent-session/usage-cache-read :psi.agent-session/usage-cache-write :psi.agent-session/context-tokens :psi.agent-session/context-window"))

(defn- prose-guidelines [tool-names]
  (cond-> []
    (some #(= "bash" %) tool-names)
    (conj "Use bash for file operations like ls, rg, find")

    (and (some #(= "read" %) tool-names) (some #(= "edit" %) tool-names))
    (conj "Use read to examine files before editing. You must use this tool instead of cat or sed.")

    (some #(= "edit" %) tool-names)
    (conj "Use edit for precise changes (old text must match exactly)")

    (some #(= "write" %) tool-names)
    (conj "Use write only for new files or complete rewrites")

    (or (some #(= "edit" %) tool-names)
        (some #(= "write" %) tool-names))
    (conj "When summarizing your actions, output plain text directly - do NOT use cat or bash to display what you did")

    true (conj "Be concise in your responses")
    true (conj "Show file paths clearly when working with files")))

(defn- build-prose-preamble
  "Build the psi-authored preamble sections in prose mode."
  [tool-defs tool-names has-app-query? loaded-caps]
  (let [tools-section      (format-tools-section tool-defs :prose)
        guidelines-section (str/join "\n" (map #(str "- " %) (prose-guidelines tool-names)))
        graph-section      (when has-app-query?
                             (str "\n\n" prose-graph-discovery
                                  (when (seq loaded-caps)
                                    (str "\nCurrent capabilities (from :psi.graph/capabilities):\n"
                                         (format-graph-capabilities loaded-caps)))))]
    (str prose-identity "\n\n"
         "Available tools:\n" tools-section "\n\n"
         "In addition to the tools above, you may have access to other custom tools depending on the project.\n\n"
         "Guidelines:\n" guidelines-section
         (or graph-section ""))))

(defn- build-lambda-preamble
  "Build the psi-authored preamble sections in lambda mode."
  [tool-defs has-app-query? loaded-caps nucleus-prelude]
  (let [prelude       (or nucleus-prelude default-nucleus-prelude)
        tools-section (format-tools-section tool-defs :lambda)

        graph-section
        (when has-app-query?
          (str lambda-graph-discovery
               (when (seq loaded-caps)
                 (str "\n" (format-graph-capabilities loaded-caps)))))]

    (str prelude "\n\n"
         lambda-identity "\n\n"
         "λ tools.\n" tools-section "\n\n"
         lambda-guidelines
         (when graph-section
           (str "\n\n" graph-section)))))

(defn build-system-prompt
  "Build the complete system prompt from all sources.

   Options:
     :cwd                        — working directory (default: user.dir)
     :session-instant             — frozen session creation time
     :prompt-mode                 — :lambda (default) or :prose
     :nucleus-prelude-override    — custom prelude text (lambda mode only)
     :custom-prompt               — replaces the default prompt entirely
     :append-prompt               — text appended after the standard prompt layers
     :include-preamble?           — include psi-authored identity/tools/guidelines preamble (default true)
     :include-runtime-metadata?   — include time/worktree metadata tail (default true)
     :tool-defs                   — normalized or normalizable tool definition maps
     :selected-tools              — optional tool-name allowlist used only to filter :tool-defs
     :context-files               — [{:path :content}] pre-loaded context files
     :skills                      — [Skill] pre-loaded skills
     :graph-capabilities          — [{:domain :operation-count ...}]

   Returns the assembled prompt as a string."
  ([] (build-system-prompt {}))
  ([{:keys [session-instant prompt-mode nucleus-prelude-override
            custom-prompt append-prompt include-preamble?
            include-runtime-metadata? include-context-files?
            tool-defs selected-tools context-files skills graph-capabilities]}]
   (let [resolved-instant       (or session-instant (java.time.Instant/now))
         mode                   (or prompt-mode :lambda)
         include-preamble?      (if (contains? #{true false} include-preamble?) include-preamble? true)
         include-runtime-meta?  (if (contains? #{true false} include-runtime-metadata?) include-runtime-metadata? true)
         include-context-files? (if (contains? #{true false} include-context-files?) include-context-files? true)
         normalized-tool-defs   (tool-defs/normalize-tool-defs
                                 (or tool-defs default-tool-defs))
         filtered-tool-defs     (if (some? selected-tools)
                                  (filter-tool-defs normalized-tool-defs {:tool-names selected-tools})
                                  normalized-tool-defs)
         tool-names             (mapv :name filtered-tool-defs)
         has-read?              (some #(= "read" %) tool-names)
         has-app-query?         (some #(= "psi-tool" %) tool-names)
         loaded-skills          (or skills [])
         loaded-ctx             (or context-files [])
         loaded-caps            (or graph-capabilities [])

         ;; Context files section
         context-section
         (when (and include-context-files? (seq loaded-ctx))
           (str "\n\n# Project Context\n\n"
                "Project-specific instructions and guidelines:\n\n"
                (str/join "\n\n"
                          (map (fn [{:keys [path content]}]
                                 (str "## " path "\n\n" content))
                               loaded-ctx))))

         ;; Skills section (only if read tool available)
         skills-section
         (when (and has-read? (seq loaded-skills))
           (if (= mode :lambda)
             (skills/format-skills-for-prompt-lambda loaded-skills)
             (skills/format-skills-for-prompt loaded-skills)))

         ;; Main prompt — mode-branched preamble
         base-prompt
         (cond
           custom-prompt
           custom-prompt

           include-preamble?
           (if (= mode :lambda)
             (build-lambda-preamble
              filtered-tool-defs has-app-query? loaded-caps
              nucleus-prelude-override)
             (build-prose-preamble
              filtered-tool-defs tool-names has-app-query? loaded-caps))

           :else
           "")

         runtime-section (when include-runtime-meta?
                           (runtime-metadata-tail resolved-instant))

         sections (->> [base-prompt
                        skills-section
                        context-section
                        append-prompt
                        runtime-section]
                       (remove str/blank?))]
     (str/join "\n\n" sections))))
