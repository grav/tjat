(ns tjat.core
  (:require ["react-dom/client" :as react-dom]
            [allem.util :as util]
            [allem.platform :as platform]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            ["showdown" :as showdown]
            [tjat.db :as db]
            [tjat.ui :as ui]
            [tjat.ui-components :as ui-components]
            [tjat.algolia :as a]
            ["@instantdb/core" :as instantdb]
            ["algoliasearch" :as algolia]
            [clojure.edn]))

(defonce root (react-dom/createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))

;; API request handlers
(defn make-api-request! [{:keys [message model api-keys]}]
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

(defn do-request! [params]
  (make-api-request! params))

(comment
  (-> (do-request! {:message "yolo"
                    :model   :gpt-4o})
      (.then util/spprint)
      (.then println)))

;; Markdown extensions for think blocks
(def think-start-extension
  (clj->js
    {:type "lang"
     :regex #"<think>"
     :replace "<small><details><summary><i>Think \uD83D\uDCAD</i></summary><i>"}))

(def think-end-extension
  (clj->js
    {:type "lang"
     :regex #"</think>"
     :replace "</i><hr></details></small>"}))

;; Event handlers
(defn handle-model-selection [e !state]
  (let [selected-models (->> (.-selectedOptions (.-target e))
                             (map #(.-value %))
                             (map keyword)
                             set)]
    (swap! !state assoc :models selected-models)
    (js/localStorage.setItem "tjat-models" (pr-str selected-models))))

(defn handle-text-change [e !state]
  (swap! !state assoc :text (.-value (.-target e))))

(defn create-new-chat [text db algolia-client !state]
  (if db
    (let [chat-id (instantdb/id)
          tx (.-tx db)
          new-chat (aget (.-chats ^js/Object tx) chat-id)]
      (.transact db (.update new-chat #js{:text text}))
      (when algolia-client
        (-> (.saveObject ^js/Object algolia-client
                         (clj->js
                           {:indexName a/index-name-chats
                            :body      {:objectID chat-id
                                        :text     text}}))
            (.then js/console.log)))
      chat-id)
    ;; local-only
    (let [id (str (random-uuid))]
      (swap! !state update :chats (fn [vs]
                                    (conj (or vs [])
                                          {:id id :text text})))
      id)))

(defn save-response-to-db [db algolia-client response response-id chat-id !state]
  (if db
    (do
      (.transact db (let [new-response (aget (.-responses ^js/Object (.-tx db)) response-id)]
                      (-> new-response
                          (.update (clj->js response))
                          (.link #js {:chats chat-id}))))
      (when algolia-client
        (-> (.saveObject ^js/Object algolia-client
                         (clj->js {:indexName a/index-name-responses
                                   :body      {:objectID response-id
                                               :chat_id  chat-id
                                               :text     (:text response)}}))
            (.then js/console.log)))
      (swap! !state update-in [:loading-chats chat-id] dec))
    ;; local-only
    (let [chat-idx (->> (map vector (range) (map :id (:chats @!state)))
                        (filter (fn [[_ id]] (= id chat-id)))
                        util/single
                        first)]
      (swap! !state (fn [s]
                      (cond-> s
                              true (update-in [:chats chat-idx :responses] 
                                              (fn [vs] (conj (or vs [])
                                                             (assoc response :id response-id))))
                              true (update-in [:loading-chats chat-id] dec)))))))

(defn handle-api-error [e model !state chat-id]
  (js/alert
    (cond
      (some-> (ex-data e) :status)
      (str "Error: Got status " (:status (ex-data e))
           " from API (" (name model) ")")
      :else
      (str e)))
  (swap! !state update-in [:loading-chats chat-id] dec))

(defn handle-submit [text models api-keys db algolia-client selected-chat selected-chat-id !state]
  (let [chat-id (if (not= text (:text selected-chat))
                  (create-new-chat text db algolia-client !state)
                  selected-chat-id)
        start-time (js/Date.)]
    (doseq [model models]
      (swap! !state (fn [s]
                      (-> s
                          (assoc :selected-chat-id chat-id)
                          (update-in [:loading-chats chat-id] inc))))
      (-> (do-request! {:message  text
                        :model    model
                        :api-keys api-keys})
          (.then (fn [v]
                   (let [response-id (or (when db (instantdb/id))
                                         (str (random-uuid)))
                         end-time (js/Date.)
                         response {:text          v
                                   :model         (name model)
                                   :request-time  start-time
                                   :response-time end-time}]
                     (save-response-to-db db algolia-client response response-id chat-id !state))))
          (.catch (fn [e] (handle-api-error e model !state chat-id)))))))

(defn handle-search-results [res !state]
  (swap! !state assoc :search-results res))

(defn handle-chat-toggle-hidden [chat-id hidden db]
  (.transact db
             (.update (aget (.-chats ^js/Object (.-tx db)) chat-id)
                      #js{:hidden hidden})))

(defn handle-chat-select [selected-chat-id chats !state]
  (swap! !state assoc
         :selected-chat-id selected-chat-id
         :text (->> chats
                    (filter (comp #{selected-chat-id} :id))
                    util/single
                    :text)))

(defn handle-response-select [selected-chat-id id !state]
  (swap! !state assoc-in [:selections selected-chat-id] id))

;; UI Components
(defn api-keys-config [api-keys !state]
  [:div "API Keys Configuration Extracted"])

(defn model-selector [models all-models !state]
  [:div
   [:div (platform/format' "Select model: (%d selected)" (count models))]
   [:select
    {:multiple true
     :value     models
     :on-change #(handle-model-selection % !state)}
    (for [p all-models]
      ^{:key (name p)}
      [:option {:id (name p)}
       (name p)])]])

(defn text-input [text !state]
  [:p
   [:textarea
    {:style {:width "100%"}
     :rows  10
     :value text
     :on-change #(handle-text-change % !state)}]])

(defn submit-controls [text models api-keys db algolia-client selected-chat selected-chat-id loading !state]
  [:p
   {:style {:height 50}}
   [:button {:disabled (or (empty? text)
                           (empty? models))
             :on-click #(handle-submit text models api-keys db algolia-client 
                                       selected-chat selected-chat-id !state)}
    "submit"]
   (when loading
     [ui/spinner])])

(defn search-section [algolia-client !state]
  (when algolia-client
    [:div {:style {:display :flex}}
     "Search: "
     [ui/search
      {:algolia-client algolia-client
       :on-search      #(handle-search-results % !state)}]]))

(defn chat-interface [state chats db !state]
  [ui/error-boundary
   [ui-components/chat-menu state
    {:on-chat-toggle-hidden #(handle-chat-toggle-hidden %1 %2 db)
     :on-chat-select        #(handle-chat-select % chats !state)
     :on-response-select    (fn [[selected-chat-id id]]
                              (handle-response-select selected-chat-id id !state))}]])

(defn dev-debug-info [db state]
  #?(:dev-config
     [:div
      [:pre 'db? (str " " (some? db))]
      [:pre (util/spprint (dissoc state :chats))]]))

;; Response components
(defn format-timestamp [timestamp]
  (some-> timestamp
          js/Date.parse
          (js/Date.)
          str))

(defn create-markdown-converter []
  (doto (showdown/Converter.
          (clj->js {:extensions [think-start-extension
                                 think-end-extension]}))
    (.setFlavor "github")))

(defn response-timestamp [{:keys [request-time]}]
  [:div [:i (format-timestamp request-time)]])

(defn response-content [{:keys [text]}]
  [:div [:p
         {:dangerouslySetInnerHTML
          {:__html (.makeHtml (create-markdown-converter) text)}}]])

(defn response-view [{:keys [id] :as response}]
  (when id
    [:div
     [response-timestamp response]
     [response-content response]]))

(defn response-tab-item [{:keys [model id]} 
                         {:keys [chat-id selected-response-id index on-response-select]}]
  ^{:key id} 
  [:div 
   [:div
    {:style    {:padding 10}
     :on-click #(on-response-select [chat-id id])}
    [:div {:style {:background-color (when (or (= selected-response-id id)
                                               (and (nil? selected-response-id)
                                                    (zero? index))) :lightgray)}}
     (name model)]]])


(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db algolia-client]} !state]
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text models api-keys loading-chats chats selected-chat-id]
             :or   {models    (or
                                (some->> (js/localStorage.getItem "tjat-models") clojure.edn/read-string)
                                (some->> (js/localStorage.getItem "tjat-model") keyword (conj #{})) ;; legacy
                                #{(first all-models)})
                    api-keys  api-keys-persisted}} @!state
            loading (not (zero? (->> (for [[_ v] loading-chats]
                                       v)
                                     (apply +))))
            selected-chat (->> chats
                               (filter (comp #{selected-chat-id} :id))
                               util/single)]
        [:div
         [dev-debug-info db @!state]
         [:div
          [api-keys-config api-keys !state]]



         [:div
          [model-selector models all-models !state]
          [text-input text !state]
          [submit-controls text models api-keys db algolia-client selected-chat selected-chat-id loading !state]
          (when algolia-client
            [:div {:style {:display :flex}}
             "Search: "
             [ui/search
              {:algolia-client algolia-client
               :on-search      #(handle-search-results % !state)}]])
          [chat-interface @!state chats db !state]]]))))

(defn instant-db-error-handler [res]
  (let [e (.-error res)]
    (js/console.error e)
    (js/alert (.-message e))))

(defn on-algolia-import-click [algolia-client
                               {:keys [chats]}]
  (let [response-reqs (for [{:keys   [responses] :as c
                             chat-id :id} chats
                            {:keys [id] :as r} responses]
                        {:action    "addObject"
                         :indexName a/index-name-responses
                         :body      (assoc r :objectID id
                                             :chat_id chat-id)})
        chat-reqs (->> chats
                       (map (fn [{:keys [id] :as c}]
                              {:action    "addObject"
                               :indexName a/index-name-chats
                               :body      (-> (assoc c :objectID id)
                                              (dissoc :responses))})))]
    (-> (.multipleBatch
          ^js/Object algolia-client
          (clj->js {:requests (concat chat-reqs response-reqs)}))
        (.then #(js/alert "Done!"))
        (.catch #(js/alert "Something went wrong!")))))

(defn instantdb-view []
  (let [!ref-state (atom nil)]
    (r/create-class
      {:component-did-mount    (fn []
                                 (let [instantdb-app-id-persisted (js/localStorage.getItem "instantdb-app-id")
                                       algolia-app-id (js/localStorage.getItem "algolia-app-id")
                                       algolia-api-key (js/localStorage.getItem "algolia-api-key")]
                                   (when (seq instantdb-app-id-persisted)
                                     (swap! !ref-state
                                            merge
                                            (db/init-instant-db {:app-id        instantdb-app-id-persisted
                                                                 :subscriptions {:chats {#_#_:$ {:where
                                                                                                 {:or [{:hidden false}
                                                                                                       {:hidden {:$isNull true}}]}}
                                                                                         :responses {}}}
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
                                          :algolia {:app-id  algolia-app-id
                                                    :api-key algolia-api-key})))
       :component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys                      [instantdb-app-id]
                                        {algolia-app-id  :app-id
                                         algolia-api-key :api-key} :algolia} @!state
                                       {:keys [unsubscribe db algolia-client]} @!ref-state]
                                   #_[:pre (util/spprint @!ref-state)]
                                   [:div {:style {:max-width 800}}
                                    [:h1 "tjat!"]
                                    [:details #?(:dev-config {:open true})
                                     [:summary "Sync settings"]
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
                                       [:a {:href   "https://dashboard.algolia.com/"
                                            :target "_blank"}
                                        "Algolia"]
                                       " App-id: "
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
                                       "Algolia API key (write): "
                                       [ui/edit-field {:on-save (fn [s]
                                                                  (if (seq s)
                                                                    (do
                                                                      (js/localStorage.setItem "algolia-api-key" s)
                                                                      (when (seq algolia-app-id)
                                                                        (swap! !ref-state
                                                                               merge
                                                                               {:algolia-client (algolia/algoliasearch
                                                                                                  algolia-app-id s)}))
                                                                      (swap! !state assoc-in [:algolia :api-key] s))
                                                                    (do
                                                                      (js/localStorage.removeItem "algolia-api-key")
                                                                      (swap! !ref-state dissoc :algolia-client)
                                                                      (swap! !state update :algolia dissoc :api-key))))


                                                       :value   algolia-api-key}]]
                                      [:button {:on-click #(on-algolia-import-click
                                                             algolia-client
                                                             @!state)
                                                :disabled (or (empty? algolia-api-key)
                                                              (empty? algolia-app-id))}

                                       "Import to Algolia"]]]
                                    [app {:db             db
                                          :algolia-client algolia-client}
                                     !state]]))})))

(defn ^:dev/after-load main []
  (.render root (r/as-element [instantdb-view])))

