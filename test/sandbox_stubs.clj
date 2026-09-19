(ns sandbox-stubs
  "Test-only stand-ins for the extras dvergr adds to borrowed namespaces."
  (:require [clojure.data.xml]
            [dvergr.mail]))

;; dvergr's hardened clojure.data.xml keeps a `text` helper (concatenated text
;; content of a node); babashka's clojure.data.xml has none.
(intern 'clojure.data.xml 'text
        (fn text [node]
          (cond (string? node) node
                (map? node) (apply str (map text (:content node)))
                :else "")))
