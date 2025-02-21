(ns tjat.upload
  (:require [promesa.core :as p]
            [reagent.core :as r]
            [tjat.util :as util]
            [cljs.reader]))

(defn send! [args])

(defn upload-file [{:keys [on-progress file-name file-type]
                    :or   {on-progress #()}} file]

  (-> (send! {:method  :post
              :url     (str #_api/api-base-url "/get-upload-url")
              :headers {"x-file-type" file-type
                        "x-file-name" file-name}})
      (p/then (fn [{:keys [body]}]
                (p/promise
                  (fn [resolve reject]
                    (let [url (cljs.reader/read-string body)
                          xhr (js/XMLHttpRequest.)]
                      ;; TODO - can we do this with regular http client?
                      (set! (.-onprogress (.-upload xhr)) #(let [p (.-loaded %)
                                                                 t (.-total %)]
                                                             (on-progress {:loaded p
                                                                           :total  t})))
                      (set! (.-onload xhr) #(resolve file-name))
                      (set! (.-onerror xhr) reject)
                      (doto xhr
                        (.open "PUT" url true)
                        ;(.setRequestHeader "Content-Type" "image/jpeg")
                        (.setRequestHeader "x-file-name" file-name)
                        (.setRequestHeader "Cache-Control" "max-age=604800")
                        ;(.setRequestHeader "X-File-Size" (.-size file))
                        ;(.setRequestHeader "X-File-Type" (.-type file))
                        (.send file)))))))))

(defn scale-image-file [{:keys [width height]} file]
  (p/promise
    (fn [resolve _]
      (let [reader (js/FileReader.)]
        (set! (.-onload reader)
              (fn [ev]
                (let [image (js/document.createElement "img")]
                  (set! (.-onload image)
                        (fn []
                          (let [canvas (js/document.createElement "canvas")
                                r (max (/ width (.-width image))
                                       (/ height (.-height image)))]
                            (set! (.-width canvas) width)
                            (set! (.-height canvas) height)
                            (.drawImage (.getContext canvas "2d") image 0 0 (* r (.-width image)) (* r (.-height image)))
                            (resolve (.toDataURL canvas "image/jpeg")))))
                  (set! (.-src image) (.-result (.-target ev))))))
        (.readAsDataURL reader file)))))

(defn sum-excess-images [cnt uploads]
  (concat (subvec uploads 0 (dec cnt))
          [(->> uploads
                (drop (dec cnt))
                (reduce (fn [{{l :loaded
                               t :total} :progress
                              :as        in}
                             {{:keys [loaded total]} :progress
                              file :file}]
                          (assoc in :progress {:loaded (+ l loaded)
                                               :total  (+ t (or total
                                                                (.-size file)))}))
                        {:file-name :rest
                         :progress  {:loaded 0
                                     :total  0}}))]))

(defn drop-zone [{:keys [debug]}]
  (let [state (r/atom (when debug
                        {:uploads [{:file-name "IMG_1391.jpg"
                                    :progress  {:loaded 381393, :total 381393}
                                    :done true}
                                   #_#_#_#_{:file-name "bar"
                                            :progress  {:loaded 10, :total 30}}
                                   {:file-name "dino.jpg"
                                    :progress  {:loaded 20, :total 30}
                                    :done "dino.jpg"}
                                   {:file-name "buz"
                                    :progress  {:loaded 1, :total 30}}
                                   {:file-name "ding"
                                    :progress  {:loaded 300, :total 300}}]}))
        dim 100
        margin 2
        dim' (- dim margin margin)]
    (fn [{:keys [on-progress on-loaded on-preview on-upload-started on-upload-ended
                 prefix n-width n-height caption]
          :or {on-progress     (fn [i p]
                                 (swap! state assoc-in [:uploads i :progress] p))
               on-loaded       (fn [i file-name]
                                 (swap! state assoc-in [:uploads i :done] file-name))
               on-preview      (fn [i preview]
                                 (js/console.log 'preview preview)
                                 (swap! state assoc-in [:uploads i :show-model] preview))
               on-upload-started #(prn "starting upload ...")
               on-upload-ended #(prn "all done:" %)
               n-width         4
               n-height        2
               caption         "Drop Files to Upload ..."}}]
      [:div
       [:div [:pre (util/spprint @state)]]
       [:div.drop-zone
        (let [{:keys [uploads drag-over]} @state
              cnt (* n-height n-width)]
          [:div {:style {:width  (* n-width dim)
                         :height (* n-height dim)}}
           [:div {:style         {:display          :flex
                                  :flex-wrap        :wrap
                                  :background-color "#ddd"
                                  :height           "100%"
                                  :width            "100%"}
                  :on-drop       (fn [event]
                                   (.preventDefault event)
                                   (.stopPropagation event)
                                   (let [uploads (->> (map (fn [%]
                                                             (let [f (.item (.-files (.-dataTransfer event)) %)
                                                                   [_ extension] (re-matches #".+(\..+)$" (.-name f))]
                                                               {:file      f
                                                                :file-name (str (when prefix (str prefix "/"))
                                                                                (cljs.core/random-uuid)
                                                                                extension)
                                                                :file-type (.-type f)}))
                                                           (range (.-length (.-files (.-dataTransfer event)))))
                                                      (filter (comp #{"image/jpeg" "image/png" "image/tiff"} :file-type))
                                                      vec)]
                                     (doseq [[{:keys [file]} i] (cond->> (map vector uploads (range))
                                                                         (> (count uploads) cnt) (take (dec cnt)))]
                                       (-> (scale-image-file {:width  dim'
                                                              :height dim'}
                                                             file)
                                           (p/then (partial on-preview i))))

                                     (on-upload-started)
                                     (swap! state assoc
                                            :uploads uploads
                                            :drag-over false)
                                     (-> (map-indexed
                                           (fn [i {:keys [file file-name file-type]}]
                                             (-> (upload-file {:file-name   (str "images/" file-name)
                                                               :file-type   file-type
                                                               :on-progress (partial on-progress i)}
                                                              file)
                                                 (p/then (partial on-loaded i))))
                                           uploads)
                                         p/all
                                         (p/then #(on-upload-ended (map :file-name uploads))))))
                  :on-drag-over  (fn [event]
                                   (.preventDefault event)
                                   (swap! state assoc :drag-over true))

                  :on-drag-leave (fn [event]
                                   (.preventDefault event)
                                   (swap! state assoc :drag-over false))}
            (if-not uploads
              [:div {:style {:background-color (when drag-over :gray)
                             :width            "100%"
                             :height           "100%"
                             :display          :flex
                             :align-items      :center
                             :justify-content  :center}}
               [:div
                caption]]
              (for [{:keys                  [file-name show-model done]
                     {:keys [loaded total]} :progress}
                    (cond->> uploads
                             (> (count uploads) cnt) (sum-excess-images cnt))]
                (let [p (if (and loaded total)
                          (/ loaded total)
                          0)]
                  ^{:key file-name}

                  [:div.upload {:style {:width    dim'
                                        :height   dim'
                                        :margin   margin
                                        :position :relative}}
                   [:div.progress {:style {:position :absolute
                                           :display  :flex
                                           :width    "100%"
                                           :height   "100%"}}
                    [:div.loaded {:style {:width  (str (* 100 p) "%")
                                          :height "100%"}}]

                    [:div.remaining {:style {:width            (str (* 100 (- 1 p)) "%")
                                             :height           "100%"
                                             :background-color :gray
                                             :opacity          0.5}}]]
                   (when (or done
                             (and (= file-name :rest)
                                  (= p 1)))
                     [:div.done
                      {:style {:color       :lightgreen
                               :text-shadow "1px 1px 1px #000"
                               :position    :absolute
                               :bottom      0
                               :right       2}}
                      "✔"])
                   [:div.image-container
                    {:style {:overflow :hidden
                             :height   "100%"
                             :width    "100%"}}
                    (if (not= :rest file-name)
                      (if done
                        [:img {:src   nil #_(wu/img-url file-name)
                               :style {:width  :auto
                                       :height "100%"}}]
                        [:img {:src   show-model
                               :style {:width  :auto
                                       :height "100%"}}])
                      [:div {:style {:display         :flex
                                     :align-items     :center
                                     :justify-content :center
                                     :height          "100%"
                                     :width           "100%"}}
                       [:div {:style {:font-size 20
                                      :color     :gray}}
                        (str "+" (- (count uploads)
                                    (dec cnt)))]])]])))]])
        (when debug
          [:pre (util/spprint @state)])]])))
