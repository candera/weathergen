(ns weathergen.worker
  (:require [weathergen.model :as model]
            [weathergen.encoding :refer [encode decode]]
            [cljs.core.async :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [weathergen.cljs.macros :refer [with-time]]))

(defn main
  []
  (set! js/onmessage
        (fn [msg]
          (let [val (->> msg .-data decode)]
            #_(.log js/console
                  "Worker received message")
            (js/postMessage
             (with-time "Compute weather grid"
               (encode (model/weather-grid val))))))))


