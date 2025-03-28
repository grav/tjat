(ns tjat.components.chat-menu
  (:require [reagent.core :as r]
            [allem.util :as util]
            [tjat.components.response :refer [response-tabs response-view]]))

(defn chat-menu 
  "Component for displaying chat history and responses"
  []
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