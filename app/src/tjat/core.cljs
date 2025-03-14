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
            ["@instantdb/core" :as instantdb]
            ["algoliasearch" :as algolia]
            ["@supabase/supabase-js" :as supabase]))

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


(defn chat-menu []
  (let [!state (r/atom nil)]
    (fn [{:keys [chats selected-chat-id selections]
          {search-response-ids :responses
           search-chat-ids     :chats
           :as search-results} :search-results}
         {:keys [on-chat-select]
          :as   handlers}]
      (let [{selected-response-id selected-chat-id} selections
            {:keys [hover timer resting]} @!state
            {:keys [responses]
             :as   chat} (->> chats
                              (filter (comp #{selected-chat-id} :id))
                              util/single)]
        [:div {:style {:display :flex
                       :padding 10}}
         [:div (for [{:keys [id text]} (reverse chats)]
                 ^{:key id} [:div {:on-click #(on-chat-select id)
                                   :style {:display (when (and search-results
                                                               (nil? (search-chat-ids id)))
                                                      :none)}}
                             [:div {:style          {:font-weight      900
                                                     :background-color (or
                                                                         (when (= hover id) :lightblue)
                                                                         (when (= selected-chat-id id) :lightgray))
                                                     :padding          10
                                                     :white-space      (when (not= resting id) :nowrap)
                                                     :width            150
                                                     :overflow-x       :hidden
                                                     :overflow-y       :auto
                                                     :text-overflow    (when (not= resting id) :ellipsis)
                                                     :max-height       200}
                                    :on-mouse-enter #(swap! !state assoc :hover id :timer (js/setTimeout
                                                                                            (fn []
                                                                                              (swap! !state
                                                                                                     assoc :resting id))
                                                                                            300))
                                    :on-mouse-leave (fn [_]
                                                      (when timer
                                                        (js/clearTimeout timer))
                                                      (swap! !state dissoc :hover :timer :resting))}
                              text]
                             (when (= selected-chat-id id))])]
         [:div {:style {:padding 10}}
          [response-tabs (assoc chat :selected-response-id selected-response-id) handlers]
          [:hr]
          (when (or (nil? search-results)
                    (search-response-ids selected-response-id))
            [response-view (or (->> responses (filter (comp #{selected-response-id} :id)) seq)
                               (first responses))])]]))))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db supabase-client]} !state]
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
            [:div [:p "Provider: "
                   [:b (name provider)]]
             [:div {:style {:display :flex}}
              [:div "Api key: "]
              [ui/edit-field {:on-save      (fn [k]
                                              (let [api-keys (if (seq k)
                                                               (merge api-keys
                                                                      {provider k})
                                                               (dissoc api-keys provider))]
                                                (swap! !state assoc :api-keys api-keys)
                                                (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                              :value (get api-keys provider)}]]])

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
           [:button {:disabled (empty? text)
                     :on-click #(do
                                  (swap! !state assoc :loading true)
                                  (let [chat-id (if (not= text (:text selected-chat))
                                                  (if db
                                                    (let [chat-id (instantdb/id)
                                                          tx (.-tx db)
                                                          new-chat (aget (.-chats ^js/Object tx) chat-id)]
                                                      (.transact db
                                                                 (.update new-chat #js{:text text}))
                                                      (when supabase-client
                                                        (-> (-> ^js/Object supabase-client
                                                                (.from "chats")
                                                                (.insert #js{:id      chat-id
                                                                             :text    text}))
                                                            (.then js/console.log)
                                                            (.catch js/console.error)))
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
                                                       (when supabase-client
                                                         (-> (-> ^js/Object supabase-client
                                                                 (.from "responses")
                                                                 (.insert #js{:id      response-id
                                                                              :chat_id chat-id
                                                                              :text    v}))
                                                             (.then js/console.log)
                                                             (.catch js/console.error)))

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
                                                       100)))))
                                        (.catch (fn [e]
                                                  (js/alert (str "Error: Got status " (:status (ex-data e))
                                                                 " from API"))
                                                  (swap! !state assoc :loading false))))))}


            "submit"]
           (when loading
             [ui/spinner])]
          (when supabase-client
            [:div {:style {:display :flex}}
             "Search: "
             [ui/search
              {:supabase-client supabase-client
               :on-search       (fn [res]
                                  (swap! !state assoc :search-results res))}]])
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

(defn instant-db-error-handler [res]
  (let [e (.-error res)]
    (js/console.error e)
    (js/alert (.-message e))))

(def supabase-url "https://rjiidqdhadwocugptyjj.supabase.co")

