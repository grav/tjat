(ns tjat.core
  (:require ["react-dom/client" :as react-dom]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [tjat.db :as db]
            [tjat.ui :as ui]
            [tjat.algolia :as a]
            [tjat.api :as api]
            [tjat.components.response :refer [response-view response-tabs]]
            [tjat.components.chat-menu :refer [chat-menu]]
            [tjat.components.model-selector :refer [model-selector]]
            [tjat.components.message-form :refer [message-form]]
            [tjat.components.settings :refer [settings-panel]]
            ["@instantdb/core" :as instantdb]
            ["algoliasearch" :as algolia]))

(defonce root (react-dom/createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))

(comment
  (-> (api/do-request! {:message "yolo"
                        :model   :gpt-4o})
      (.then util/spprint)
      (.then println)))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db algolia-client]} !state]
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
         [:h1 "Tjat!"]
         [:div
          ;; Model selector component
          [model-selector 
           {:model model
            :all-models all-models
            :provider provider
            :api-keys api-keys}
           {:on-model-change (fn [model-keyword]
                               (swap! !state assoc :model model-keyword))
            :on-api-key-save (fn [api-keys]
                               (swap! !state assoc :api-keys api-keys))}]

          ;; Message form component
          [message-form 
           {:text text
            :loading loading}
           {:on-text-change (fn [new-text]
                              (swap! !state assoc :text new-text))
            :on-submit #(do
                          (swap! !state assoc :loading true)
                          (let [chat-id (if (not= text (:text selected-chat))
                                          (if db
                                            (let [chat-id (instantdb/id)
                                                  tx (.-tx db)
                                                  new-chat (aget (.-chats ^js/Object tx) chat-id)]
                                              (.transact db
                                                         (.update new-chat #js{:text text}))
                                              (when algolia-client
                                                (-> (.saveObject ^js/Object algolia-client
                                                                 (clj->js
                                                                   {:indexName a/index-name-chats
                                                                    :body      {:id       chat-id
                                                                                :objectID chat-id
                                                                                :text     text}}))
                                                    (.then js/console.log)))
                                              chat-id)
                                            ;; local-only
                                            (let [id (str (random-uuid))]
                                              (swap! !state update :chats (fn [vs]
                                                                            (conj (or vs [])
                                                                                  {:id id :text text})))
                                              id))

                                          selected-chat-id)
                                start-time (js/Date.)]
                            (-> (api/do-request! {:message  text
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
                                               (when algolia-client
                                                 (-> (.saveObject ^js/Object algolia-client
                                                                  (clj->js {:indexName a/index-name-chats
                                                                            :body      {:id      response-id
                                                                                        :objectID chat-id
                                                                                        :chat_id chat-id
                                                                                        :text    v}}))
                                                     (.then js/console.log)))

                                               (swap! !state (fn [s]
                                                               (-> s
                                                                   (assoc-in [:selections chat-id] response-id)
                                                                   (assoc :loading false)
                                                                   (assoc :selected-chat-id chat-id)))))
                                             ;; local-only
                                             (let [chat-idx (->> (map vector (range) (map :id (:chats @!state)))
                                                                 (filter (fn [[_ id]]
                                                                           (= id chat-id)))
                                                                 util/single
                                                                 first)]
                                               (swap! !state (fn [s]
                                                               (-> s
                                                                   (update-in [:chats chat-idx :responses] (fn [vs]
                                                                                                             (conj (or vs [])
                                                                                                                   (assoc response
                                                                                                                     :id response-id))))
                                                                   (assoc-in [:selections chat-id] response-id)
                                                                   (assoc :loading false)
                                                                   (assoc :selected-chat-id chat-id))))
                                               100)))))
                                (.catch (fn [e]
                                          (js/alert
                                            (cond
                                              (some-> (ex-data e) :status)
                                              (str "Error: Got status " (:status (ex-data e))
                                                   " from API")
                                              :else
                                              (str e)))
                                          (swap! !state assoc :loading false))))))}]
          
          ;; Search component
          (when algolia-client
            [:div {:style {:display :flex}}
             "Search: "
             [ui/search
              {:algolia-client algolia-client
               :on-search      (fn [res]
                                 (swap! !state assoc :search-results res))}]])
          
          ;; Chat menu component
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
                                          :algolia {:app-id  algolia-app-id
                                                    :api-key algolia-api-key})))
       :component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys                      [instantdb-app-id chats]
                                        {algolia-app-id  :app-id
                                         algolia-api-key :api-key} :algolia} @!state
                                       {:keys [unsubscribe db algolia-client]} @!ref-state]
                                   [:div {:style {:max-width 800}}
                                    ;; Settings panel component
                                    [settings-panel
                                     {:instantdb-app-id instantdb-app-id
                                      :algolia {:app-id algolia-app-id
                                                :api-key algolia-api-key}
                                      :chats chats}
                                     {:on-instantdb-change (fn [s]
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
                                      :on-algolia-id-change (fn [s]
                                                              (if (seq s)
                                                                (do
                                                                  (js/localStorage.setItem "algolia-app-id" s)
                                                                  (when (seq algolia-api-key)
                                                                    (swap! !ref-state
                                                                           merge
                                                                           {:algolia-client (algolia/algoliasearch
                                                                                              s algolia-api-key)}))
                                                                  (swap! !state assoc-in [:algolia :app-id] s))
                                                                (do
                                                                  (js/localStorage.removeItem "algolia-app-id")
                                                                  (swap! !ref-state dissoc :algolia-client)
                                                                  (swap! !state update :algolia dissoc :app-id))))
                                      :on-algolia-key-change (fn [s]
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
                                      :on-algolia-import (fn []
                                                           (let [{:keys [chats]} @!state
                                                                 response-reqs (for [{:keys   [responses] :as c
                                                                                      chat-id :id} (:chats @!state)
                                                                                     {:keys [id] :as r} responses]
                                                                                 {:action    "addObject"
                                                                                  :indexName a/index-name-responses
                                                                                  :body      (assoc r :objectID id
                                                                                                      :chat_id chat-id)})
                                                                 chat-reqs (->> chats
                                                                                (map (fn [{:keys [id] :as c}]
                                                                                       {:action "addObject"
                                                                                        :indexName a/index-name-chats
                                                                                        :body (-> (assoc c :objectID id)
                                                                                                  (dissoc :responses))})))]
                                                             (-> (.multipleBatch
                                                                   ^js/Object algolia-client
                                                                   (clj->js {:requests (concat chat-reqs response-reqs)}))
                                                                 (.then #(js/alert "Done!"))
                                                                 (.catch #(js/alert "Something went wrong!")))))}]
                                    
                                    [app {:db             db
                                          :algolia-client algolia-client}
                                     !state]]))})))

(defn ^:dev/after-load main []
  (.render root (r/as-element [instantdb-view])))
