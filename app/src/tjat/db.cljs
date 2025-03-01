(ns tjat.db
  (:require ["@instantdb/react"
             :as instantdb
             :refer [id i init InstaQLEntity]]))

(defn app []
  (let [db (instantdb/init #js{:app-id "bb63ab72-4a57-4235-b8dc-93c07b60d2b3"})
        _ (.subscribeQuery #js{:todos {}}
                           (fn [r]
                             (if (.-error r)
                               (js/console.warn (.-error r))
                               (js/console.log (.-data r)))))]
    (fn []
      [:div "DB Test"])))