(defn instantdb-view []
  (let [!ref-state (atom nil)]
    (r/create-class
      {:component-did-mount    (fn []
                                 (let [instantdb-app-id-persisted (js/localStorage.getItem "instantdb-app-id")
                                       supabase-key (js/localStorage.getItem "supabase-key")
                                       algolia-app-id (js/localStorage.getItem "algolia-app-id")
                                       algolia-api-key (js/localStorage.getItem "algolia-api-key")]
                                   (when (seq instantdb-app-id-persisted)
                                     (swap! !ref-state
                                            merge
                                            (db/init-instant-db {:app-id        instantdb-app-id-persisted
                                                                 :subscriptions {:chats {:responses {}}}
                                                                 :!state        !state
                                                                 :on-error      instant-db-error-handler})))
                                   (when (and (seq algolia-app-id)
                                              (seq algolia-api-key))
                                     (swap! !ref-state
                                            merge
                                            {:algolia-client (algolia/algoliasearch
                                                               algolia-app-id
                                                               algolia-api-key)}))
                                   (swap! !state assoc
                                          :instantdb-app-id instantdb-app-id-persisted
                                          :algolia {:app-id algolia-app-id
                                                    :api-keys algolia-api-key})))
       :component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys [instantdb-app-id]
                                        {algolia-app-id :app-id
                                         algolia-api-key :api-key} :algolia} @!state
                                       {:keys [unsubscribe db algolia-client]} @!ref-state]
                                   #_[:pre (util/spprint @!ref-state)]
                                   [:div {:style {:max-width 800}}
                                    [:details {:open true}
                                     [:summary "Settings"]
                                     [:div {:style {:display :flex}}
                                      [:a {:href   "https://www.instantdb.com/dash"
                                           :target "_blank"}
                                       "InstantDB"]
                                      " app-id: "
                                      [ui/edit-field {:on-save (fn [s]
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
                                                                         (swap! !ref-state
                                                                                merge
                                                                                (db/init-instant-db
                                                                                  {:app-id        s
                                                                                   :subscriptions {:chats {:responses {}}}
                                                                                   :!state        !state
                                                                                   :on-error      instant-db-error-handler}))
                                                                         (swap! !state assoc :instantdb-app-id s))
                                                                       (do
                                                                         (js/localStorage.removeItem "instantdb-app-id")
                                                                         (swap! !ref-state dissoc :db :unsubscribe)
                                                                         (swap! !state dissoc :chats :instantdb-app-id))))))

                                                      :value   instantdb-app-id}]]
                                     [:div
                                      [:div {:style {:display :flex}}
                                       "Algolia App-id: "
                                       [ui/edit-field {:secret? false
                                                       :on-save (fn [s]
                                                                  (if (seq s)
                                                                    (do
                                                                      (js/localStorage.setItem "algolia-app-id" s)
                                                                      (when (seq algolia-api-key)
                                                                        (swap! !ref-state
                                                                               merge
                                                                               {:algoli-client (algolia/algoliasearch
                                                                                                 s algolia-api-key)}))
                                                                      (swap! !state assoc-in [:algolia :app-id] s))
                                                                    (do
                                                                      (js/localStorage.removeItem "algolia-app-id")
                                                                      (swap! !ref-state dissoc :algolia-client)
                                                                      (swap! !state update :algolia dissoc :app-id))))


                                                       :value   algolia-app-id}]]
                                      [:div {:style {:display :flex}}
                                       "Algolia API key: "
                                       [ui/edit-field {:on-save (fn [s]
                                                                  (if (seq s)
                                                                    (do
                                                                      (js/localStorage.setItem "algolia-api-key" s)
                                                                      (when (seq algolia-app-id)
                                                                        (swap! !ref-state
                                                                               merge
                                                                               {:algoli-client (algolia/algoliasearch
                                                                                                 algolia-app-id s)}))
                                                                      (swap! !state assoc-in [:algolia :api-key] s))
                                                                    (do
                                                                      (js/localStorage.removeItem "algolia-api-key")
                                                                      (swap! !ref-state dissoc :algolia-client)
                                                                      (swap! !state update :algolia dissoc :api-key))))


                                                       :value   algolia-api-key}]]
                                      [:button {:on-click (fn []
                                                            #_(let [chat-promises (for [{:keys [id text]} (:chats @!state)]
                                                                                    (-> ^js/Object supabase-client
                                                                                        (.from "chats")
                                                                                        (.insert #js{:id id :text text})))
                                                                    response-promises (for [{:keys   [responses]
                                                                                             chat_id :id}
                                                                                            (:chats @!state)
                                                                                            {:keys [id text]} responses]
                                                                                        (-> ^js/Object supabase-client
                                                                                            (.from "responses")
                                                                                            (.insert #js{:id id :text text :chat_id chat_id})))]


                                                                (-> (js/Promise.all chat-promises)
                                                                    (.then #(js/Promise.all response-promises))
                                                                    (.then #(js/alert "done!")))))



                                                :disabled (or (empty? algolia-api-key)
                                                              (empty? algolia-app-id))}

                                       "Import to Algolia"]]]
                                    [app {:db             db
                                          :algolia-client algolia-client}
                                     !state]]))})))

(defn ^:dev/after-load main []
  (.render root (r/as-element [instantdb-view])))

