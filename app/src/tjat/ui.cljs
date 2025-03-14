(ns tjat.ui
  (:require [reagent.core :as r]
            [tjat.algolia :as a]))

(defn edit-field []
  (let [!state (r/atom nil)
        !button-ref (atom nil)
        !input-ref (atom nil)]
    (fn [{:keys [value on-save secret?]
          :or {secret? true}}]
      (let [{:keys [is-editing? text]} @!state
            text (or text value)]
        [:div
         [:input {:ref #(reset! !input-ref %)
                  :value
                  (cond
                    is-editing? text

                    (and (not secret?) (seq text))
                    text

                    (and secret? (not is-editing?) (seq text))
                    (apply str (repeat (count text) (js/String.fromCodePoint 0x25CF)))

                    (and (not is-editing?) (empty? text))
                    "[none]")
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

(defn error-boundary [_]
  (let [error (r/atom nil)]
    (r/create-class
      {:component-did-catch (fn [this e info])
       :get-derived-state-from-error (fn [e]
                                       (reset! error e)
                                       #js {})
       :reagent-render (fn [comp]
                         (if @error
                           [:div
                            (str "Something went wrong in '" (.-name ^js/Function (first comp)) "'")
                            [:button {:on-click #(reset! error nil)} "Try again"]]
                           comp))})))

(defn spinner []
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
     :stroke-width "4"}]])

(defn search []
  (let [!state (r/atom nil)]
    (r/create-class
      {:component-will-unmount (fn []
                                 (let [{:keys [search-timer]} @!state]
                                   (when search-timer
                                     (js/clearTimeout search-timer))))
       :reagent-render           (fn [{:keys [algolia-client on-search]
                                       :or {on-search println}}]
                                   (let [{:keys [search search-timer loading]} @!state]
                                     [:div
                                      [:input {:type      :text
                                               :value     search
                                               :on-change (fn [e]
                                                            (when search-timer
                                                              (js/clearTimeout search-timer))
                                                            (let [search (.-value (.-target e))]
                                                              (when (empty? search)
                                                                (on-search nil))
                                                              (swap! !state assoc :search (.-value (.-target e))
                                                                     :search-timer (js/setTimeout
                                                                                     (fn []
                                                                                       (js/clearTimeout search-timer)
                                                                                       (let [{:keys [search]} @!state]
                                                                                         (when (seq search)
                                                                                           (swap! !state assoc :loading true)
                                                                                           (-> (.search ^js/Object algolia-client
                                                                                                        (clj->js {:requests [{:indexName a/index-name-chats
                                                                                                                              :query     search}
                                                                                                                             {:indexName a/index-name-responses
                                                                                                                              :query search}]}))
                                                                                               (.then (fn [r]
                                                                                                        (let [{[{chats :hits} {responses :hits}] :results} (->> (js->clj r :keywordize-keys true))]
                                                                                                          (js/console.log chats responses)
                                                                                                          (swap! !state assoc :loading false)
                                                                                                          (on-search {:responses (->> responses
                                                                                                                                      (map :id)
                                                                                                                                      set)
                                                                                                                      :chats     (set (concat (map :id chats)
                                                                                                                                              (map :chat_id responses)))}))))))))

                                                                                     500))))}]
                                      [:button {:on-click (fn [_]
                                                            (swap! !state dissoc :search)
                                                            (on-search nil))} "X"]
                                      (when loading
                                        [spinner])]))})))