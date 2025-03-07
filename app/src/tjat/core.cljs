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



(defn response-view [{:keys [request-time id text] :as x}]
  (when id
    [:div
     [:div [:i (some-> request-time
                       js/Date.parse
                       (js/Date.)
                       str)]]
     [:div [:p
            {:dangerouslySetInnerHTML
             {:__html (.makeHtml
                        ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
                        (doto (showdown/Converter.)
                          (.setFlavor "github")) text)}}]]]))

(defn response-tabs [{:keys     [selected-response-id]
                      chat-id   :id
                      responses :responses}
                     {:keys [on-response-select]}]
  [:div {:style {:display :flex}}
   (for [[i {:keys [model id] :as v}] (map vector (range) (sort-by :time responses))]
     ^{:key id} [:div [:div
                       {:style    {:padding 10}
                        :on-click #(on-response-select [chat-id id])}
                       [:div {:style {:background-color (when (or (= selected-response-id id)
                                                                  (and (nil? selected-response-id)
                                                                       (zero? i))) :lightgray)}}
                        (name model)]]])])


(defn chat-menu [{:keys [chats selected-chat-id selections]}
                 {:keys [on-chat-select]
                  :as   handlers}]
  (let [{selected-response-id selected-chat-id} selections
        {:keys [responses]
         :as   chat} (->> chats
                          (filter (comp #{selected-chat-id} :id))
                          util/single)]
    [:div {:style {:display :flex
                   :padding 10}}
     [:div (for [{:keys [id text]} (reverse chats)]
             ^{:key id} [:div {:on-click #(on-chat-select id)}
                         [:div {:style {:font-weight      900
                                        :background-color (when (= selected-chat-id id) :lightgray)
                                        :padding          10
                                        :white-space      :nowrap
                                        :width            100
                                        :overflow         :hidden
                                        :text-overflow    :ellipsis}} text]
                         (when (= selected-chat-id id))])]
     [:div {:style {:padding 10}}
      [response-tabs (assoc chat :selected-response-id selected-response-id) handlers]
      [:hr]
      [response-view (or (->> responses (filter (comp #{selected-response-id} :id)) seq)
                         (first responses))]]]))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db]} !state]
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text model api-keys loading chats selected-chat-id]
             :or   {model    (or
                               (some-> (js/localStorage.getItem "tjat-model") keyword)
                               (first all-models))
                    api-keys api-keys-persisted}} @!state
            {:keys [provider]} (allem.core/make-config {:model model})
            selected-chat (->> chats
                               (filter (comp #{selected-chat-id} :id))
                               util/single)]
        [:div
         #_[:div
            [:pre 'db? (str " " (some? db))]
            [:pre (util/spprint @!state)]]
         [:h1 "Tjat!"]
         [:div
          "Model: "
          [:select
           {:value     (name model)
            :on-change (fn [e]
                         (let [model (.-value (.-target e))]
                           (js/localStorage.setItem "tjat-model" model)
                           (swap! !state assoc :model (keyword model))))}
           (for [p all-models]
             ^{:key (name p)}
             [:option {:id (name p)}
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
                                     :value   (get api-keys provider)}]]])

          [:p
           [:textarea
            {:style {:width "100%"}
             :rows  10
             :value text
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:p
           {:style {:height 50}}
           [:button {:on-click #(do
                                  (swap! !state assoc :loading true)
                                  (let [chat-id (if (not= text (:text selected-chat))
                                                  (if db
                                                    (let [chat-id (instantdb/id)
                                                          tx (.-tx db)
                                                          new-chat (aget (.-chats ^js/Object tx) chat-id)]
                                                      (.transact db
                                                                 (.update new-chat #js{:text text}))
                                                      chat-id)
                                                    ;; local-only
                                                    (let [id (str (random-uuid))]
                                                      (swap! !state update :chats (fn [vs]
                                                                                    (conj (or vs [])
                                                                                          {:id id :text text})))
                                                      id))

                                                  selected-chat-id)
                                        start-time (js/Date.)]
                                    (-> (do-request! {:message  text
                                                      :model    model
                                                      :api-keys api-keys})
                                        (.then (fn [v]
                                                 (let [response-id (or (when db
                                                                         (instantdb/id))
                                                                       (str (random-uuid)))
                                                       end-time (js/Date.)
                                                       response {:text          v
                                                                 :model         (name model)
                                                                 :request-time  start-time
                                                                 :response-time end-time}]
                                                   (if db
                                                     (do
                                                       (.transact db (let [new-response (aget (.-responses ^js/Object (.-tx db)) response-id)]
                                                                       (-> new-response
                                                                           (.update (clj->js response))
                                                                           (.link #js {:chats chat-id}))))
                                                       (swap! !state (fn [s]
                                                                       (-> s

                                                                           (assoc-in [:selections chat-id] response-id)
                                                                           (assoc :loading false)
                                                                           (assoc :selected-chat-id chat-id)))))
                                                     ;; local-only
                                                     (let [chat-idx (->> (map vector (range) (map :id (:chats @!state))) ;; weird that 'chat' isn't updated?
                                                                         (filter (fn [[_ id]]
                                                                                   (= id chat-id)))
                                                                         util/single
                                                                         first)]
                                                       (swap! !state (fn [s]
                                                                       (-> s
                                                                           (update-in [:chats chat-idx :responses] (fn [vs]
                                                                                                                     (conj (or vs [])
                                                                                                                           (assoc response
                                                                                                                             :id response-id))) [])
                                                                           (assoc-in [:selections chat-id] response-id)
                                                                           (assoc :loading false)
                                                                           (assoc :selected-chat-id chat-id))))
                                                       100))))))))}


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
          [ui/error-boundary
           [chat-menu @!state
            {:on-chat-select     (fn [selected-chat-id]
                                   (swap! !state assoc
                                          :selected-chat-id selected-chat-id
                                          ;; TODO - this might be a bit aggressive ...
                                          :text (->> chats
                                                     (filter (comp #{selected-chat-id} :id))
                                                     util/single
                                                     :text)))
             :on-response-select (fn [[selected-chat-id id]]
                                   (swap! !state assoc-in [:selections selected-chat-id] id))}]]]]))))
#_(defn testit []
    [:div
     [upload/drop-zone]])

(defn instantdb-view []
  (let [!ref-state (atom nil)]
    (r/create-class
      {:component-did-mount    (fn []
                                 (let [instantdb-app-id-persisted (js/localStorage.getItem "instantdb-app-id")]
                                   (when (seq instantdb-app-id-persisted)
                                     (reset! !ref-state
                                             (db/init-instant-db {:app-id        instantdb-app-id-persisted
                                                                  :subscriptions {:chats {:responses {}}}
                                                                  :!state        !state})))
                                   (swap! !state assoc :instantdb-app-id instantdb-app-id-persisted)))
       :component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys [instantdb-app-id]} @!state
                                       {:keys [unsubscribe db]} @!ref-state]
                                   #_[:pre (util/spprint @!ref-state)]
                                   [:div {:style {:max-width 800}}
                                    [:details

                                     [:summary "Settings"]
                                     [:div {:style {:display :flex}}
                                      "InstantDB app-id:"
                                      [ui/secret-edit-field {:on-save (fn [s]
                                                                        (and
                                                                          (or (or (empty? s)
                                                                                  (seq instantdb-app-id))
                                                                              (js/confirm "Enabling InstantDB will loose all local changes"))
                                                                          (do
                                                                            (when unsubscribe
                                                                              (unsubscribe))
                                                                            (if (seq s)
                                                                              (do
                                                                                (js/localStorage.setItem "instantdb-app-id" s)
                                                                                (reset! !ref-state
                                                                                        (db/init-instant-db
                                                                                          {:app-id        s
                                                                                           :subscriptions {:chats {:responses {}}}
                                                                                           :!state        !state}))
                                                                                (swap! !state assoc :instantdb-app-id s))
                                                                              (do
                                                                                (js/localStorage.removeItem "instantdb-app-id")
                                                                                (reset! !ref-state nil)
                                                                                (swap! !state dissoc :chats :instantdb-app-id))))))

                                                             :value   instantdb-app-id}]]]
                                    [app {:db db} !state]]))})))

(defn ^:dev/after-load main []
  (.render root (r/as-element [instantdb-view])))

