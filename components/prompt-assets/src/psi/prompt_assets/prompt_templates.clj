(ns psi.prompt-assets.prompt-templates
  "Prompt template discovery, parsing, argument expansion, and invocation.

   Architecture
   ────────────
   Prompt templates are Markdown files that expand into full prompts.
   The user types /name in the editor to invoke a template, where name
   is the filename without .md.

   Discovery loads templates from ordered sources (first-discovered wins):
     1. Global templates: ~/.psi/agent/prompts/*.md
     2. Project templates: .psi/prompts/*.md
     3. Additional paths: CLI --prompt-template <path>

   Parsing extracts YAML frontmatter (description) and body content.
   Argument expansion substitutes $1 $2 $@ $ARGUMENTS ${@:N} ${@:N:L}
   placeholders with positional arguments.

   Nullable pattern
   ────────────────
   All functions are pure or take explicit paths/data.
   No global state — template registries live in the session data atom."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================
;; Config
;; ============================================================

(def default-config
  {:global-prompts-dir  (str (System/getProperty "user.home") "/.psi/agent/prompts")
   :project-prompts-dir ".psi/prompts"
   :discovery-recursive false})

;; ============================================================
;; YAML Frontmatter Extraction
;; ============================================================

(defn- yaml-list-item?
  "True if a trimmed YAML line is a list item (starts with `- `)."
  [line]
  (str/starts-with? (str/trim line) "- "))

(defn- yaml-list-item-value
  "Extract the value from a YAML list item line (`- value` → `\"value\"`)."
  [line]
  (str/trim (subs (str/trim line) 2)))

(defn- parse-yaml-frontmatter
  "Parse a YAML frontmatter string into a Clojure map.

   Supports:
   - Scalar values: `key: value`
   - Inline-quoted scalars: `key: \"value\"`
   - Block sequences:
       key:
         - item1
         - item2

   Does not depend on clj-yaml. Returns a keyword-keyed map."
  [yaml-str]
  (let [lines (str/split-lines yaml-str)]
    (loop [remaining lines
           result {}
           current-key nil
           current-list nil]
      (if (empty? remaining)
        ;; Flush any pending list
        (if (and current-key current-list)
          (assoc result current-key (vec (reverse current-list)))
          result)
        (let [line (first remaining)
              rest-lines (rest remaining)
              trimmed (str/trim line)]
          (cond
            ;; Blank line — flush pending list if any, reset
            (str/blank? trimmed)
            (if (and current-key current-list)
              (recur rest-lines (assoc result current-key (vec (reverse current-list))) nil nil)
              (recur rest-lines result nil nil))

            ;; List item — accumulate into current list
            (and current-key (yaml-list-item? trimmed))
            (recur rest-lines result current-key (cons (yaml-list-item-value trimmed) current-list))

            ;; Key: value line
            :else
            (let [colon-idx (str/index-of trimmed ":")]
              (if (and colon-idx (pos? colon-idx))
                (let [k (keyword (str/trim (subs trimmed 0 colon-idx)))
                      v (str/trim (subs trimmed (inc colon-idx)))
                      ;; Flush any pending list before starting a new key
                      result' (if (and current-key current-list)
                                (assoc result current-key (vec (reverse current-list)))
                                result)]
                  (if (str/blank? v)
                    ;; Empty value — start a potential list
                    (recur rest-lines result' k [])
                    ;; Scalar value — strip surrounding quotes if present
                    (let [v' (if (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                               (subs v 1 (dec (count v)))
                               v)]
                      (recur rest-lines (assoc result' k v') nil nil))))
                ;; Not a key:value line and not a list item — skip
                (recur rest-lines
                       (if (and current-key current-list)
                         (assoc result current-key (vec (reverse current-list)))
                         result)
                       nil nil)))))))))

(defn extract-frontmatter
  "Extract YAML frontmatter and body from raw Markdown text.
   Frontmatter is delimited by --- fences at the start of the file.
   Returns {:frontmatter <map-or-nil> :body <string>}.

   Supports scalar values and block sequences in frontmatter."
  [raw]
  (let [trimmed (str/triml raw)]
    (if (str/starts-with? trimmed "---")
      (let [after-first (subs trimmed 3)
            end-idx     (str/index-of after-first "\n---")]
        (if end-idx
          (let [yaml-str (subs after-first 0 end-idx)
                body     (str/trim (subs after-first (+ end-idx 4)))]
            {:frontmatter (parse-yaml-frontmatter yaml-str)
             :body        body})
          ;; No closing --- fence: treat entire content as body
          {:frontmatter nil
           :body        (str/trim raw)}))
      {:frontmatter nil
       :body        (str/trim raw)})))

(defn frontmatter-flag
  "Coerce a frontmatter scalar to a boolean.
   Recognises only the literal words `true`/`false` (case-insensitive,
   whitespace-trimmed); any other value (including typos or an absent key)
   yields `default`."
  [value default]
  (case (some-> value str str/trim str/lower-case)
    "true"  true
    "false" false
    default))

;; ============================================================
;; Template Parsing
;; ============================================================

(defn first-non-empty-line
  "Return the first non-blank line from `text`, or empty string."
  [text]
  (or (first (remove str/blank? (str/split-lines text)))
      ""))

(defn parse-template-file
  "Parse a .md template file at `path`. Returns a PromptTemplate map or nil
   if the file doesn't exist or can't be read.
   `source` is one of :user :project :package :cli :settings."
  [path source]
  (let [f (io/file path)]
    (when (.exists f)
      (let [raw                    (slurp f)
            {:keys [frontmatter
                    body]}         (extract-frontmatter raw)
            name                   (str/replace (.getName f) #"\.md$" "")
            description            (or (:description frontmatter)
                                       (first-non-empty-line body))]
        {:name        name
         :description description
         :content     body
         :source      source
         :file-path   (.getAbsolutePath f)}))))

;; ============================================================
;; Placeholder Analysis
;; ============================================================

(def ^:private positional-pattern #"\$(\d+)")
(def ^:private all-placeholder-pattern #"\$(?:\d+|@|ARGUMENTS|\{@:\d+(?::\d+)?\})")

(defn has-placeholders?
  "True if `content` contains any $ placeholder."
  [content]
  (boolean (re-find all-placeholder-pattern (or content ""))))

(defn placeholder-count
  "Count of distinct $N positional references in `content`."
  [content]
  (count (set (map second (re-seq positional-pattern (or content ""))))))

;; ============================================================
;; Shell-like Tokenization
;; ============================================================

(defn tokenize-args
  "Split `input` into positional arguments.
   Quoted strings (double quotes) are single args; unquoted words are
   individual args. Supports escaped quotes within quoted strings."
  [input]
  (when-not (str/blank? input)
    (let [input (str/trim input)]
      (loop [i      0
             tokens []
             cur    nil
             in-q   false]
        (if (>= i (count input))
          (if cur
            (conj tokens (str cur))
            tokens)
          (let [c (.charAt input i)]
            (cond
              ;; Inside quoted string
              in-q
              (cond
                ;; Escaped quote inside quotes
                (and (= c \\) (< (inc i) (count input)) (= (.charAt input (inc i)) \"))
                (recur (+ i 2) tokens (str cur "\"") true)

                ;; Closing quote
                (= c \")
                (recur (inc i) tokens cur false)

                ;; Normal char inside quotes
                :else
                (recur (inc i) tokens (str cur c) true))

              ;; Outside quoted string
              (= c \")
              (recur (inc i) tokens (or cur "") true)

              ;; Whitespace — end current token
              (Character/isWhitespace c)
              (if cur
                (recur (inc i) (conj tokens (str cur)) nil false)
                (recur (inc i) tokens nil false))

              ;; Normal char
              :else
              (recur (inc i) tokens (str cur c) false))))))))

;; ============================================================
;; Placeholder Expansion
;; ============================================================

(defn- get-arg
  "Get 1-indexed arg from args vector, or empty string if missing."
  [args n]
  (let [idx (dec n)]
    (if (and (>= idx 0) (< idx (count args)))
      (nth args idx)
      "")))

(defn- expand-slice
  "Expand ${@:N} or ${@:N:L} slice references."
  [content args]
  (-> content
      ;; ${@:N:L} — L args from Nth (1-indexed)
      (str/replace #"\$\{@:(\d+):(\d+)\}"
                   (fn [[_ start-s len-s]]
                     (let [start (dec (Integer/parseInt start-s))
                           len   (Integer/parseInt len-s)]
                       (str/join " " (take len (drop start args))))))
      ;; ${@:N} — from Nth to end (1-indexed)
      (str/replace #"\$\{@:(\d+)\}"
                   (fn [[_ start-s]]
                     (let [start (dec (Integer/parseInt start-s))]
                       (str/join " " (drop start args)))))))

(defn expand-placeholders
  "Substitute placeholders in `content` with `args` (string vector).
   $1, $2, ... → positional args (1-indexed)
   $@ and $ARGUMENTS → all args joined by space
   ${@:N} → from Nth arg onward
   ${@:N:L} → L args from Nth
   Missing args produce empty string."
  [content args]
  (let [args (or args [])]
    (-> content
        ;; Slices first (before $@ expansion eats them)
        (expand-slice args)
        ;; $ARGUMENTS → all args joined
        (str/replace "$ARGUMENTS" (str/join " " args))
        ;; $@ → all args joined
        (str/replace "$@" (str/join " " args))
        ;; $N → positional (highest first to avoid $1 matching inside $10)
        (as-> c
              (let [max-n (or (some->> (re-seq positional-pattern c)
                                       (map (comp #(Integer/parseInt %) second))
                                       seq
                                       (apply max))
                              0)]
                (reduce (fn [s n]
                          (str/replace s (str "$" n) (get-arg args n)))
                        c
                        (range max-n 0 -1)))))))

;; ============================================================
;; Discovery
;; ============================================================

(defn discover-template-files
  "Return a seq of {:path <File> :source <keyword>} for all .md files
   in the given directory. Non-recursive."
  [dir-path source]
  (let [d (io/file dir-path)]
    (when (and (.exists d) (.isDirectory d))
      (->> (.listFiles d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (map (fn [f] {:path f :source source}))
           (sort-by #(.getName ^java.io.File (:path %)))))))

(defn discover-templates
  "Discover prompt templates from all sources. Returns a vector of
   PromptTemplate maps. First-discovered name wins (no override).

   `opts` keys:
     :global-prompts-dir  — path to global prompts (default: ~/.psi/agent/prompts)
     :project-prompts-dir — path to project prompts (default: .psi/prompts)
     :extra-paths         — seq of additional file paths to include
     :disabled            — if true, return empty (--no-prompt-templates)"
  ([] (discover-templates {}))
  ([opts]
   (if (:disabled opts)
     []
     (let [global-dir  (or (:global-prompts-dir opts)
                           (:global-prompts-dir default-config))
           project-dir (or (:project-prompts-dir opts)
                           (:project-prompts-dir default-config))
           ;; Ordered sources
           global-files  (discover-template-files global-dir :user)
           project-files (discover-template-files project-dir :project)
           extra-files   (map (fn [p] {:path (io/file p) :source :cli})
                              (:extra-paths opts))
           all-files     (concat global-files project-files extra-files)]
       ;; First-discovered wins
       (reduce (fn [acc {:keys [path source]}]
                 (let [tpl (parse-template-file (.getPath ^java.io.File path) source)]
                   (if (and tpl (not (some #(= (:name %) (:name tpl)) acc)))
                     (conj acc tpl)
                     acc)))
               []
               all-files)))))

;; ============================================================
;; Template Lookup
;; ============================================================

(defn find-template
  "Find a template by name in `templates` vector. Returns nil if not found."
  [templates name]
  (first (filter #(= (:name %) name) templates)))

;; ============================================================
;; Invocation
;; ============================================================

(defn parse-command
  "Parse /name args text into {:command-name <string> :args-text <string>}.
   Returns nil if text doesn't start with /."
  [text]
  (when (and (string? text) (str/starts-with? text "/"))
    (let [trimmed   (subs text 1)
          space-idx (str/index-of trimmed " ")]
      (if space-idx
        {:command-name (subs trimmed 0 space-idx)
         :args-text    (str/trim (subs trimmed (inc space-idx)))}
        {:command-name trimmed
         :args-text    ""}))))

(defn invoke-template
  "Attempt to expand a prompt template from `text`.
   Returns {:content <expanded> :source-template <name>} on match,
   or nil if no template matches.

   `templates` — vector of PromptTemplate maps
   `commands`  — set of registered command names (commands take priority)"
  [templates commands text]
  (when-let [{:keys [command-name args-text]} (parse-command text)]
    (when-not (contains? commands command-name)
      (when-let [tpl (find-template templates command-name)]
        (let [args     (tokenize-args args-text)
              expanded (expand-placeholders (:content tpl) args)]
          {:content         expanded
           :source-template (:name tpl)})))))

;; ============================================================
;; Introspection — enriched template views
;; ============================================================

(defn enrich-template
  "Add derived fields to a PromptTemplate map for introspection."
  [tpl]
  (assoc tpl
         :has-placeholders  (has-placeholders? (:content tpl))
         :placeholder-count (placeholder-count (:content tpl))))

(defn template-summary
  "Return an introspection summary of all templates."
  [templates]
  {:template-count (count templates)
   :templates      (mapv (fn [t]
                           {:name             (:name t)
                            :description      (:description t)
                            :source           (:source t)
                            :has-placeholders (has-placeholders? (:content t))
                            :placeholder-count (placeholder-count (:content t))})
                         templates)})

(defn template-names
  "Return a vector of template name strings."
  [templates]
  (mapv :name templates))

(defn templates-by-source
  "Group templates by their source."
  [templates]
  (group-by :source templates))
