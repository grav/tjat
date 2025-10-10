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
            [tjat.algolia :as a]
            ["@instantdb/core" :as instantdb]
            ["algoliasearch" :as algolia]
            [clojure.edn]
            [clojure.string]))

(defonce root (react-dom/createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))

(def instantdb-subs
  {:chats {#_#_:$ {:where
                   {:or [{:hidden false}
                         {:hidden {:$isNull true}}]}}
           :responses {}}
   :systemPrompts {}})

(defn do-request! [{:keys [messages model api-keys]}]
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               (let [config (allem.core/make-config
                              {:model    model
                               :api-keys api-keys})
                     {:keys [reply-fn headers url body]} (allem.core/apply-config
                                                           (assoc config :messages messages))]
                 (-> (http/send! client {:method  :post
                                         :url     url
                                         :headers headers
                                         :body    (js/JSON.stringify (clj->js body))})
                     (.then #(get % :body))
                     (.then js/JSON.parse)
                     (.then #(js->clj % :keywordize-keys true))
                     (.then reply-fn)))))))


(comment
  (-> (do-request! {:message "yolo"
                    :model   :gpt-4o})
      (.then util/spprint)
      (.then println)))

(def think-start
  (clj->js
    {:type "lang"
     :regex #"<think>"
     :replace "<small><details><summary><i>Think \uD83D\uDCAD</i></summary><i>"}))

(def think-end
  (clj->js
    {:type "lang"
     :regex #"</think>"
     :replace "</i><hr></details></small>"}))

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
                        (doto (showdown/Converter.
                                (clj->js {:extensions [think-start
                                                       think-end]}))
                          (.setFlavor "github")) text)}}]]]))

