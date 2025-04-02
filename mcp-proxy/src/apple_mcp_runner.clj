(ns apple-mcp-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn start-process
  "Starts the apple-mcp process with the given command."
  []
  (let [pb (ProcessBuilder. ["/Users/mikkelriiskjaergravgaard/.bun/bin/bunx" "apple-mcp@latest"])
        process (.start pb)]
    process))

(defn send-message
  "Sends a message to the process via stdin."
  [process message]
  (with-open [writer (io/writer (.getOutputStream process))]
    (.write writer (str message "\n"))
    (.flush writer)))

(defn read-output
  "Reads and prints the output from the process."
  [process]
  (with-open [reader (io/reader (.getInputStream process))]
    (doseq [line (line-seq reader)]
      (println line))))

(defn main
  "Main function to run the apple-mcp process and interact with it."
  []
  (println "Starting apple-mcp process...")
  (let [process (start-process)]
    (future (read-output process))                          ; Start a thread to read and print output

    (println "Process started. Type your messages (or 'quit' to exit):")
    (loop []
      (print "> ")
      (flush)
      (let [input (read-line)]
        (if (= input "quit")
          (do
            (.destroy process)
            (println "Process terminated. Goodbye!"))
          (do
            (send-message process input)
            (recur)))))))

