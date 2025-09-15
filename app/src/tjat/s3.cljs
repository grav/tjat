(ns tjat.s3
  (:require ["aws-sdk" :as aws]))

(defn create-client [{:keys [access-key-id secret-access-key endpoint]}]
  (aws/S3. #js{:accessKeyId      access-key-id
               :secretAccessKey  secret-access-key
               :endpoint         endpoint
               :signatureVersion "v4"}))

(defn get-file+ [{:keys [bucket] :as s3} {:keys [file-hash]}]
  (let [s3-client (create-client s3)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (.getSignedUrlPromise s3-client
                                       "getObject"
                                       #js {:Bucket  bucket
                                            :Key     file-hash
                                            :Expires 300})))
        (.then (fn [signed-url]
                 (js/fetch signed-url)))
        (.then (fn [response]
                 (let [status (.-status response)]
                   (cond
                     (= 200 status)
                     response

                     (= 404 status)
                     nil

                     :else
                     (throw (ex-info "Error getting file from cache" {:status status})))))))))




(defn file-exists+ [{:keys [bucket] :as s3} {:keys [file-hash]}]
  (let [s3-client (create-client s3)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (.getSignedUrlPromise s3-client
                                       "headObject"
                                       #js {:Bucket  bucket
                                            :Key     file-hash
                                            :Expires 300})))
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