(defn system-prompt-tabs [{:keys [ids on-select selected-id system-prompts]}]
  [:div {:style {:display :flex}}
   (for [id ids
         :let [selected-value (get-in system-prompts [id :value])]]
     ^{:key id} [:div [:div
                       {:style    {:padding 10}
                        :on-click #(on-select id)}
                       [:div {:style {:background-color (when (= selected-id id)
                                                          :lightgray)
                                      :font-weight (if (empty? selected-value)
                                                     500 800)}}
                        (name id)]]])])

(defn response-tabs [{:keys     [selected-response-id loading-chats]
                      chat-id   :id
                      responses :responses}
                     {:keys [on-response-select]}]
  [:div {:style {:display :flex}}
   (concat (for [[i {:keys [model id] :as v}] (map vector (range) (sort-by :time responses))]
             ^{:key id} [:div [:div
                               {:style    {:padding 10}
                                :on-click #(on-response-select [chat-id id])}
                               [:div {:style {:background-color (when (or (= selected-response-id id)
                                                                          (and (nil? selected-response-id)
                                                                               (zero? i))) :lightgray)}}
                                (name model)]]])
           (for [model (vals loading-chats)]
             [:div ^{:key (name model)}
              [:div
               {:style {:padding 10}}
               [:div {:style {:color :lightgray}}
                (name model)]]]))])


(defn chat-menu []
  (let [!state (r/atom nil)]
    (fn [{:keys [chats selected-chat-id selections loading-chats]
          {search-response-ids :responses
           search-chat-ids     :chats
           :as search-results} :search-results}
         {:keys [on-chat-select on-chat-toggle-hidden]
          :as   handlers}]
      (let [{selected-response-id selected-chat-id} selections
            {:keys [hover timer resting show-hidden]} @!state
            {:keys [responses hidden]
             :as   chat} (->> chats
                              (filter (comp #{selected-chat-id} :id))
                              util/single)]
        [:div
         [:div [:label [:input {:type     :checkbox
                                :value    (boolean show-hidden)
                                :on-click (fn [_]
                                            (swap! !state assoc :show-hidden (not show-hidden)))}]
                "Show hidden"]]
         [:div {:style {:display :flex
                        :padding 10}}
          [:div (for [{:keys [id text hidden]} (reverse chats)]
                  ^{:key id} [:div {:on-click #(on-chat-select id)
                                    :style    {:display (when (or (and search-results
                                                                       (nil? (search-chat-ids id)))
                                                                  (and (not show-hidden)
                                                                       hidden))
                                                          :none)}}
                              [:div {:style          {:position         :relative
                                                      :font-weight      900
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
                               text
                               (when (or hidden
                                         (= id hover))
                                 [:div {:title    (if hidden "Show" "Hide")
                                        :style    {:position :absolute
                                                   :top      0
                                                   :padding  5
                                                   :right    0
                                                   :z-index  1
                                                   :cursor   :pointer}
                                        :on-click (fn [e]
                                                    (.stopPropagation e)
                                                    (on-chat-toggle-hidden id (not hidden)))}
                                  (if hidden "+" "˟")])]
                              (when (= selected-chat-id id))])]
          [:div {:style {:display (when (and hidden
                                             (not show-hidden))
                                    :none)
                         :padding 10}}
           [response-tabs (assoc chat
                            :selected-response-id selected-response-id
                            :loading-chats (get loading-chats selected-chat-id)) handlers]
           [:hr]
           (when (or (nil? search-results)
                     (search-response-ids selected-response-id))
             [response-view (or (->> responses (filter (comp #{selected-response-id} :id)) seq)
                                (first responses))])]]]))))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db algolia-client]} !state]
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text models api-keys loading-chats chats selected-chat-id systemPrompts system-prompts selected-system-prompt]
             :or   {models    (or
                                (some->> (js/localStorage.getItem "tjat-models") clojure.edn/read-string)
                                (some->> (js/localStorage.getItem "tjat-model") keyword (conj #{})) ;; legacy
                                #{(first all-models)})
                    api-keys  api-keys-persisted}} @!state
            system-prompts (if db
                             (->> (for [{:keys [value model id]} systemPrompts]
                                    [(keyword model) {:value        value
                                                      :instantdb-id id}])
                                  (into {}))
                             system-prompts)
            loading (not (zero? (->> (for [[_ v] loading-chats]
                                       (count (keys v)))
                                     (apply +))))
            selected-chat (->> chats
                               (filter (comp #{selected-chat-id} :id))
                               util/single)]
        [:div
         #?(:dev-config
            [:div
             [:pre 'db? (str " " (some? db))]
             [:pre (util/spprint (dissoc @!state :chats :uploaded-files))]])
         [:div
          [:details #?(:dev-config {:open true})
           [:summary "API keys"]
           [:div
            [:table
             [:thead [:tr
                      [:th "Provider"]
                      [:th "Key"]]]
             [:tbody
              (for [[provider _] (:providers allem.core/config)]
                ^{:key (name provider)}
                [:tr
                 [:td (name provider)]
                 [:td [ui/edit-field {:on-save
                                      (fn [k]
                                        (let [api-keys (if (seq k)
                                                         (merge api-keys
                                                                {provider k})
                                                         (dissoc api-keys provider))]
                                          (swap! !state assoc :api-keys api-keys)
                                          (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                                      :value (get api-keys provider)}]]])]]]]]



         [:div
          [:div (platform/format' "Select model: (%d selected)" (count models))]
          [:select
           {:multiple true
            :value     models
            :on-change (fn [e]
                         (let [selected-models (->> (.-selectedOptions (.-target e))
                                                    (map #(.-value %))
                                                    (map keyword)
                                                    set)]
                           (swap! !state assoc :models selected-models)
                           (js/localStorage.setItem "tjat-models" (pr-str selected-models))))}

           (for [p all-models]
             ^{:key (name p)}
             [:option {:id (name p)}
              (name p)])]

          [:div
           "System prompt:"
           [:div {:style {:display :flex}}

            [system-prompt-tabs {:ids models
                                 :system-prompts    system-prompts
                                 :selected-id       selected-system-prompt
                                 :on-select         (fn [id]
                                                      (let [v (if (not= id selected-system-prompt)
                                                                id
                                                                nil)]
                                                        (swap! !state assoc :selected-system-prompt v)))}]]
           (when (and selected-system-prompt
                      (contains? (set models) selected-system-prompt))
             [:div
              [:textarea
               {:style {:width "100%"}
                :rows  10
                :value (get-in system-prompts [selected-system-prompt :value])
                :on-change
                (fn [e]
                  (if db
                    (.transact db (let [new-prompt (aget (.-systemPrompts ^js/Object (.-tx db))
                                                         (or (get-in system-prompts [selected-system-prompt :instantdb-id])
                                                             (instantdb/id)))]
                                    (-> new-prompt
                                        (.update (clj->js {:model selected-system-prompt
                                                           :value (.-value (.-target e))})))))
                    (swap! !state assoc-in [:system-prompts selected-system-prompt :value] (.-value (.-target e)))))}]])
           "Text:"
           [:textarea
            {:style {:width "100%"}
             :rows  10
             :value text
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:div
           [:label "Upload files: "]
           (let [drag-over? (r/atom false)
                 handle-files (fn [files]
                                (doseq [file files]
                                  (let [file-key (str (.-name file) "-" (.-size file) "-" (.-lastModified file))]
                                    (when-not (get-in @!state [:uploaded-files file-key])
                                      (let [reader (js/FileReader.)]
                                        (set! (.-onload reader)
                                              (fn [event]
                                                (let [data-url (.-result (.-target event))
                                                      base64-data (second (clojure.string/split data-url #","))]
                                                  (swap! !state assoc-in [:uploaded-files file-key]
                                                         {:file file
                                                          :base64 base64-data
                                                          :name (.-name file)
                                                          :type (.-type file)}))))
                                        (.readAsDataURL reader file))))))]
             [:div {:style {:border (if @drag-over? "2px dashed #007cba" "2px dashed #ccc")
                            :padding "20px"
                            :text-align "center"
                            :background-color (if @drag-over? "#f0f8ff" "#fafafa")
                            :border-radius "5px"
                            :cursor "pointer"
                            :transition "all 0.2s ease"}
                    :on-click (fn [e]
                                (let [input (.createElement js/document "input")]
                                  (set! (.-type input) "file")
                                  (set! (.-multiple input) true)
                                  (set! (.-onchange input) (fn [e]
                                                             (handle-files (array-seq (.-files (.-target e))))))
                                  (.click input)))
                    :on-drag-over (fn [e]
                                    (.preventDefault e)
                                    (.stopPropagation e)
                                    (reset! drag-over? true))
                    :on-drag-leave (fn [e]
                                     (.preventDefault e)
                                     (.stopPropagation e)
                                     (reset! drag-over? false))
                    :on-drop (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (reset! drag-over? false)
                               (handle-files (array-seq (.-files (.-dataTransfer e)))))}
              [:p {:style {:margin "0"}}
               (if @drag-over?
                 "Drop files here"
                 "Click to select files or drag and drop them here")]])]
          (when-let [uploaded-files (:uploaded-files @!state)]
            (when (seq uploaded-files)
              [:div
               [:p (str "Uploaded " (count uploaded-files) " file(s):")]
               (for [[file-key {:keys [name type]}] uploaded-files]
                 ^{:key file-key} [:p {:style {:margin-left 20}} (str "• " name " (" type ")")])
               [:button {:on-click #(swap! !state dissoc :uploaded-files)
                         :style {:margin-left 10}}
                "Clear All"]]))
          [:p
           {:style {:height 50}}
           [:button {:disabled (or (empty? text)
                                   (empty? models))
                     :on-click #(do

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
                                                                            :body      {:objectID chat-id
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

                                    (doseq [model models]
                                      (let [response-id (or (when db
                                                              (instantdb/id))
                                                            (str (random-uuid)))
                                            system-prompt (-> (get-in system-prompts [model :value])
                                                              not-empty)]
                                        (swap! !state (fn [s]
                                                        (-> s
                                                            (assoc :selected-chat-id chat-id)
                                                            (assoc-in [:loading-chats chat-id response-id] model))))
                                        (-> (do-request! (let [uploaded-files (:uploaded-files @!state)
                                                               file-values (when uploaded-files (vals uploaded-files))
                                                               messages (->> (concat [system-prompt text]
                                                                                     file-values)
                                                                             (remove nil?))]
                                                           {:messages messages
                                                            :model    model
                                                            :api-keys api-keys}))
                                            (.then (fn [v]
                                                     (let [
                                                           end-time (js/Date.)
                                                           response {:text          v
                                                                     :model         (name model)
                                                                     :system-prompt system-prompt
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
                                                                              (clj->js {:indexName a/index-name-responses
                                                                                        :body      {:objectID response-id
                                                                                                    :chat_id  chat-id
                                                                                                    :text     v}}))
                                                                 (.then js/console.log)))

                                                           (swap! !state update-in [:loading-chats chat-id] dissoc response-id))

                                                         ;; local-only
                                                         (let [chat-idx (->> (map vector (range) (map :id (:chats @!state))) ;; weird that 'chat' isn't updated?
                                                                             (filter (fn [[_ id]]
                                                                                       (= id chat-id)))
                                                                             util/single
                                                                             first)]
                                                           (swap! !state (fn [s]
                                                                           (cond-> s
                                                                                   true
                                                                                   (update-in [:chats chat-idx :responses] (fn [vs & _args]
                                                                                                                             (conj (or vs [])
                                                                                                                                   (assoc response
                                                                                                                                     :id response-id))) [])
                                                                                   true (update-in [:loading-chats chat-id] dissoc response-id))))
                                                           100)))))
                                            (.catch (fn [e]
                                                      (js/alert
                                                        (cond
                                                          (some-> (ex-data e) :status)
                                                          (str "Error: Got status " (:status (ex-data e))
                                                               " from API (" (name model) ")")
                                                          :else
                                                          (str e)))
                                                      (swap! !state update-in [:loading-chats chat-id] dissoc response-id))))))))}


            "submit"]
           (when loading
             [ui/spinner])]
          (when algolia-client
            [:div {:style {:display :flex}}
             "Search: "
             [ui/search
              {:algolia-client algolia-client
               :on-search      (fn [res]
                                 (swap! !state assoc :search-results res))}]])
          [ui/error-boundary
           [chat-menu @!state
            {:on-chat-toggle-hidden (fn [chat-id hidden]
                                      (.transact db
                                                 (.update (aget (.-chats ^js/Object (.-tx db)) chat-id)
                                                          #js{:hidden hidden})))
             :on-chat-select        (fn [selected-chat-id]
                                      (swap! !state assoc
                                             :selected-chat-id selected-chat-id
                                             ;; TODO - this might be a bit aggressive ...
                                             :text (->> chats
                                                        (filter (comp #{selected-chat-id} :id))
                                                        util/single
                                                        :text)))
             :on-response-select    (fn [[selected-chat-id id]]
                                      (swap! !state assoc-in [:selections selected-chat-id] id))}]]]]))))

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
                                                                 :subscriptions instantdb-subs
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
                                                                                   :subscriptions instantdb-subs
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

