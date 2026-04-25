(ns charm.core
  "Compatibility shim for charm.clj >= 0.2.x.

   In v0.1.x charm.core was the upstream umbrella namespace. From v0.2.x it was
   removed; the same symbols now live in their canonical sub-namespaces. This
   shim re-exports every symbol used by psi/tui so the rest of the codebase
   does not need to be touched."
  (:require
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as prog]
   [charm.style.core :as style]
   [charm.terminal :as term]))

;; ---------------------------------------------------------------------------
;; charm.program
;; ---------------------------------------------------------------------------

(def cmd          prog/cmd)
(def batch        prog/batch)
(def sequence-cmds prog/sequence-cmds)
(def quit-cmd     prog/quit-cmd)
(def run          prog/run)
(def run-async    prog/run-async)

;; ---------------------------------------------------------------------------
;; charm.terminal
;; ---------------------------------------------------------------------------

(def create-terminal term/create-terminal)
(def get-size        term/get-size)

;; ---------------------------------------------------------------------------
;; charm.message
;; ---------------------------------------------------------------------------

(def key-press   msg/key-press)
(def window-size msg/window-size)
(def quit        msg/quit)
(def error       msg/error)
(def mouse       msg/mouse)
(def focus       msg/focus)
(def blur        msg/blur)

(def key-press?   msg/key-press?)
(def window-size? msg/window-size?)
(def quit?        msg/quit?)
(def error?       msg/error?)
(def mouse?       msg/mouse?)

(def key-match? msg/key-match?)
(def ctrl?      msg/ctrl?)
(def alt?       msg/alt?)
(def shift?     msg/shift?)

;; ---------------------------------------------------------------------------
;; charm.style.core  — styling / rendering / colours
;; ---------------------------------------------------------------------------

(def style  style/style)
(def render style/render)
(def styled style/styled)

(def rgb     style/rgb)
(def hex     style/hex)
(def ansi    style/ansi)
(def ansi256 style/ansi256)

(def black   style/black)
(def red     style/red)
(def green   style/green)
(def yellow  style/yellow)
(def blue    style/blue)
(def magenta style/magenta)
(def cyan    style/cyan)
(def white   style/white)

(def normal-border  style/normal-border)
(def rounded-border style/rounded-border)
(def thick-border   style/thick-border)
(def double-border  style/double-border)

(def join-horizontal style/join-horizontal)
(def join-vertical   style/join-vertical)

;; ---------------------------------------------------------------------------
;; charm.components.text-input
;; ---------------------------------------------------------------------------

(def text-input       text-input/text-input)
(def text-input-init  text-input/text-input-init)
(def text-input-update text-input/text-input-update)
(def text-input-view  text-input/text-input-view)
(def text-input-value text-input/value)
(def text-input-set-value text-input/set-value)
(def text-input-focus text-input/focus)
(def text-input-blur  text-input/blur)
(def text-input-reset text-input/reset)

(def echo-normal   text-input/echo-normal)
(def echo-password text-input/echo-password)
(def echo-none     text-input/echo-none)
