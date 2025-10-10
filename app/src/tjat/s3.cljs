(ns tjat.s3
  (:require ["@aws-sdk/client-s3" :as aws-s3]
            ["@aws-sdk/s3-request-presigner" :as aws-presign]))

(defn create-client [{:keys [access-key-id secret-access-key endpoint]}]
  (let [[_ region] (re-matches #"https://s3.(.+).amazonaws.com" endpoint)]
    (aws-s3/S3Client.
      #js{:region           (or region "auto")
          :endpoint         endpoint
          :signatureVersion "v4"
          :credentials      #js {:accessKeyId     access-key-id
                                 :secretAccessKey secret-access-key}})))

(defn get-file+ [{:keys [bucket] :as s3} {:keys [key]}]
  (let [s3-client (create-client s3)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (aws-presign/getSignedUrl s3-client
                                           (aws-s3/GetObjectCommand.
                                             #js {:Bucket  bucket
                                                  :Key     key})
                                           #js {:expiresIn 300})))
        (.then (fn [signed-url]
                 (js/fetch signed-url)))
        (.then (fn [response]
                 (let [status (.-status response)]
                   (cond
                     (= 200 status)
                     response

                     (= 404 status)
                     (throw (ex-info "File is not cached" {:status status}))

                     :else
                     (throw (ex-info "Error getting file from cache" {:status status})))))))))


(defn get-file-open-url+ [{:keys [bucket] :as s3} {:keys [key]}]
  (let [s3-client (create-client s3)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (aws-presign/getSignedUrl s3-client
                                           (aws-s3/GetObjectCommand.
                                                 #js {:Bucket  bucket
                                                      :Key     key})
                                           #js {:expiresIn 300}))))))

(defn file-exists+ [{:keys [bucket] :as s3} {:keys [key]}]
  (let [s3-client (create-client s3)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (aws-presign/getSignedUrl s3-client
                                           (aws-s3/HeadObjectCommand.
                                             #js {:Bucket bucket
                                                  :Key    key})
                                           #js{:expiresIn 300})))
        (.then (fn [signed-url]
                 (js/fetch signed-url #js{:method "HEAD"})))
        (.then (fn [response]
                 (let [status (.-status response)]

                   (cond (= 404 status)
                         false

                         (= 200 status)
                         true

                         :else
                         (throw (ex-info "Error checking if file exists" {:status status})))))))))

(defn upload+ [{:keys [bucket] :as s3} {:keys [file key file-type]}]
  (let [s3-client (create-client s3)
        file-type (or file-type (.-type file))]
    (-> (js/Promise.resolve)
        (.then #(aws-presign/getSignedUrl s3-client
                                          (aws-s3/PutObjectCommand.
                                            #js {:Bucket      bucket
                                                 :Key         key
                                                 :ContentType file-type})
                                          #{:expiresIn     300}))
        (.then (fn [signed-url]
                 (js/console.log signed-url)
                 (js/fetch signed-url
                           #js {:method  "PUT"
                                :headers #js {"Content-Type" file-type}
                                :body    file}))))))

(defn copy+ [s3 {:keys [source-bucket destination-bucket source-key destination-key file-type]}]
  (let [source-client (assoc s3 :bucket source-bucket)
        dest-client (assoc s3 :bucket destination-bucket)]
    (-> (js/Promise.resolve)
        (.then #(get-file+ source-client {:key source-key}))
        (.then #(.blob %))
        (.then #(upload+ dest-client {:file % :key destination-key :file-type file-type})))))

