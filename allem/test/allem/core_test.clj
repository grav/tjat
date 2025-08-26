(ns allem.core-test
  (:require [allem.util :as util]
            [clojure.test :refer [deftest is testing]]
            [allem.core :as a]
            [clojure.data.json :as json])
  (:import (java.io BufferedInputStream FileInputStream)
           (java.net URLConnection)))



(defn run-test [{:keys [provider model]}]
  (let [{:keys [fn]} (get-in a/config [:providers provider])
        f (resolve fn)]
    (f (cond-> {:msg "Why is the sky blue?"}
               model (assoc :model model)))))

(defn run-all []
  (for [[p _] (get a/config  :providers)]
    {p (run-test {:provider p})}))






(comment
  util/config)





(defn run-with-config [args]
  (let [{:keys [headers url post-process reply-fn body]} (apply-config args)]
    (-> (a/request-with-throw-on-error {:headers headers
                                        :url     url
                                        :method  :post
                                        :body (json/write-str body)})
        :body
        (json/read-str :key-fn keyword)
        reply-fn
        post-process)))

(comment
  (run-with-config
    {:messages ["what's in this image?"
                (FileInputStream. "/Users/grav/Downloads/cat.jpg")]
     :model    :gemini-2.0-flash}
    #_{:message "what's the average of these numbers [1,.6 ,5, 7.8]"
       :model   :gemini-1.5-flash}))


(comment
  (apply str (take 10 (allem.util/input-stream->base64
                        (FileInputStream. "/Users/grav/Downloads/cat.jpg")))))

(comment
  (allem.util/get-api-key :gemini))

(comment
  (run-test {:provider :anthropic}))

(comment
  (run-all))

