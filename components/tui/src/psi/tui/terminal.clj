(ns psi.tui.terminal
  "Terminal implementations.

   ProcessTerminal  — real OS terminal wrapping stdin/stdout.
                      Uses Java ProcessBuilder-style raw-mode via JVM threads.
   VirtualTerminal  — in-memory terminal for tests (Nullable pattern).

   Implements rules:
     TUIStarts        (start! wires callbacks; hides cursor; enables Kitty/paste)
     TUIStops         (stop! restores state; disables Kitty/paste)
     KittyProtocolQueried   (start! sends query sequence)
     BracketedPasteEnabled  (start! enables bracketed paste)
     KittyProtocolDisabledOnStop
     BracketedPasteDisabledOnStop"
  (:require
   [clojure.string :as str]
   [psi.tui.protocols :as proto])
  (:import
   (java.io FileInputStream PrintStream)
   (org.jline.utils Signals)))

;;;; Escape constants

(def ^:private hide-cursor   "\u001b[?25l")
(def ^:private show-cursor   "\u001b[?25h")
(def ^:private kitty-query   "\u001b[?u")
(def ^:private kitty-disable "\u001b[<u")
(def ^:private paste-enable  "\u001b[?2004h")
(def ^:private paste-disable "\u001b[?2004l")

(defn move-cursor-to
  "Return an ANSI sequence to move the hardware cursor to {:row r :col c}."
  [{:keys [row col]}]
  (format "\u001b[%d;%dH" (inc row) (inc col)))

;;;; VirtualTerminal (Nullable / in-memory)

(defrecord VirtualTerminal
           [cols-atom
            rows-atom
            output-atom          ; vector of written strings
            kitty-active-atom
            running-atom]

  proto/Terminal
  (start! [this on-input on-resize]
    ;; In-memory: record that we're running; write startup sequences to output.
    (reset! running-atom true)
    (swap! output-atom conj kitty-query)
    (swap! output-atom conj paste-enable)
    (swap! output-atom conj hide-cursor)
    ;; Store callbacks so tests can fire synthetic events.
    (alter-meta! (:on-input-atom (meta this)) (constantly on-input))
    (alter-meta! (:on-resize-atom (meta this)) (constantly on-resize))
    this)

  (stop! [_this]
    (reset! running-atom false)
    (swap! output-atom conj kitty-disable)
    (swap! output-atom conj paste-disable)
    (swap! output-atom conj show-cursor))

  (write! [_this data]
    (swap! output-atom conj data))

  (columns [_this] @cols-atom)
  (rows    [_this] @rows-atom))

(defn create-virtual-terminal
  "Create an in-memory VirtualTerminal for testing.
   opts: {:cols 80 :rows 24}

   Returns the terminal. Use written-output to inspect output.
   Use fire-input! / fire-resize! to inject synthetic events."
  ([] (create-virtual-terminal {}))
  ([{:keys [cols rows] :or {cols 80 rows 24}}]
   (let [on-input-atom  (atom nil)
         on-resize-atom (atom nil)
         term           (->VirtualTerminal
                         (atom cols)
                         (atom rows)
                         (atom [])
                         (atom false)
                         (atom false))]
     ;; Stash callbacks into metadata so fire-input!/fire-resize! can reach them.
     (with-meta term {:on-input-atom  on-input-atom
                      :on-resize-atom on-resize-atom}))))

(defn written-output
  "Return the vector of strings written to a VirtualTerminal."
  [vterm]
  @(:output-atom vterm))

(defn fire-input!
  "Inject a synthetic key event into a running VirtualTerminal."
  [vterm raw-key]
  (when-let [cb (deref (:on-input-atom (meta vterm)))]
    (cb raw-key)))

(defn fire-resize!
  "Inject a synthetic resize event into a running VirtualTerminal."
  [vterm cols rows]
  (reset! (:cols-atom vterm) cols)
  (reset! (:rows-atom vterm) rows)
  (when-let [cb (deref (:on-resize-atom (meta vterm)))]
    (cb cols rows)))

;;;; ProcessTerminal (real OS terminal)

(defn- run-stty!
  "Run stty with args against /dev/tty. Ignores errors."
  [& args]
  (try
    (-> (ProcessBuilder. ^java.util.List (into ["stty"] args))
        (.redirectInput (java.io.File. "/dev/tty"))
        (.redirectOutput (java.io.File. "/dev/tty"))
        (.start)
        (.waitFor))
    (catch Exception _ nil)))

