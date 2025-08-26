(ns allem.io
  (:require [clojure.java.io :as io]))

(defn input-stream->byte-array [is]
  (with-open [xout (java.io.ByteArrayOutputStream.)]
    (io/copy is xout)
    (.toByteArray xout)))

(defn input-stream->base64
  "Convert an InputStream to a Base64 encoded string"
  [input-stream]
  (let [e (java.util.Base64/getEncoder)]
    (.encodeToString e (input-stream->byte-array input-stream))))