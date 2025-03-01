(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb
             :refer [id i init InstaQLEntity]]
            [reagent.core :as r]
            [tjat.ui :as ui]))

;; https://www.instantdb.com/docs/start-vanilla

(defn init-instant-db [{:keys [app-id on-error on-success]
                        :or {on-error #(js/console.warn (.-error %))
                             on-success #(js/console.log (.-data %))}}]
  (let [db (instantdb/init #js{:appId app-id})]
    {:db db
     :unsubscribe (.subscribeQuery db #js{:todos #js{}}
                                   (fn [r]
                                     (if (.-error r)
                                       (on-error r)
                                       (on-success r))))}))



(defn app []
  (let [!r-state (r/atom nil)
        !state (atom nil)]
    (r/create-class
      {:component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render (fn []
                         (let [{:keys [todos instant-db-app-id]} @!r-state
                               {:keys [unsubscribe db]} @!state]
                           [:div "DB Test"
                            [:div {:style {:display        :flex}}
                             "InstantDB app-id:"
                             [ui/secret-edit-field {:on-save (fn [s]

                                                               (when unsubscribe
                                                                 (unsubscribe))
                                                               (swap! !r-state assoc
                                                                      :instant-db-app-id s
                                                                      :todos nil)
                                                               (when (seq s)
                                                                 (reset! !state
                                                                         (init-instant-db
                                                                           {:app-id     s
                                                                            :on-success (fn [r]
                                                                                          (js/console.log (.-data r))
                                                                                          (swap! !r-state assoc :todos (js->clj
                                                                                                                         (aget (.-data r) "todos")
                                                                                                                         :keywordize-keys true)))}))))

                                                    :value   instant-db-app-id}]]
                            [:ul
                             (for [{:keys [id text]} todos]
                               ^{:key id}
                               [:li text])]
                            [:button {:on-click #(.transact db
                                                            (let [t (aget (.-todos ^js/Object (.-tx db)) (instantdb/id))]
                                                              (.update t #js{:text (rand-nth ["foo" "bar" "hello" "ding dong" ":-D"])})))}

                             "Add todo"]]))})))
