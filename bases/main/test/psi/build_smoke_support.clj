(ns psi.build-smoke-support)

(def build-lock (Object.))

(defmacro with-build-lock
  [& body]
  `(locking build-lock
     ~@body))
