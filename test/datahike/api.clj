(ns datahike.api)

;; Test-only placeholder for the room database the sandbox injects: loading
;; dvergr.mail.* offline must not need datahike.
(defn q [& _] (throw (ex-info "datahike not available in offline tests" {})))
