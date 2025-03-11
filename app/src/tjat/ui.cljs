(ns tjat.ui
  (:require [reagent.core :as r]))

(defn secret-edit-field []
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
       :reagent-render           (fn [{:keys [supabase-client]}]
                                   (let [{:keys [search search-timer loading]} @!state]
                                     [:div
                                      [:input {:type      :text
                                               :value     search
                                               :on-change (fn [e]
                                                            (when search-timer
                                                              (js/clearTimeout search-timer))
                                                            (swap! !state assoc :search (.-value (.-target e))
                                                                   :search-timer (js/setTimeout
                                                                                   (fn []
                                                                                     (js/clearTimeout search-timer)
                                                                                     (let [{:keys [search]} @!state]
                                                                                       ;; from https://supabase.com/docs/guides/database/full-text-search?queryGroups=language&language=js
                                                                                       ;; const { data, error } = await supabase.from('books').select().textSearch('title', `'Harry'`)
                                                                                       (when (seq search)
                                                                                         (println "Searching .. " search)
                                                                                         (swap! !state assoc :loading true)
                                                                                         (-> (-> ^js/Object supabase-client
                                                                                                 (.from "responses")
                                                                                                 (.select "id")
                                                                                                 (.textSearch "text" (str "'" search "'")))
                                                                                             (.then (fn [res]
                                                                                                      (swap! !state assoc :loading false)
                                                                                                      (js->clj (.-data res))))
                                                                                             (.catch js/console.error)))))

                                                                                   500)))}]
                                      (when loading
                                        [spinner])]))})))