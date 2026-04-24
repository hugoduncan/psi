#!/usr/bin/env bb

(ns psi
  (:require
   [psi.launcher-main :as launcher-main]))

(apply launcher-main/-main *command-line-args*)
