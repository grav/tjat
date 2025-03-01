(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb
             :refer [id i init InstaQLEntity]]
            [reagent.core :as r]))

;; https://www.instantdb.com/docs/start-vanilla

(defn app []
  (let [db (instantdb/init #js{:appId "bb63ab72-4a57-4235-b8dc-93c07b60d2b3"})
        !state (r/atom nil)
        ;; TODO - this runs on every mount, need unsubscribe!
        _ (.subscribeQuery db #js{:todos #js{}}
                           (fn [r]
                             (if (.-error r)
                               (js/console.warn (.-error r))
                               (swap! !state assoc :todos (js->clj (aget (.-data r) "todos") :keywordize-keys true)))))]
    (fn []
      (let [{:keys [todos]} @!state]
        [:div "DB Test"
         [:ul
          (for [{:keys [id text]} todos]
            ^{:key id}
            [:li text])]
         [:button {:on-click #(.transact db
                                         (let [t (aget (.-todos (.-tx db)) (instantdb/id))]
                                           (.update t #js{:text (rand-nth ["foo" "bar" "hello" "ding dong" ":-D"])})))}

          "Add todo"]]))))
