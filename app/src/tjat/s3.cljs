(ns tjat.s3)


(defn file-is-cached+ [{:keys [bucket]} {:keys [file-hash]}]
  (let [s3-client nil]
    (-> (js/Promise.resolve)
        (.getSignedUrlPromise s3-client
                              "headObject"
                              #js {:Bucket  bucket
                                   :Key     file-hash
                                   :Expires 300})
        (.then (fn [signed-url]
                 (js/fetch signed-url #js{:method "HEAD"})))
        (.then (fn [response]
                 (let [status (.-status response)]

                   (cond (= 404 status)
                         false

                         (= 200 status)
                         true

                         :else
                         (throw (ex-info "Error checking cache" {:status status})))))))))