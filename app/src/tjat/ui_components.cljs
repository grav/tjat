(ns tjat.ui-components
  (:require [reagent.core :as r]
            [react-color :as react-color]
            [clojure.string :as s]
            [tjat.util :as util]
            [tjat.markdown :as md]))

(def sketch-picker
  (r/adapt-react-class react-color/SketchPicker))

(defn color-picker [{:keys [color on-save editing?]}]
  (let [color (or color "black")]
    (if editing?
      [sketch-picker
       {:color color
        :on-change-complete #(on-save (.-hex %))}]
      [:div {:style {:border "1px solid black"
                     :display :inline-block
                     :width 50
                     :height 20
                     :background-color color}}])))

(defn auto-complete [{:keys [value]}]
  (let [state (r/atom {:value (or value "")})]
    (fn [{:keys [values on-add]}]
      (let [{:keys [value]} @state]
        [:div
         [:input {:type      :text
                  :value     value
                  :on-change #(swap! state assoc :value (.-value (.-target %)))}]
         (when-not (empty? value)
           (let [auto (->> values
                           (filter #(s/starts-with? (s/lower-case %) (s/lower-case value)))
                           (remove #(= (s/lower-case %) (s/lower-case value)))
                           first)]
             [:span
              (when auto
                [:button {:on-click (fn [_]
                                      (swap! state dissoc :value)
                                      (on-add auto))}
                 auto])
              (when (not= value auto)
                [:button {:on-click (fn [_]
                                      (swap! state dissoc :value)
                                      (on-add value))}
                 (str "Create '" value "'")])]))]))))

(defn edit-button [{:keys [on-click]}]
  [:button {:on-click on-click} "✎"])

(defn spinner []
  [:div {:style {:width           "100%"
                 :display         :flex
                 :justify-content :center}}
   [:div {:style {:border        "4px solid #f3f3f3"
                  :border-top    "4px solid #3498db"
                  :border-radius "50%"
                  :height 20
                  :width 20
                  :animation     "spin 2s linear infinite"}}]])

(defn progress-button []
  (let [state (r/atom nil)]
    (r/create-class
      {:component-will-receive-props (fn [this [_ opts]]
                                       (let [{:keys [progress]} (r/props this)
                                             {progress' :progress} opts]
                                         (when (and (< progress 1)
                                                    (= progress' 1))
                                           (swap! state assoc
                                                  :spinning-ended true
                                                  :callback (js/setTimeout
                                                              #(swap! state assoc :spinning-ended false)
                                                              750)))))


       :reagent-render
                                     (fn [{:keys [progress on-click progress-type disabled]
                                           :or {progress-type :spinner}} title]
                                       (let [{:keys [spinning-ended]} @state]
                                         [:button {:on-click on-click
                                                   :style    {:height 40
                                                              :width  70}
                                                   :disabled (or
                                                               disabled
                                                               (< progress 1)
                                                               spinning-ended)}

                                          (if-not (< progress 1)
                                            (if spinning-ended
                                              [:span {:style {:font-size 20}}
                                               "✓"]
                                              title)
                                            (case progress-type
                                                  :progress [:progress
                                                             {:style {:width "100%"}
                                                              :value (* 100 progress)
                                                              :max 100}]
                                                   [spinner]))]))})))

(defn rel-dim [v]
  (str "calc(100% - " v "px)"))

(defn tag [{:keys [on-click]} text]
  [:div {:on-click (when on-click
                     on-click)
         :style    {:margin           2
                    :cursor           (when on-click
                                        :pointer)
                    :border-radius    25
                    :padding          5
                    :background-color :lightblue}}
   text])

(defn editable-field [_]
  (let [h 20
        state (r/atom nil)]
    (fn [{v-original :value
          on-save    :on-save
          :keys      [placeholder input-type allow-empty-string required
                      on-delete value-type]
          :or        {on-save            #(js/console.warn "missing handler")
                      allow-empty-string false
                      required false
                      value-type :markdown}}]
      (let [{:keys [editing]} @state]
        [:div {:style {:border     "0.5px dotted"
                       :height     "100%"
                       :box-sizing :border-box}}
         (if-not editing
           [:div.not-editing {:style {:width      "100%"
                                      :height     "100%"
                                      :min-height (* 2 h)
                                      :position   :relative}}
            [:div.paragraf {:style {:height     (rel-dim h)
                                    :color      (if (and (nil? v-original)
                                                         required)
                                                  :red
                                                  :green)
                                    :font-style (when-not v-original
                                                  :italic)}}
             (let [s (or v-original placeholder)]
               [:div {:style {:max-height "100%"
                              :margin 0
                              :overflow-y :hidden}}
                (case value-type
                      :markdown [md/md'->hiccup s]
                  :url [:a {:target "photoop_external_link" :href s} s]
                  :else s)])]
            [:div.buttons {:style {:height   h
                                   :position :absolute
                                   :bottom   0
                                   :display :flex}}
             [edit-button {:on-click (fn [_]
                                       (swap! state assoc :editing (or v-original ""))
                                       (js/setTimeout (fn []
                                                        (when-let [ref (:ref @state)]
                                                          (.focus ref))
                                                        200)))}]
             (when on-delete
               [:button {:on-click (fn [_]
                                     (when (js/confirm "Delete text?")
                                       (on-delete)))}
                "\uD83D\uDDD1"])]]
           [:div.editing {:style {:height     "100%"
                                  :min-height (* 2 h)
                                  :width      "100%"
                                  :position   :relative}}
            [:div.edit-field {:style {:height (rel-dim h)
                                      :width  "100%"}}
             (if (= input-type :textarea)
               [:textarea {:ref       #(swap! state assoc :ref %)
                           :style     {:padding 5
                                       :width   "100%"
                                       :height  "100%"
                                       :border  :none}
                           :on-change #(swap! state assoc :editing (.-value (.-target %)))
                           :value     editing}]
               [:input {:ref       #(swap! state assoc :ref %)
                        :style     {:width "100%"}
                        :type      :text
                        :on-change #(swap! state assoc :editing (.-value (.-target %)))
                        :value     editing}])]
            [:div.buttons {:style {:height   h
                                   :position :absolute
                                   :bottom   0}}
             [:button {:on-click #(let [v (when (or allow-empty-string
                                                    (seq editing))
                                            editing)]
                                    (swap! state dissoc :editing)
                                    (on-save v))} "✓"]
             [:button {:on-click #(swap! state dissoc :editing)} "✗"]]])]))))

(defn reorderable-list [{:keys [editable]}]
  (let [state (r/atom nil)
        uuid (str (random-uuid))]
    (fn [{:keys [items item-view on-reorder]
          :or {item-view identity}}]
      (let [es (or (:items @state) items)
            color (if (:idx @state) :gray :black)]
        [:div
         (for [[e idx] (map vector es (range))]
           [:div {:key idx}
            [:div {:draggable     true
                   :style         {:display :inline-block}
                   :on-drag-start (fn [ev]
                                    (let [dt (.-dataTransfer ev)]
                                      (set! (.-dropEffect dt) "move")
                                      (.setData dt uuid nil)
                                      (swap! state assoc :idx idx))
                                    (.stopPropagation ev))
                   :on-drag-over  (fn [ev]
                                    ;; required for drag to work
                                    (when (= uuid (first (js->clj (.-types (.-dataTransfer ev)))))
                                      (.preventDefault ev)))
                   :on-drag-enter (fn [ev]
                                    (when (= uuid (first (js->clj (.-types (.-dataTransfer ev)))))
                                      (swap! state assoc :items (util/swap-elements items (:idx @state) idx))))
                   :on-drag-end   #(swap! state dissoc :idx)
                   :on-drop       (fn [ev]
                                    (when (= uuid (first (js->clj (.-types (.-dataTransfer ev)))))
                                      (let [idx2 (:idx @state)]
                                        (reset! state nil)
                                        (on-reorder (util/swap-elements items idx2 idx))))
                                    (.stopPropagation ev))}

             [:div {:style {:color color}}

              (if editable
                [editable-field {:value e}]
                [item-view e])]]])]))))

(defn- render-tab [t selected?]
  [:h4 {:style {:font-weight (when-not selected? 300)}}
   t])

(defn tab-bar [{:keys [selected
                       orientation
                       change-tab
                       render-tab
                       tab-width
                       key-fn]
                :or   {orientation :vertical
                       render-tab  render-tab
                       key-fn identity}}
               tabs]
  [:div {:style {:display        :flex
                 :width          "100%"
                 :height         "100%"
                 :flex-direction (if (= orientation :vertical)
                                   :column)}}

   [:div {:style (merge
                   (when (= orientation :horizontal)
                     {:width tab-width})
                   (when (= orientation :vertical)
                     {:justify-content :space-around
                      :align-items :center
                      :height          50})
                   {:display          :flex
                    :overflow         :auto
                    :flex-direction   (when (= orientation :horizontal) :column)
                    :background-color "#CCC"})}
    (for [[t _] tabs]
      [:div {:key (key-fn t)
             :on-click (when change-tab
                         #(change-tab (key-fn t)))
             :style    (merge {}
                              (when change-tab
                                {:cursor :pointer}))}
       [render-tab t (= (key-fn t) selected)]])]
   [:div {:style (merge
                   (when (= orientation :vertical)
                     {:height "calc(100% - 50px)"})
                   (when (= orientation :horizontal)
                     {:width (str "calc(100% - " tab-width "px")}))}
    (->> tabs
         (filter (fn [[t _]]
                   (= (key-fn t) selected)))
         util/single
         second)]])
