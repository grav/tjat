(ns tjat.core
  (:require ["react-dom/client" :refer [createRoot]]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            ["showdown" :as showdown]))

(defonce root (createRoot (gdom/getElement "app")))

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

(defn api-key-edit []
  (let [!state (r/atom nil)
        !button-ref (atom nil)
        !input-ref (atom nil)]
    (fn [{:keys [value on-save]}]
      (let [{:keys [is-editing? text]} @!state
            text (or text value)]
        [:div
         [:input {:ref #(reset! !input-ref %)
                  :value     (if is-editing?
                               text
                               (if text
                                 (apply str (repeat (count text) (js/String.fromCodePoint 0x25CF)))
                                 "[none]"))
                  :on-change (fn [e]
                               (swap! !state assoc :text (.-value (.-target e))))
                  :on-focus #(swap! !state assoc :is-editing? true)

                  :on-key-down #(when (= "Enter" (.-key %))
                                  (on-save text)
                                  (.blur (.-target %)))
                  :on-blur (fn [e]
                             (when (not= @!button-ref (.-relatedTarget e))
                               (swap! !state assoc :is-editing? false :text nil)))}]

         (when is-editing?
           [:button {:ref         #(reset! !button-ref %)
                     :on-blur     (fn [e]
                                    (when (not= @!input-ref (.-relatedTarget e))
                                      (swap! !state assoc :is-editing? false :text nil)))
                     :on-key-down #(when (= "Escape" (.-key %))
                                     (.blur (.-target %)))
                     :on-click (fn [_]
                                 (on-save text)
                                 (swap! !state assoc :is-editing? false :text nil))}
            "Save"])]))))

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
    (fn []
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text answer model api-keys loading]
             :or   {model    (first all-models)
                    api-keys api-keys-persisted}} @!state
            {:keys [provider]} (allem.core/make-config {:model model})]
        [:div
         #_[:pre (util/spprint @!state)]
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
              [api-key-edit {:on-save (fn [k]
                                        (let [api-keys (if (seq k)
                                                         (merge api-keys
                                                                {provider k})
                                                         (dissoc api-keys provider))]
                                          (swap! !state assoc :api-keys api-keys)
                                          (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                             :value   (get api-keys provider)}]]])

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
                                               (let [id (random-uuid)]
                                                 ;; TODO: potentially do this in one 'swap'
                                                 (swap! !state update-in [:chats text] conj {:id id
                                                                                             :model model
                                                                                             :time (js/Date.)
                                                                                             :response v})
                                                 (swap! !state assoc-in [:selections text] id)
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
          [chat-menu @!state
           {:on-chat-select (fn [selected-chat]
                              (swap! !state assoc
                                     :selected-chat selected-chat
                                     ;; TODO - this might be a bit aggressive ...
                                     :text selected-chat))
            :on-response-select (fn [[selected-chat id]]
                                  (swap! !state assoc-in [:selections selected-chat] id))}]]]))))

#_(defn testit []
    [:div
     [upload/drop-zone]])

(defn ^:dev/after-load main []
  (.render root (r/as-element [app])))
