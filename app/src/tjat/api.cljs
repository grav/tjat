(ns tjat.api
  (:require [allem.core]
            [allem.util :as util]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]))

(defn do-request! 
  "Send a message to the LLM API"
  [{:keys [message model api-keys]}]
  (let [config (allem.core/make-config
                 {:model    model
                  :api-keys api-keys})
        {:keys [reply-fn headers url body]} (allem.core/apply-config
                                              (assoc config
                                                :message message))]
    (-> (http/send! client {:method  :post
                            :url     url
                            :headers headers
                            :body    (js/JSON.stringify (clj->js body))})
        (.then #(get % :body))
        (.then js/JSON.parse)
        (.then #(js->clj % :keywordize-keys true))
        (.then reply-fn))))