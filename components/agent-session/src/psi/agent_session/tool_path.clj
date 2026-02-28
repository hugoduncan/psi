(ns psi.agent-session.tool-path
  "Shared path resolution for built-in file and search tools.

   Implements canonical behavior from spec/tools/path-resolution.allium:
   - expand-path: strip @-prefix, normalize unicode spaces, expand ~/~
   - resolve-to-cwd: resolve relative paths against working directory
   - resolve-read-path: expand + resolve + try macOS filename variants

   All file/search tools should use these helpers for consistent path handling."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.text Normalizer Normalizer$Form]))

;;; Unicode space codepoints to normalize

(def ^:private unicode-spaces
  "Unicode space characters to normalize to ASCII space.
   Includes narrow no-break space (U+202F), thin space (U+2009),
   hair space (U+200A), em space (U+2003), en space (U+2002),
   figure space (U+2007), punctuation space (U+2008),
   ideographic space (U+3000), and no-break space (U+00A0)."
  #{\u202F \u2009 \u200A \u2003 \u2002 \u2007 \u2008 \u3000 \u00A0})

(defn expand-path
  "Expand a raw path string:
   1. Strip leading @ prefix
   2. Replace unicode space characters with ASCII space
   3. Expand ~ and ~/ to user home directory

   Returns the expanded path string."
  [raw-path]
  (let [;; Step 1: Strip leading @
        s (if (str/starts-with? raw-path "@")
            (subs raw-path 1)
            raw-path)
        ;; Step 2: Normalize unicode spaces to ASCII space
        s (apply str (map (fn [c] (if (unicode-spaces c) \space c)) s))
        ;; Step 3: Expand tilde
        home (System/getProperty "user.home")]
    (cond
      (= s "~")              home
      (str/starts-with? s "~/") (str home (subs s 1))
      :else                   s)))

(defn resolve-to-cwd
  "Resolve a path against a working directory.
   If path is absolute, returns it as-is.
   If relative, joins with cwd.

   Returns a java.io.File."
  ^File [cwd path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (if cwd
        (io/file cwd path)
        f))))

;;; macOS filename variant helpers

(defn- am-pm-variant
  "Replace ASCII space before AM/PM with narrow no-break space (U+202F).
   macOS sometimes uses this in filenames with timestamps."
  [^String path]
  (-> path
      (str/replace #" (AM|PM)" (str "\u202F" "$1"))
      (str/replace #" (am|pm)" (str "\u202F" "$1"))))

(defn- nfd-variant
  "Normalize path to NFD (decomposed) Unicode form.
   macOS HFS+ uses NFD normalization for filenames."
  [^String path]
  (Normalizer/normalize path Normalizer$Form/NFD))

(defn- curly-quote-variant
  "Replace straight quotes with curly/smart quotes.
   macOS sometimes stores filenames with curly quotes."
  [^String path]
  (-> path
      (str/replace "'" "\u2019")
      (str/replace "\"" "\u201C")))

(defn- nfd-curly-variant
  "Apply both NFD normalization and curly quote replacement."
  [^String path]
  (-> path nfd-variant curly-quote-variant))

(defn resolve-read-path
  "Resolve a path for reading, with macOS fallback variants.

   1. Expand the raw path (strip @, normalize unicode spaces, expand ~)
   2. Resolve against cwd
   3. If the resolved file exists, return it
   4. Otherwise try macOS variants in order:
      - AM/PM narrow-no-break-space variant
      - NFD-normalized variant
      - Curly-quote variant
      - NFD + curly-quote combined variant
   5. Return first existing variant, or the original resolved path

   Returns a java.io.File."
  ^File [raw-path cwd]
  (let [expanded  (expand-path raw-path)
        resolved  (resolve-to-cwd cwd expanded)
        abs-path  (.getAbsolutePath resolved)]
    (if (.exists resolved)
      resolved
      ;; Try macOS variants
      (let [variants [(io/file (am-pm-variant abs-path))
                      (io/file (nfd-variant abs-path))
                      (io/file (curly-quote-variant abs-path))
                      (io/file (nfd-curly-variant abs-path))]]
        (or (first (filter #(.exists ^File %) variants))
            resolved)))))
