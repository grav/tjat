(ns tjat.core
  (:require ["react-dom/client" :as react-dom]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            ["showdown" :as showdown]
            [tjat.db :as db]
            [tjat.ui :as ui]
            ["@instantdb/core" :as instantdb]))

(defonce root (react-dom/createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))

(defn do-request! [{:keys [message model api-keys]}]
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

(comment
  (-> (do-request! {:message "yolo"
                    :model   :gpt-4o})
      (.then util/spprint)
      (.then println)))



(defn response-view [{:keys [time id response]}]
  (when id
    [:div
     [:div [:i (str time)]]
     [:div [:p
            {:dangerouslySetInnerHTML
             {:__html (.makeHtml
                        ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
                        (doto (showdown/Converter.)
                          (.setFlavor "github")) response)}}]]]))

(defn response-tabs [{:keys [selected-chat selections chats]}
                     {:keys [on-response-select]}]
  (let [responses (get chats selected-chat)
        {selected-response-id selected-chat} selections]
    [:div {:style {:display :flex}}
     (for [[i {:keys [model id] :as v}] (map vector (range) (sort-by :time responses))]
       ^{:key i} [:div [:div
                        {:style {:padding 10}
                         :on-click #(on-response-select [selected-chat id])}
                        [:div {:style {:background-color (when (= selected-response-id id) :lightgray)}}
                         (name model)]]])]))


(defn chat-menu [{:keys [chats selected-chat selections]
                  :as state}
                 {:keys [on-chat-select]
                  :as handlers}
                 !state]
  (let [{selected-response-id selected-chat} selections]
    [:div {:style {:display :flex
                   :padding 10}}
     [:div (for [[k _] chats]
             ^{:key k} [:div {:on-click #(on-chat-select k)}
                        [:div {:style {:font-weight      900
                                       :background-color (when (= selected-chat k) :lightgray)
                                       :padding          10
                                       :width        100}} k]
                        (when (= selected-chat k))])]
     [:div {:style {:padding 10}}
      [response-tabs state handlers]
      [:hr]
      [response-view (->> (get chats selected-chat)
                          (filter (comp #{selected-response-id} :id))
                          util/single)]]]))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db]} !state]
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text answer model api-keys loading]
             :or   {model    (first all-models)
                    api-keys api-keys-persisted}} @!state
            {:keys [provider]} (allem.core/make-config {:model model})]
        [:div
         [:pre (util/spprint @!state)]
         [:h1 "Tjat!"]
         [:div
          "Model: "
          [:select
           {:value     (name model)
            :on-change #(swap! !state assoc :model (keyword (.-value (.-target %))))}
           (for [p all-models]
             ^{:key (name p)}
             [:option {:id       (name p)}
              (name p)])]
          (when provider
            [:div [:p (str "Provider: ")
                   [:b (name provider)]]
             [:div {:style {:display :flex}}
              [:div "Api key:"]
              [ui/secret-edit-field {:on-save (fn [k]
                                                (let [api-keys (if (seq k)
                                                                 (merge api-keys
                                                                        {provider k})
                                                                 (dissoc api-keys provider))]
                                                  (swap! !state assoc :api-keys api-keys)
                                                  (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                                     :value        (get api-keys provider)}]]])

          [:p
           [:textarea
            {:value text
             :rows  10
             :cols  100
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:p
           {:style {:height 50}}
           [:button {:on-click #(do
                                  (swap! !state assoc :loading true)
                                  (-> (do-request! {:message  text
                                                    :model    model
                                                    :api-keys api-keys})
                                      (.then (fn [v]
                                               (print v)
                                               (let [chat-id (instantdb/id)
                                                     response-id (instantdb/id)
                                                     tx (.-tx db)]
                                                 (.transact db
                                                            #js [(let [new-chat (aget (.-chats ^js/Object tx) chat-id)]
                                                                   (.update new-chat #js{:text text}))
                                                                 (let [new-response (aget (.-responses ^js/Object tx) response-id)]
                                                                   (-> new-response
                                                                       (.update #js{:text  v
                                                                                    :model (name model)})
                                                                       (.link #js {:chats chat-id})))])

                                                 #_(swap! !state update-in [:chats text] conj {:id       chat-id
                                                                                               :model    model
                                                                                               :time     (js/Date.)
                                                                                               :response v})
                                                 ;; TODO: potentially do this in one 'swap'
                                                 (swap! !state assoc-in [:selections text] chat-id)
                                                 (swap! !state assoc
                                                        :loading false
                                                        :selected-chat text))))))}

            "submit"]
           (when loading
             [:svg.spinner
              {:width   "20"
               :height  "20"
               :viewBox "0 0 50 50"}
              [:circle.spinner-circle
               {:cx           "25"
                :cy           "25"
                :r            "20"
                :fill         "none"
                :stroke       "#007bff"
                :stroke-width "4"}]])]
          #_[chat-menu @!state
             {:on-chat-select     (fn [selected-chat]
                                    (swap! !state assoc
                                           :selected-chat selected-chat
                                           ;; TODO - this might be a bit aggressive ...
                                           :text selected-chat))
              :on-response-select (fn [[selected-chat id]]
                                    (swap! !state assoc-in [:selections selected-chat] id))}]]]))))
#_(defn testit []
    [:div
     [upload/drop-zone]])

(defn instantdb-view []
  (let [instantdb-app-id-persisted (js/localStorage.getItem "instantdb-app-id")
        !ref-state (atom (when (seq instantdb-app-id-persisted)
                           (db/init-instant-db {:app-id        instantdb-app-id-persisted
                                                :subscriptions [:chats :responses]
                                                :!state        !state})))]
    (swap! !state assoc :instantdb-app-id instantdb-app-id-persisted)
    (r/create-class
      {:component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys [instantdb-app-id]} @!state
                                       {:keys [unsubscribe db]} @!ref-state]
                                   [:pre (util/spprint @!ref-state)]
                                   [:div
                                    [:h3 "DB Test"]
                                    [:div {:style {:display :flex}}
                                     "InstantDB app-id:"
                                     [ui/secret-edit-field {:on-save (fn [s]
                                                                       (when unsubscribe
                                                                         (unsubscribe))
                                                                       (swap! !state assoc
                                                                              ;; TODO - use unsubscriptions to reset state
                                                                              :todos nil)
                                                                       (js/localStorage.setItem "instantdb-app-id" s)
                                                                       (when (seq s)
                                                                         (reset! !ref-state
                                                                                 (db/init-instant-db
                                                                                   {:app-id        s
                                                                                    :subscriptions [:chat]
                                                                                    :!state        !state}))))

                                                            :value   instantdb-app-id}]]
                                    [app {:db db} !state]
                                    #_[:div [:ul
                                             (for [{:keys [id text]} todos]
                                               ^{:key id}
                                               [:li text])]
                                       [:button {:on-click #(.transact db
                                                                       (let [t (aget (.-todos ^js/Object (.-tx db)) (instantdb/id))]
                                                                         (.update t #js{:text (rand-nth ["foo" "bar" "hello" "ding dong" ":-D"])})))}

                                        "Add item"]]]))})))
(defn db-test []
  [:div
   [instantdb-view]])

(defn ^:dev/after-load main []
  (.render root (r/as-element [db-test])))
