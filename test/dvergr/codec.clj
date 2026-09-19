(ns dvergr.codec)

;; Test-only placeholders. These tests verify intake/provenance behavior, not
;; Dvergr's native HTML implementation. HTML tests redefine the functions.
(defn strip-tags [_] (throw (ex-info "HTML codec not installed in this test" {})))
(defn decode-entities [text] text)
(defn url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
(defn base64-encode [s] (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str s) "UTF-8")))
