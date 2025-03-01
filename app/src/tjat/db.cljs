(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb
             :refer [id i init InstaQLEntity]]
            [reagent.core :as r]
            [tjat.ui :as ui]))

;; https://www.instantdb.com/docs/start-vanilla
;; we don't need no hooks!

(defn init-instant-db [{:keys [app-id on-error on-success]
                        :or   {on-error   #(js/console.warn (.-error %))
                               on-success #(js/console.log (.-data %))}}]
  (let [db (instantdb/init #js{:appId app-id})]
    {:db          db
     :unsubscribe (.subscribeQuery db #js{:todos #js{}}
                                   (fn [r]
                                     (if (.-error r)
                                       (on-error r)
                                       (on-success r))))}))

(defn make-instantdb-success-handler [!r-state]
  (fn [r]
    (js/console.log (.-data r))
    (swap! !r-state assoc :todos (js->clj
                                   (aget (.-data r) "todos")
                                   :keywordize-keys true))))

(defn app []
  (let [instantdb-app-id-persisted (js/localStorage.getItem "instantdb-app-id")
        !r-state (r/atom {:instantdb-app-id instantdb-app-id-persisted})
        !state (atom (when (seq instantdb-app-id-persisted)
                       (init-instant-db {:app-id     instantdb-app-id-persisted
                                         :on-success (make-instantdb-success-handler !r-state)})))]
    (r/create-class
      {:component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys [todos instantdb-app-id]} @!r-state
                                       {:keys [unsubscribe db]} @!state]
                                   [:div
                                    [:h3 "DB Test"]
                                    [:div {:style {:display :flex}}
                                     "InstantDB app-id:"
                                     [ui/secret-edit-field {:on-save (fn [s]
                                                                       (when unsubscribe
                                                                         (unsubscribe))
                                                                       (swap! !r-state assoc
                                                                              :instantdb-app-id s
                                                                              :todos nil)
                                                                       (js/localStorage.setItem "instantdb-app-id" s)
                                                                       (when (seq s)
                                                                         (reset! !state
                                                                                 (init-instant-db
                                                                                   {:app-id     s
                                                                                    :on-success (make-instantdb-success-handler !r-state)}))))

                                                            :value   instantdb-app-id}]]
                                    [:ul
                                     (for [{:keys [id text]} todos]
                                       ^{:key id}
                                       [:li text])]
                                    [:button {:on-click #(.transact db
                                                                    (let [t (aget (.-todos ^js/Object (.-tx db)) (instantdb/id))]
                                                                      (.update t #js{:text (rand-nth ["foo" "bar" "hello" "ding dong" ":-D"])})))}

                                     "Add item"]]))})))