(defn- query-terminal-size
  "Query the real terminal dimensions via `stty size`.
   Returns {:cols n :rows n} or nil if unavailable."
  []
  (try
    (let [pb  (-> (ProcessBuilder. ["stty" "size"])
                  (.redirectInput (java.io.File. "/dev/tty")))
          p   (.start pb)
          out (slurp (.getInputStream p))
          _   (.waitFor p)
          [rows-s cols-s] (str/split (str/trim out) #"\s+")]
      (when (and rows-s cols-s)
        {:cols (Long/parseLong cols-s) :rows (Long/parseLong rows-s)}))
    (catch Exception _ nil)))

(def ^:private key-translations
  {"\r"         "enter"
   "\n"         "enter"
   "\u001b"     "escape"
   "\u001b[A"   "up"
   "\u001b[B"   "down"
   "\u001b[C"   "right"
   "\u001b[D"   "left"
   "\u001b[H"   "home"
   "\u001b[F"   "end"
   "\u001b[1~"  "home"
   "\u001b[4~"  "end"
   "\u001b[5~"  "pageUp"
   "\u001b[6~"  "pageDown"
   "\u001b[3~"  "delete"
   "\u007f"     "backspace"
   "\u0008"     "backspace"
   "\t"         "tab"
   "\u001b[13;2u" "shift+enter"
   "\u001b\r"     "alt+enter"})

(defn- translate-key
  "Translate raw VT byte sequences to symbolic key names that components expect.
   Printable characters are returned as-is.
   Unknown sequences are returned as-is."
  [^String raw]
  (get key-translations raw raw))

(defn- csi-sequence-length
  [^String s]
  (loop [i 2]
    (cond
      (>= i (count s)) (count s)
      :else
      (let [c (int (nth s i))]
        (if (<= 0x40 c 0x7e)
          (inc i)
          (recur (inc i)))))))

(defn- escape-sequence-length
  [^String s]
  (cond
    (and (> (count s) 2) (= \[ (nth s 1)))
    (csi-sequence-length s)

    (= 1 (count s)) 1
    :else 2))

(defn- dispatch-input
  "Split a raw read into individual key events and call on-input for each.
   Escape sequences (starting with ESC) are kept whole.
   Everything else is split character by character."
  [^String raw on-input]
  (loop [s raw]
    (when (seq s)
      (if (= \u001b (first s))
        (let [known-len (escape-sequence-length s)]
          (on-input (translate-key (subs s 0 known-len)))
          (recur (subs s known-len)))
        (do
          (on-input (translate-key (str (first s))))
          (recur (subs s 1)))))))

(defn- read-input-loop
  "Read bytes from /dev/tty, split into key events, call on-input.
   Runs in a background daemon thread. Exits when running? becomes false."
  [on-input running?]
  (try
    (let [tty (FileInputStream. "/dev/tty")
          buf (byte-array 64)]
      (loop []
        (when @running?
          (let [n (.read tty buf 0 64)]
            (when (pos? n)
              (dispatch-input (String. buf 0 n "UTF-8") on-input))
            (recur)))))
    (catch Exception _e nil)))

(defrecord ProcessTerminal
           [^PrintStream out
            cols-atom
            rows-atom
            kitty-active-atom
            running-atom
            reader-thread-atom]

  proto/Terminal
  (start! [this on-input on-resize]
    (reset! running-atom true)
    ;; Enable raw mode: no echo, no canonical buffering
    (run-stty! "-echo" "raw")
    ;; Query real terminal size at startup; update atoms before first render.
    (when-let [{:keys [cols rows]} (query-terminal-size)]
      (reset! cols-atom cols)
      (reset! rows-atom rows))
    ;; Register SIGWINCH handler to update dimensions and trigger a repaint
    ;; when the terminal is resized (e.g. via tmux resize-pane).
    ;; Wrapped in try/catch: Signals/register may throw on platforms that
    ;; do not support SIGWINCH (e.g. Windows), and we must not crash start!.
    (try
      (Signals/register "WINCH"
                        (reify Runnable
                          (run [_]
                            (when-let [{:keys [cols rows]} (query-terminal-size)]
                              (reset! cols-atom cols)
                              (reset! rows-atom rows)
                              (on-resize cols rows)))))
      (catch Exception _))
    ;; Query Kitty protocol + enable bracketed paste + hide cursor
    (.print out kitty-query)
    (.print out paste-enable)
    (.print out hide-cursor)
    (.flush out)
    ;; Background reader thread reads from /dev/tty directly
    (let [t (Thread.
             #(read-input-loop on-input running-atom)
             "tui-input-reader")]
      (.setDaemon t true)
      (.start t)
      (reset! reader-thread-atom t))
    this)

  (stop! [_this]
    (reset! running-atom false)
    ;; Restore terminal state before writing cursor-restore sequences
    (run-stty! "sane")
    (when @kitty-active-atom
      (.print out kitty-disable)
      (reset! kitty-active-atom false))
    (.print out paste-disable)
    (.print out show-cursor)
    (.flush out))

  (write! [_this data]
    (.print out data)
    (.flush out))

  (columns [_this] @cols-atom)
  (rows    [_this] @rows-atom))

(defn create-process-terminal
  "Create a ProcessTerminal backed by System/out.
   Dimensions default to 80×24; pass {:cols n :rows n} to override."
  ([] (create-process-terminal {}))
  ([{:keys [cols rows] :or {cols 80 rows 24}}]
   (->ProcessTerminal
    System/out
    (atom cols)
    (atom rows)
    (atom false)
    (atom false)
    (atom nil))))
