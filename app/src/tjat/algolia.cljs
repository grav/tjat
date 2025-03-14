(ns tjat.algolia
  (:require ["algoliasearch" :as algolia]))

;; https://www.algolia.com/doc/libraries/javascript/v5/
(comment
  (let [app-id "4ORO0SDUY5"
        api-key (js/localStorage.getItem "algolia-api-key")
        index-name "test-index"
        client (when (seq api-key)
                 (algolia/algoliasearch app-id api-key))]
    (-> (.saveObject client
                     (clj->js {:indexName index-name
                               :body      {:text      "what size is the sun?"
                                           :id        4242
                                           :objectID (str 4242) ;; important!
                                           :responses [{:model "model1"
                                                        :text  "quite big"
                                                        :id    4243}
                                                       #_{:model "model2"
                                                          :text  "small compared to other stars"
                                                          :id    4244}]}}))

        (.then js/console.log))))

(comment
  (let [app-id "4ORO0SDUY5"
        api-key (js/localStorage.getItem "algolia-api-key")
        index-name "test-index"
        client (when (seq api-key)
                 (algolia/algoliasearch app-id api-key))]
    (-> (.search client
                 (clj->js {:requests [{:indexName index-name
                                       :query     "sun"}]}))
        (.then js/console.log))))


