(ns allem.config
  (:require [allem.platform :as platform]
            [allem.io :as io]
            [allem.util :as util]
            [clojure.string :as str]))

(def openai-content-structure
  {:level1 {:messages nil}
   :level2 {:role "user"
            :content nil}})

(def bearer-headers-fn
  (fn [{:keys [api-key]}]
    {"Authorization"     (platform/format' "Bearer %s" api-key)}))

(def openai-reply-fn
  #(-> % :choices util/single :message :content))

(defn openai-text-fn [s]
  {:type "text"
   :text s})

(def openai-upload-fn
  (fn [{:keys [mime-type base64-data]}]
    (cond
      (str/starts-with? mime-type "image/")
      {:type "image_url"
       :image_url
       {:url (platform/format' "data:%s;base64,%s"
                    mime-type
                    base64-data)}}
      :else
      (throw (ex-info (str "Unsupported mime-type for upload") {:mime-type mime-type})))))

(def functions
  {:anthropic
   ;;; https://docs.anthropic.com/en/api/messages
   {:headers-fn (fn [{:keys [api-key]}]
                  {"x-api-key"         api-key
                   "anthropic-version" "2023-06-01"
                   "anthropic-dangerous-direct-browser-access" "true"
                   "content-type"      "application/json"})
    :reply-fn   (fn [b]
                  (-> b :content util/single :text))
    :upload-fn (fn [{:keys [mime-type base64-data]}]
                 (cond

                   (str/starts-with? mime-type "image/")
                   {:type "image"
                    :source {:type "base64"
                             :media_type mime-type
                             :data base64-data}}

                   (= mime-type "application/pdf")
                   {:type "document"
                    :source {:type "base64"
                             :media_type mime-type
                             :data base64-data}}

                   :else
                   (throw (ex-info (str "Unsupported mime-type for upload") {:mime-type mime-type}))))}

   :gemini {:headers-fn {}
            :url-fn (fn [{:keys [api-key model]}]
                      (platform/format'
                        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
                        model api-key))
            :level1 {:contents nil}
            :level2 {:parts nil}
            :reply-fn #(-> % :candidates util/single :content :parts util/single :text)
            :text-fn #(hash-map :text %)
            :upload-fn (fn [{:keys [mime-type base64-data]}]
                         ;; https://ai.google.dev/gemini-api/docs/vision?lang=rest
                         {:inline_data
                          {:mime_type mime-type
                           :data base64-data}})}
   :randomllm {:upload-fn (fn [& _args])}})

(defn normalize-config [c]
  (merge
    openai-content-structure
    {:headers-fn bearer-headers-fn
     :reply-fn   openai-reply-fn
     :text-fn    openai-text-fn
     :upload-fn openai-upload-fn}
    c))
