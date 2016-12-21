(def project 'weathergen)

(set-env!
 :dependencies '[[org.clojure/clojure       "1.9.0-alpha14"]
                 [org.clojure/clojurescript "1.9.293"]
                 [adzerk/boot-cljs          "1.7.228-2"]
                 [adzerk/boot-reload        "0.4.13"]
                 [hoplon/hoplon             "6.0.0-alpha17"]
                 [org.clojure/core.async    "0.2.395"
                  :exclusions [org.clojure/tools.reader]]
                 [tailrecursion/boot-jetty  "0.1.3"]
                 ;; [org.clojure/tools.nrepl "0.2.12" :scope "test"]
                 [cljsjs/jquery-ui "1.11.4-0"]
                 [org.clojure/data.csv "0.1.3"]
                 ;; TODO: Update to later version
                 [com.cognitect/transit-cljs "0.8.239"]
                 [com.taoensso/timbre "4.7.4"]
                 ;;[secretary "1.2.3"]
                 ;;[funcool/cuerdas "2.0.0"]
                 ;;[com.cemerick/url "0.1.1"]

                 [cljsjs/filesaverjs "1.3.3-0"]
                 ;; Had to download as the cljsjs one is pretty far out of date
                 ;;[cljsjs/jszip "2.5.0-0"]
                 [cljsjs/pako "0.2.7-0"]
                 [cljsjs/tinycolor "1.3.0-0"]
                 [garden "1.3.2"]

                 [clojure-complete "0.2.4" :scope "test"]]
 :source-paths #{"src"}
 :asset-paths  #{"assets"})

(require
 '[adzerk.boot-cljs         :refer [cljs]]
 #_'[adzerk.boot-cljs-repl   :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload       :refer [reload]]
 '[hoplon.boot-hoplon       :refer [hoplon prerender]]
 '[tailrecursion.boot-jetty :refer [serve]]
 'complete.core)

(deftask dev
  "Build weathertest for local development."
  []
  (comp
    (watch)
    (speak)
    (hoplon)
    ;;(reload)  ; Doesn't work with web workers
    (cljs)
    (serve :port 8006)))

#_(deftask dev-repl
  []
  (comp
   (watch)
   (speak)
   (cljs-repl)
   (cljs)))

(deftask prod
  "Build weathertest for production deployment."
  []
  (comp
    (hoplon)
    (cljs :optimizations :advanced
          :compiler-options {:externs ["externs.js"]})
    (target :dir #{"target"})))

(defn run-repl-server
  [port]
  (clojure.core.server/start-server {:port port
                                     :name (name project)
                                     :accept 'clojure.core.server/repl
                                     :daemon false}))

(deftask repl-server
  "Run a REPL socket server"
  [p port PORT int "Port to run the server on. Defaults to 5555."]
  (let [port (or port 5559)]
    (run-repl-server port)
    (println "Server is running on port" port)
    (Thread/sleep Long/MAX_VALUE)))
