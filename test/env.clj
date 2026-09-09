(ns env (:refer-clojure :exclude [get]))

;; Test-only replacement for Dvergr's injected config namespace. No host env.
(defn get [_] "offline-fixture")
