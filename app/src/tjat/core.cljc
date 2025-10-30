(ns tjat.core
  (:require ["react-dom/client" :as react-dom]
            [tjat.util :as util]
            [allem.util :as allem-util]
            [allem.platform :as platform]
            [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.server :as rdom]
            [goog.dom :as gdom]
            [goog.string :as gstring]
            [goog.string.format]
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
            [clojure.string]
            ["@aws-sdk/client-s3" :as aws-s3]
            ["@aws-sdk/s3-request-presigner" :as aws-presign]
            [tjat.s3 :as s3]
            ["react-select$default" :as Select]
            [tjat.markdown :as md]))

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

(defn add-file-button []
  (let [!is-adding (r/atom nil)]
    (fn [{:keys [on-add-file file]}]
      (if @!is-adding
        [ui/spinner]
        [:a {:style {:margin 5}
             :href  "#" :on-click (fn [e]
                                    (.preventDefault e)
                                    (reset! !is-adding true)
                                    (-> (on-add-file file)
                                        (.catch #(js/alert (ex-message %)))
                                        (.finally #(reset! !is-adding false))))}

         "Add"]))))

(defn open-file-button [{:keys [title on-open-file]}]
  [:a {:style {:margin-left 5}
       :href "#"
       :on-click (fn [e]
                   (.preventDefault e)
                   (-> (on-open-file)
                       (.then (fn [url]
                                (js/window.open url "_blank")))))}

   (or title "Open")])

(defn on-share+ [{:keys [selected-chat-text render-view response]
                  {:keys [sharing-bucket sharing-url]
                   :as   s3} :s3
                  {:keys [files text id model]} :response}]
  (let [body (rdom/render-to-static-markup
               [:div
                [:h2 [:pre "tjat share"]]
                [:div "Text:"
                 [:div {:style {:margin 4}} selected-chat-text]]
                [:hr]
                [:div (str "Model: ")
                 [:b model]]
                [render-view
                 {:chat-text selected-chat-text
                  :response  (-> response
                                 (update :files
                                         (fn [files]
                                           (for [[k {:keys [file-hash] :as f}] files]
                                             [k (assoc f :share-url
                                                  (str sharing-url "/" file-hash))]))))}]])

        html-str (gstring/format "<html><head><style>code { white-space: pre-wrap; }</style></head><body style='max-width: 800px;'>%s</body></html>" body)
        share-key (gstring/format "%s.html" id)]
    (-> (js/Promise.all (concat
                          [(s3/upload+ (assoc s3 :bucket sharing-bucket) {:file      html-str
                                                                          :file-type "text/html; charset=utf-8"
                                                                          :key       share-key})]
                          (for [[_k {:keys [file-hash file-type]}] files]
                            (s3/copy+ s3 {:source-bucket      (:bucket s3)
                                          :destination-bucket sharing-bucket
                                          :source-key         file-hash
                                          :destination-key    file-hash
                                          :file-type          file-type}))))
        (.then (fn []
                 share-key)))))

(defn response-view []
  (let [!state (r/atom nil)]
    (fn [{{:keys [response-time id text system-prompt files]
           :as response} :response
          {:keys [sharing-bucket sharing-url]
           :as s3} :s3
          :keys [selected-chat-text on-add-file on-share+]}]

      (let [s3-sharing (assoc s3 :bucket sharing-bucket)
            {{{:keys [rstate share-key]} id} :render-state} @!state]
        (when id
          [:div
           (when on-share+
             [:div [:button {:on-click (fn []
                                         (swap! !state assoc-in [:render-state id :rstate] :rendering)
                                         (-> (on-share+ {:selected-chat-text selected-chat-text
                                                         :render-view response-view
                                                         :response response
                                                         :s3       s3})
                                             (.then (fn [share-key]
                                                      (swap! !state update-in [:render-state id]
                                                             (fn [s]
                                                               (assoc s :rstate :rendered
                                                                        :share-key share-key)))))
                                             (.catch (fn [e]
                                                       (swap! !state update-in [:render-state] dissoc id)
                                                       (js/console.log e)))))
                             :disabled (or (nil? sharing-bucket)
                                           (= rstate :rendering))}

                    "Share"]
              (when (= rstate :rendered)
                [open-file-button {:title        "Open Share"
                                   :on-open-file (if sharing-url
                                                   #(js/Promise.resolve (gstring/format "%s/%s"
                                                                                        sharing-url share-key))
                                                   #(s3/get-file-open-url+ s3-sharing {:key share-key}))}])])
           [:div [:i (util/date->str response-time)]]
           (when system-prompt
             [:div [:small
                    [:details
                     [:summary [:i "System prompt"] " \uD83E\uDD16"]
                     [:i [md/markdown-view system-prompt]]]]])
           (when (not-empty files)
             [:div [:small
                    [:details
                     [:summary [:i "Files"] " \uD83D\uDCC1"]
                     (for [[k {nme       :name :as f
                               share-url :share-url
                               file-hash :file-hash}] files]
                       ^{:key (name k)} [:div {:style {:display :flex}}
                                         (cond
                                           (and on-add-file
                                                file-hash)
                                           [:div
                                            nme
                                            " "
                                            [add-file-button
                                             {:on-add-file on-add-file
                                              :file        f}]
                                            [open-file-button
                                             {:on-open-file #(s3/get-file-open-url+ s3 {:key file-hash})}]]

                                           share-url
                                           [:a {:href share-url :target file-hash}
                                            nme])])]]])


           [:div [md/markdown-view text]]])))))

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
           (for [[i model] (->> (vals loading-chats)
                                (map vector (range)))]
             ^{:key i}
             [:div
              [:div
               {:style {:padding 10}}
               [:div {:style {:color :lightgray}}
                (name model)]]]))])


(defn chat-menu []
  (let [!state (r/atom nil)]
    (fn [{:keys [chats selected-chat-id selections loading-chats s3]
          {search-response-ids :responses
           search-chat-ids     :chats
           :as search-results} :search-results}
         {:keys [on-chat-select on-chat-toggle-hidden on-add-file on-open-file]
          :as   handlers}]
      (let [{selected-response-id selected-chat-id} selections
            {:keys [hover timer resting show-hidden]} @!state
            {:keys [responses hidden text]
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
                        :height "100vh"
                        :padding 10}}
          [:div
           {:style {:width 170
                    :overflow-y :scroll}}
           (for [{:keys [id text hidden]} (reverse chats)]
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
                             (if hidden "+" "˟")])]])]
          [:div {:style {:width "calc(100% - 170px)"
                         :max-height "100%"
                         :overflow-y :scroll
                         :display (when (and hidden
                                             (not show-hidden))
                                    :none)
                         :padding 10}}
           [:div
            {:style {:overflow-x :scroll}}
            [response-tabs (assoc chat
                             :selected-response-id selected-response-id
                             :loading-chats (get loading-chats selected-chat-id)) handlers]]
           [:hr]
           (when (or (nil? search-results)
                     (search-response-ids selected-response-id))
             [response-view {:selected-chat-text text
                             :response (or (->> responses (filter (comp #{selected-response-id} :id)) seq)
                                           (first responses))
                             :s3 s3
                             :on-share+ on-share+
                             :on-add-file on-add-file
                             :on-open-file on-open-file}])]]]))))

(defn handle-submit [{:keys [db algolia-client
                             text selected-chat-id chats models api-keys system-prompts uploaded-files]}
                     !state]
  (let [selected-chat (->> chats
                           (filter (comp #{selected-chat-id} :id))
                           util/single
                           :text)
        uploaded-files-meta (->> (for [[k v] uploaded-files]
                                   [k (dissoc v :file :base64)])
                                 (into {})
                                 not-empty)
        chat-id (if (not= text selected-chat)
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
                              not-empty)
            file-values (when uploaded-files (vals uploaded-files))]
        (swap! !state (fn [s]
                        (-> s
                            (assoc :selected-chat-id chat-id)
                            (assoc-in [:loading-chats chat-id response-id] model))))
        (-> (do-request! (let [messages (->> (concat [system-prompt text]
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
                                     :response-time end-time
                                     :files         (clj->js uploaded-files-meta)}]
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
                         (let [chat-idx (->> (map vector (range) (map :id chats))
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
                      (swap! !state update-in [:loading-chats chat-id] dissoc response-id))))))))

(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn [{:keys [db algolia-client]} !state]
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text models api-keys loading-chats chats selected-chat-id systemPrompts
                      system-prompts selected-system-prompt s3 uploaded-files]
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
            s3-configured? (let [{:keys [access-key-id secret-access-key bucket]} s3]
                             (and access-key-id secret-access-key bucket))]

        [:div
         #?(:dev-config
            [:div
             [:pre 'db? (str " " (some? db))]
             [:pre 's3? (str " " (some? s3-configured?))]
             [:details
              [:summary "app-state"]
              [:pre (util/spprint (-> (dissoc @!state :_chats)
                                      (update :uploaded-files
                                              (fn [files]
                                                (->> (for [[k v] files]
                                                       [k (dissoc v :base64)])
                                                     (into {}))))))]]])
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
          [:> Select
           {:isMulti true
            :defaultValue (for [p models]
                            {:value (name p)
                             :label (name p)})
            :options (for [p all-models]
                       ^{:key (name p)}
                       {:value (name p)
                        :label (name p)})
            :on-change (fn [v]
                         (let [selected-models (->> (js->clj v :keywordize-keys true)
                                                    (map (comp keyword :value))
                                                    set)]
                           (swap! !state assoc :models selected-models)
                           (js/localStorage.setItem "tjat-models" (pr-str selected-models))))}]
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
           [:div
            [:textarea
             {:id "foo"
              :style (merge {:width "100%"
                             :height 100
                             :transition "max-height 0.3s ease-out"}
                            (if (and selected-system-prompt
                                     (contains? (set models) selected-system-prompt))
                              {:max-height 100
                               :overflow :hidden}

                              {:max-height 0}))
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
                  (swap! !state assoc-in [:system-prompts selected-system-prompt :value] (.-value (.-target e)))))}]]
           "Text:"
           [:textarea
            {:style {:width "100%"}
             :rows  10
             :value text
             :on-key-down (fn [e]
                            (when (and (= (.-key e) "Enter")
                                       (or (.-metaKey e) (.-ctrlKey e)))
                              (.preventDefault e)
                              (handle-submit
                                {:db db
                                 :algolia-client algolia-client
                                 :text text
                                 :selected-chat-id selected-chat-id
                                 :chats chats
                                 :models models
                                 :api-keys api-keys
                                 :system-prompts system-prompts
                                 :uploaded-files uploaded-files}
                                !state)))
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:div
           [:label "Upload files: "]
           (let [drag-over? (r/atom false)
                 upload-s3 (fn [files]
                             (doseq [file files]
                               (let [file-key (str (.-name file) "-" (.-size file) "-" (.-lastModified file))]
                                    (when-not (get-in @!state [:uploaded-files file-key])
                                      (let [reader (js/FileReader.)
                                            file-type (.-type file)]
                                        (set! (.-onload reader)
                                              (fn [event]
                                                (js/console.log event)
                                                (let [base64-data (when (not (allem-util/is-text-mimetype? file-type))
                                                                    (-> (.-result (.-target event))
                                                                        (str/split #",")
                                                                        second))
                                                      file-text (when (allem-util/is-text-mimetype? file-type)
                                                                  (.-result (.-target event)))
                                                      s3-client (when s3-configured?
                                                                  (s3/create-client s3))]
                                                  (when s3-client
                                                    (-> (.arrayBuffer file)
                                                        (.then (fn [buffer]
                                                                 (.digest js/crypto.subtle "SHA-256" buffer)))
                                                        (.then (fn [buffer]
                                                                 (let [file-hash (->> (js/Array.from (js/Uint8Array. buffer))
                                                                                      (map (fn [b] (.padStart (.toString b 16) 2 "0")))
                                                                                      (str/join))]
                                                                   (swap! !state update-in [:uploaded-files file-key]
                                                                          (fn [f]
                                                                            (assoc f :file-hash file-hash
                                                                                     :cache-status :checking)))
                                                                   {:file-hash file-hash})))
                                                        (.then (fn [{:keys [file-hash] :as args}]
                                                                 (-> (s3/file-exists+ s3 {:key file-hash})
                                                                     (.then (fn [file-exists?]
                                                                              (assoc args :file-exists? file-exists?))))))
                                                        (.then (fn [{:keys [file-exists? file-hash]}]
                                                                 (when-not file-exists?
                                                                   (swap! !state assoc-in [:uploaded-files file-key :cache-status]
                                                                          :caching)
                                                                   (js/console.log (str "caching file " file-hash "..."))
                                                                   (s3/upload+ s3 {:file file
                                                                                   :key  file-hash}))))
                                                        (.then (fn [_]
                                                                 (swap! !state assoc-in [:uploaded-files file-key :cache-status]
                                                                        :cached)
                                                                 (js/console.log "done")))
                                                        (.catch (fn [e]
                                                                  (js/alert e)
                                                                  (swap! !state update :uploaded-files dissoc file-key)))))


                                                  (swap! !state assoc-in [:uploaded-files file-key]
                                                         {:text   file-text
                                                          :base64 base64-data
                                                          :name   (.-name file)
                                                          :type   file-type}))))
                                        (if (allem-util/is-text-mimetype? file-type)
                                          (.readAsText reader file)
                                          (.readAsDataURL reader file)))))))]

             [:div {:style {:border (if @drag-over? "2px dashed #007cba" "2px dashed #ccc")
                            :padding "20px"
                            :text-align "center"
                            :background-color (if @drag-over? "#f0f8ff" "#fafafa")
                            :border-radius "5px"
                            :cursor "pointer"
                            :transition "all 0.2s ease"}
                    :tabIndex 0
                    :on-click (fn [e]
                                (let [input (.createElement js/document "input")]
                                  (set! (.-type input) "file")
                                  (set! (.-multiple input) true)
                                  (set! (.-onchange input) (fn [e]
                                                             (upload-s3 (array-seq (.-files (.-target e))))))
                                  (.click input)))
                    :on-paste (fn [e]
                                ;; allow pasting images (clipboard files) or image URLs
                                (.preventDefault e)
                                (.stopPropagation e)
                                (let [clipboard (.-clipboardData e)
                                      items (.-items clipboard)
                                      items-seq (array-seq items)
                                      file-items (filter #(= "file" (.-kind %)) items-seq)]
                                  (if (seq file-items)
                                    ;; clipboard contains file(s) (e.g. image)
                                    (let [files (mapv #(.getAsFile %) file-items)]
                                      (upload-s3 (array-seq (clj->js files))))
                                    ;; try text/url
                                    (let [text (.getData clipboard "text")
                                          url text]
                                      (when (and url
                                                 (re-matches #".*\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?" url))
                                        (-> (js/fetch url)
                                            (.then (fn [res] (.blob res)))
                                            (.then (fn [blob]
                                                     (let [name (or (last (str/split url "/")) (str "pasted-" (random-uuid)))
                                                           file (js/File. (clj->js [blob]) name #js{:type (.-type blob)})]
                                                       (upload-s3 (array-seq (clj->js [file]))))))))))))
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
                               (upload-s3 (array-seq (.-files (.-dataTransfer e)))))}
              [:p {:style {:margin "0"}}
               (if @drag-over?
                 "Drop files here"
                 "Click to select files, drag and drop them here, or paste an image/URL")]])]
          (when (seq uploaded-files)
            [:div
             [:p (str "Uploaded " (count uploaded-files) " file(s):")]
             (for [[file-key {:keys [type cache-status file-hash]
                              :as f
                              nme :name}] uploaded-files]
               ^{:key file-key} [:p {:style {:margin-left 20}} (str "• " nme " (" type ")")
                                 (when s3-configured?
                                   (cond
                                     (#{:checking :caching}  cache-status)
                                     [ui/spinner]

                                     (= cache-status :cached)
                                     [open-file-button {:on-open-file #(s3/get-file-open-url+ s3 {:key file-hash})
                                                        :title        "Cached - Open"}]))])



             [:button {:on-click #(swap! !state dissoc :uploaded-files)
                       :style    {:margin-left 10}}
              "Clear All"]])
          [:p
           {:style {:height 50}}
           [:button {:disabled (or (empty? text)
                                   (empty? models)
                                   (some nil?
                                         (->> (vals uploaded-files)
                                              (map :file-hash))))
                     :on-click #(handle-submit
                                  {:db db
                                   :algolia-client algolia-client
                                   :text text
                                   :selected-chat-id selected-chat-id
                                   :chats chats
                                   :models models
                                   :api-keys api-keys
                                   :system-prompts system-prompts
                                   :uploaded-files uploaded-files}
                                  !state)}


            "Run (⌘⏎)"]
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
           [chat-menu (assoc @!state
                        :s3 s3)
            {:on-chat-toggle-hidden (fn [chat-id hidden]
                                      (.transact db
                                                 (.update (aget (.-chats ^js/Object (.-tx db)) chat-id)
                                                          #js{:hidden hidden})))
             :on-chat-select        (fn [selected-chat-id]
                                      (let [{:keys [files text]} (->> chats
                                                                      (filter (comp #{selected-chat-id} :id))
                                                                      util/single)]
                                        (swap! !state assoc
                                               :selected-chat-id selected-chat-id
                                               :text text)))
             :on-response-select    (fn [[selected-chat-id id]]
                                      (swap! !state assoc-in [:selections selected-chat-id] id))
             :on-add-file (when s3-configured?
                            (fn [{nme :name
                                  :as f}]
                              (-> (s3/get-file+ s3 {:key (:file-hash f)})
                                  (.then (fn [response]
                                           (.blob response)))
                                  (.then (fn [blob]
                                           (let [reader (js/FileReader.)
                                                 p (js/Promise.
                                                     (fn [resolve _reject]
                                                       (set! (.-onload reader)
                                                             (fn [_]
                                                               (let [data-url (.-result reader)
                                                                     base64-data (second (clojure.string/split data-url #","))]
                                                                 (resolve base64-data))))))]
                                             (.readAsDataURL reader blob)
                                             p)))
                                  (.then (fn [base64-data]
                                           (swap! !state update :uploaded-files
                                                  merge {nme (assoc f :base64 base64-data)}))))))
             :on-open-file (partial s3/get-file-open-url+ s3)}]]]]))))

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
                                                    :api-key algolia-api-key}
                                          :s3 {:endpoint (js/localStorage.getItem "s3-endpoint")
                                               :bucket (js/localStorage.getItem "s3-bucket")
                                               :access-key-id (js/localStorage.getItem "s3-access-key-id")
                                               :secret-access-key (js/localStorage.getItem "s3-secret-access-key")
                                               :sharing-bucket (js/localStorage.getItem "s3-sharing-bucket")
                                               :sharing-url (js/localStorage.getItem "s3-sharing-url")})))
       :component-will-unmount (fn []
                                 (let [{:keys [unsubscribe]} @!ref-state]
                                   (when unsubscribe
                                     (unsubscribe))))
       :reagent-render         (fn []
                                 (let [{:keys
                                        [instantdb-app-id s3]
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

                                       "Import to Algolia"]]
                                     [:div
                                      (for [{:keys [title key secret? hint]} [{:title   "S3 endpoint"
                                                                               :key     :endpoint
                                                                               :hint "S3: https://s3.[region].amazonaws.com R2: https://[account-id].r2.cloudflarestorage.com"
                                                                               :secret? false}
                                                                              {:title "S3 bucket"
                                                                               :key :bucket
                                                                               :secret? false}
                                                                              {:title "S3 access key ID"
                                                                               :key :access-key-id
                                                                               :secret? false}
                                                                              {:title "S3 secret access key"
                                                                               :key :secret-access-key
                                                                               :secret? true}
                                                                              {:title "S3 sharing bucket"
                                                                               :key :sharing-bucket}
                                                                              {:title "Sharing base-url"
                                                                               :key :sharing-url}]]
                                        ^{:key (str "s3-" (name key))}
                                         [:div {:style {:display :flex}} (str title ": ")
                                          [ui/edit-field {:secret? secret?
                                                          :on-save (fn [s]
                                                                     (if (seq s)
                                                                       (do
                                                                         (js/localStorage.setItem (str "s3-" (name key)) s)
                                                                         (swap! !state assoc-in [:s3 key] s))
                                                                       (do
                                                                         (js/localStorage.removeItem (str "s3-" (name key)))
                                                                         (swap! !state update :s3 dissoc key))))
                                                          :value   (get s3 key)}]
                                          (when hint
                                            [:div {:style {:margin-left 5}} [:small [:i hint]]])])]]
                                    [app {:db             db
                                          :algolia-client algolia-client}
                                     !state]]))})))

(defn ^:dev/after-load main []
  (.render root (r/as-element [instantdb-view])))

(comment
  (s3/create-client (:s3 @!state)))