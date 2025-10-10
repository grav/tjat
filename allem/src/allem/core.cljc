(ns allem.core
  (:require
    [allem.util :as util]
    [clojure.edn :as edn]
    [allem.config]
    [shadow.resource :as rc]))


(defn throw-on-error [{:keys [status body]
                       :as response}]
  (if (>= status 400)
    (throw (ex-info (str "HTTP Error: " status "\n" body)
                    {:status status
                     :body   body
                     :type   :http-error}))
    response))

#_(defn request-with-throw-on-error [& args]
    (-> @(apply client/request args)
        throw-on-error))



;; ollama serve

#_(defn ollama [{:keys [msg model]
                 :or {model "mistral-small:24b"}}]
    (-> (request-with-throw-on-error
          {:headers
           {"content-type" "application/json"}
           :method :post
           :url "http://localhost:11434/api/generate"
           :body (json/json-str
                   {:model model
                    :prompt msg
                    :stream false})})
        :body
        (json/read-str :key-fn keyword)
        :response)

    (comment
      (claude' {:msg "why is the sky blue?"})))
(defn message->content [{:keys [image-fn text-fn]} m]
  (cond
    (string? m)
    (text-fn m)

    (map? m)
    m

    #_#_(instance? java.io.InputStream m)
    (let [is (BufferedInputStream. m)
          mimetype (URLConnection/guessContentTypeFromStream is)]
      (when (str/starts-with? mimetype "image/")
        (.reset is)
        (image-fn {:mime-type mimetype
                   :input-stream is})))

    :else (throw (ex-info (str "unknown content" m) {:m m}))))


(do
  (defn nil-key [m]
    (-> (for [[k v] m
              :when (nil? v)]
          k)
        util/single))
  (nil-key {:role "user"
            :content nil}))

(do
  (defn structure-content
    [{:keys [level1 level2]} content]
    (assoc level1
      (nil-key level1)
      [(assoc level2
         (nil-key level2)
         content)]))
  (structure-content
    {:level1 {:messages nil}
     :level2 {:role "user"
              :content nil}}
    ["foo" "bar"]))

(def config
  (merge-with merge
              (-> (rc/inline "./config.edn")
                  edn/read-string)
              #?(:dev-config (-> (rc/inline "./config_dev.edn")
                                 edn/read-string))))


(defn make-config [{:keys [model provider api-keys]}]
  (let [provider (or provider
                     (-> (get-in config [:models model])
                         first))
        _ (assert provider model)
        {:keys [models]
         :as   config} (get-in config [:providers provider])
        {:keys [model-name post-process body-params]
         :or   {model-name (name model)}} (if model
                                            (get models model)
                                            (first (vals models)))
        config-fns (-> (get allem.config/functions provider)
                       allem.config/normalize-config)]
    (merge
      (dissoc config
              :config
              :models)
      config-fns
      (cond-> {:model        model-name
               ;; TODO - maybe nuke post-process ...
               :post-process (or (some-> post-process #_resolve)
                                 identity)
               :api-key      (get api-keys provider)
               :provider     provider}
              body-params (assoc :body-params body-params)))))


(comment
  (make-config {:model :gemini-1.5-flash}))


(defn apply-config
  [{:keys [message messages] :as config}]
  (assert (not (and message messages)))
  (let [{:keys [model headers-fn url-fn body-params url]
         :as   config} config
        url-fn (or url-fn (constantly url))
        messages (or messages [message])]
    (merge
      config
      {:url     (url-fn config)
       :headers (merge
                  {"content-type" "application/json"}
                  (headers-fn config))
       :body    (merge {:model model}
                       (structure-content config
                                          (for [m messages]
                                            (message->content config m)))
                       body-params)})))
(comment
  (apply-config
    (assoc
      (make-config {:model   :gemini-1.5-flash
                    :api-key "AIzaSyClvjs7oTP2DnZ32XbrthhSdBAalDEs4uc"})

      :message "Why is the sky blue?")
    #_{:messages ["what's in this image?"
                  (FileInputStream. "/Users/grav/Downloads/cat.jpg")]
       :model    :gpt-4o}))

(comment
  (http/send! client
              {:method :get
               :url "https://example.com"}))