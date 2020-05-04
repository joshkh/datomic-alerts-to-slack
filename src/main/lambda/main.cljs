(ns lambda.main
  (:require [clojure.string :as str]
            [cljs.pprint :refer [pprint]]
            ["https" :as https]
            ["zlib" :as zlib]))

(defn log
  "A shorter js/console.log"
  [& args] (apply js/console.log args))

(defn env
  "Get a value from a Node.js runtime Environment Variable"
  ([key]
   (aget js/process "env" key))
  ([key default]
   (or (aget js/process "env" key) default)))

(defn cloudwatch->clj
  [event]
  (-> event
      (js->clj :keywordize-keys true) ; convert js event obj to clj
      :awslogs ; CloudWatch logs
      :data ; base64 encoded data
      (js/Buffer.from "base64") ; decode
      (zlib/gunzipSync) ; decompress
      (.toString "utf8") ; stringify
      (js/JSON.parse) ; parse previously encoded JSON obj
      (js->clj :keywordize-keys true) ; convert js obj to clj
      ))

(defn link-to-logstream
  "Builds a Slack mrkdwn link to a LogStream"
  [aws-region logGroup logStream timestamp]
  (str "<"
       "https://" aws-region ".console.aws.amazon.com/cloudwatch/home"
       "?region=" aws-region "#logEventViewer"
       ":group=" logGroup
       ";stream=" logStream
       ";reftime=" timestamp
       ";filter=%257B%2524.Type%2520%253D%2520%2522Alert%2522%257D"
       "|AWS Log Stream>"))


(defn send-message-to-slack
  "Build an HTTP request to send a message to Slack"
  [webhook-path message callback]
  (let [request (.request https (clj->js {:hostname "hooks.slack.com"
                                          :method   "POST"
                                          :path     webhook-path})
                          (fn [^js response]
                            (log "Slack Webhook status code: " (aget response "statusCode"))
                            (.on response "data" (fn [_data] (callback nil "OK")))))]

    (.write request (js/JSON.stringify (clj->js message)))
    (.end request)))

(defn handler
  "Accept an event which represents an addition to a LogStream, and post the entries to a Slack channel."
  [event _context callback]

  ; Log the raw event sent to the Lambda (useful for debugging)
  (log "Event: " event)

  (let [config {:slack-webhook-path (env "SLACK_WEBHOOK_PATH"
                                         ; default value for local development:
                                         "/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX")
                :aws-region         (env "AWS_REGION"
                                         ; default value for local development:
                                         "us-east-1")}

        ; Parse the encoded CloudWatch logs from the Lambda input
        {:keys [logGroup logStream logEvents]} (cloudwatch->clj event)

        ; Build a Slack message (see https://api.slack.com/block-kit)
        block  {:text   ":rotating_light: Datomic Cloud Alert"
                :blocks (-> []

                            (conj {:type "section"
                                   :text {:type "mrkdwn"
                                          :text ":rotating_light: New Log Events"}})

                            (concat (->> logEvents
                                         (take 48) ; Slack has a limit of 50 blocks per POST
                                         (mapcat (fn [{:keys [timestamp _id message] :as log-stream-entry}]
                                                   (let [{:keys [Msg Ex Env]} (try
                                                                                (js->clj (js/JSON.parse message) :keywordize-keys true)
                                                                                (catch js/Object e
                                                                                  ; We expected a JSON message, but in case
                                                                                  ; it wasn't then return the message as a string
                                                                                  {:Msg "Unknown"
                                                                                   :Ex  message}))]

                                                     [{:type "divider"}
                                                      {:type   "section"
                                                       :fields [{:type "mrkdwn"
                                                                 :text (str ":cloud: *Msg*\n" Msg)}
                                                                {:type "mrkdwn"
                                                                 :text (str ":alarm_clock: *Date*\n" (js/Date. timestamp))}
                                                                {:type "mrkdwn"
                                                                 :text (str ":link: *Link*\n" (link-to-logstream
                                                                                                (:aws-region config)
                                                                                                logGroup
                                                                                                logStream
                                                                                                timestamp))}]}
                                                      {:type "section"
                                                       :text {:type "mrkdwn"
                                                              :text (str "```"
                                                                         ; Blocks have a 3000 character limit, but
                                                                         ; that's probably too much for display purposes.
                                                                         ; Our message already has a link to the
                                                                         ; full Alert in CloudWatch
                                                                         (str/join "" (take 1000 (with-out-str (pprint Ex))))
                                                                         "```")}}])))))
                            vec
                            (conj {:type     "context"
                                   :elements [{:type "mrkdwn"
                                               :text "End of log events"}]}))}]

    ; Send the message to Slack and callback the Lambda function
    (send-message-to-slack (:slack-webhook-path config) block callback)))





