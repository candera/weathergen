(ns weathergen.ui
  (:require [javelin.core
             :as j
             :refer [defc defc= cell cell= dosync with-let ]]
            [hoplon.core
             :as h
             :refer [a button defelem div do! fieldset hr html img input
                     label legend link loop-tpl
                     option p script select span table tbody td thead title tr
                     with-init!]]
            [hoplon.svg :as svg]
            [goog.dom :as gdom]
            [goog.crypt.base64 :as base64]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.style :as gstyle]
            [longshi.core :as fress]
            [weathergen.canvas :as canvas]
            [weathergen.coordinates :as coords]
            [weathergen.database :as db]
            [weathergen.encoding :refer [encode decode]]
            [weathergen.fmap :as fmap]
            [weathergen.help :as help]
            [weathergen.math :as math]
            [weathergen.model :as model]
            ;; [weathergen.route :as route]
            [cljs.core.async :as async
             :refer [<! >! alts! timeout]]
            [cljs.reader :as reader]
            [cljsjs.pako]
            ;;[secretary.core :refer-macros [defroute]]
            [taoensso.timbre :as log
             :refer-macros (log trace debug info warn error fatal report
                                logf tracef debugf infof warnf errorf fatalf reportf
                                spy get-env log-env)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [weathergen.cljs.macros :refer [with-time formula-of]]))

;;; Constants

(def revision 7)

;;; Browser detection

(def agents (let [is-agent? (fn [agent]
                              (-> js/navigator
                                  .-userAgent
                                  (.indexOf agent)
                                  neg?
                                  not))
                  agent-props {:chrome  "Chrome"
                               :ie      "MSIE"
                               :firefox "Firefox"
                               :safari  "Safari"
                               :opera   "op"}]
              (zipmap (keys agent-props)
                      (map is-agent? (vals agent-props)))))

(cond
  (and (:safari agents)
       (not (:chrome agents)))
  (js/alert "WeatherGen does not work well in Safari, due to Safari's atrocious Javascript performance and some weird layout issues. Chrome and Firefox are the recommended/supported browsers.")

  (or (:firefox agents) (:chrome agents))
  (println "Chrome or Firefox detected")

  :else
  (js/alert "Browsers other than Chrome and Firefox are not currently supported by WeatherGen. Some features may not behave as expected. Use of Chrome/Firefox is recommended."))


;;; State

(defc weather-params {:temp-uniformity 0.7
                      :pressure        {:min 29 :max 31}
                      :cell-count      [59 59]
                      :feature-size    10
                      :categories      {:sunny     {:wind   {:min 5 :mean 10 :max 30}
                                                    :temp   {:min 20 :mean 22 :max 24}}
                                        :fair      {:pressure 30
                                                    :wind   {:min 0 :mean 7 :max 20}
                                                    :temp   {:min 18 :mean 21 :max 23}}
                                        :poor      {:pressure 29.85
                                                    :wind   {:min 10 :mean 15 :max 30}
                                                    :temp   {:min 15 :mean 18 :max 21}}
                                        :inclement {:pressure 29.40
                                                    :wind   {:min 15 :mean 25 :max 60}
                                                    :temp   {:min 12 :mean 14 :max 16}}}
                      :turbulence      {:size 1 :power 250}
                      :origin          [1000 1000]
                      :evolution       3600
                      :time            {:offset 1234
                                        :current {:day 1 :hour 5 :minute 0}}
                      :wind-uniformity 0.7
                      :crossfade       0.1
                      :prevailing-wind {:heading 325}
                      :seed            1234
                      :wind-stability-areas [#_{:bounds {:x 16
                                                       :y 39
                                                       :width 6
                                                       :height 4}
                                              :wind {:speed 5
                                                     :heading 0}
                                              :index 0}]
                      :weather-overrides [#_{:location {:x 22
                                                      :y 45}
                                           :radius 10
                                           :falloff 5
                                           :animate? false
                                           :begin {:day 1 :hour 5 :minute 0}
                                           :peak {:day 1 :hour 6 :minute 0}
                                           :taper {:day 1 :hour 8 :minute 0}
                                           :end {:day 1 :hour 9 :minute 0}
                                           :pressure 28.5
                                           :strength 1}]})

(defc movement-params {:step 60
                       :direction {:heading 135 :speed 20}})

(def initial-size (let [window-width (.width (js/$ js/window))
                        window-height (.height (js/$ js/window))]
                    (max 250 (min (- window-width 600)
                                  (- window-height 140)))))

(defc display-params {:dimensions [initial-size initial-size]
                      :opacity    0.75
                      :display    :type
                      :map        :korea
                      :mouse-mode :select
                      :overlay    :wind
                      :pressure-unit :inhg})

(defc selected-cell nil)

(defc max-time nil)

;; TODO: This can go away if we introduce a single-input way to input
;; time.
(defc time-params
  "Holds the time that's in the 'jump/set to time' boxes because we
  need to retain a temporary value while people are making up their
  minds."
  {:displayed {:day 1 :hour 5 :minute 0}})

(defc weather-data nil)

(defc weather-data-params nil)

(defc computing true)

;; (defc route nil)

;;; Formulas

(defc= selected-cell-weather (get weather-data (:coordinates selected-cell)))

(defc= cell-count (:cell-count weather-params))

(defc= wind-stability-areas
  (->> weather-params
       :wind-stability-areas))

(defc= weather-overrides
  (:weather-overrides weather-params))

(defc= forecast (when selected-cell
                  (let [result (model/forecast
                                (:coordinates selected-cell)
                                weather-params
                                movement-params
                                (math/clamp 5 (/ 360 (:step movement-params)) 15))]
                    result)))

(defc= airbases (->> (db/airbases (:map display-params))
                     (map :name)
                     sort))

(defc= location-type
  (cond
    (-> selected-cell :location not-empty) :named
    (:coordinates selected-cell) :coordinates
    :else :none))

(defc= pressure-unit (:pressure-unit display-params))

(defc= time-from-max
  (when max-time
    (- (model/falcon-time->minutes (:displayed time-params))
       (model/falcon-time->minutes max-time))))

;;; Routes

;; (defroute "/forecast"
;;   []
;;   (do
;;     (log/debug "forecast route")
;;     (reset! route :forecast)))

;;; Worker

(def worker-count 4)

(def workers (let [all-cells (for [x (range 59)
                                   y (range 59)]
                               [x y])]
               (map (fn [n cells]
                      {:worker (js/Worker. "worker.js")
                       :n      n
                       :ch     (async/chan)
                       :cells  cells})
                    (range worker-count)
                    (partition-all (/ (* 59 59) worker-count) all-cells))))

(def command-ch (async/chan (async/dropping-buffer 1)))

(doseq [{:keys [worker ch]} workers]
  (-> worker
      .-onmessage
      (set! #(go (async/>! ch (->> % .-data decode))))))

;; TODO: Handle commands other than "compute weather"
(go-loop []
  (let [[command params] (async/<! command-ch)]
    (doseq [{:keys [cells worker]} workers]
      (.postMessage worker (encode (assoc params
                                          :cells cells))))
    (reset! computing true)
    (.time js/console "compute-weather")
    (with-time "Receive weather results"
      (loop [chs (set (map :ch workers))
             data {}]
        (if (empty? chs)
          (dosync
           (.timeEnd js/console "compute-weather")
           (reset! weather-data data)
           (reset! weather-data-params params))
          (let [[val port] (async/alts! (vec chs))]
            (recur (disj chs port)
                   (merge data val))))))
    (reset! computing false)
    (recur)))

(formula-of
 [weather-params]
 (go
   (async/>! command-ch [:compute-weather weather-params])))

;;; Mutations

;; TODO: Put more of these here

(defn move
  "Advances the weather by `steps` steps, unless that would move past
  the max time."
  [steps]
  (dosync
   (let [minutes        (* steps (:step @movement-params))
         current-time   (-> @weather-params :time :current model/falcon-time->minutes)
         desired-time   (+ current-time minutes)
         new-time       (if @max-time
                          (min (model/falcon-time->minutes @max-time)
                               desired-time)
                          desired-time)
         actual-steps   (/ (- new-time current-time)
                           (:step @movement-params))
         {:keys [time]} (swap! weather-params
                               model/step
                               @movement-params
                               actual-steps)]
     (swap! time-params
            assoc
            :displayed
            (:current time)))))

(defn jump-to-time
  "Adjust the time coordinate to match the displayed time."
  []
  (dosync
   (let [{:keys [time]} (swap! weather-params
                               model/jump-to-time
                               @movement-params
                               (:displayed @time-params))]
     (swap! time-params
            assoc
            :displayed
            (:current time)))))

(defn set-time
  "Adjust the time coordinate so that the current time is adjusted to
  match the displayed time without changing the location in weather
  space."
  []
  (swap! weather-params
         model/set-time
         (:displayed @time-params)))

(defn change-location
  [airbase]
  (if (empty? airbase)
    (reset! selected-cell nil)
    (reset! selected-cell {:location airbase
                           :coordinates (coords/airbase-coordinates
                                         @cell-count
                                         (:map @display-params)
                                         airbase)})))

(defn change-theater
  [theater]
  (dosync
   (swap! selected-cell
          #(if (= :named @location-type)
             nil
             %))
   (swap! display-params assoc :map theater)))

;;; Serialization

(defn save-data
  [blob filename]
  (let [a (gdom/createElement "a")
        _ (-> (gdom/getDocument) .-body (gdom/appendChild a))
        _ (gstyle/showElement a false)
        url (-> js/window .-URL (.createObjectURL blob))]
    (-> a .-href (set! url))
    (-> a .-download (set! filename))
    (.click a)
    (-> js/window .-URL (.revokeObjectURL url))))

(defn save-fmap
  [weather-params weather-data]
  (let [t (get-in weather-params [:time :current])
        [x-cells y-cells] (:cell-count weather-params)
        ;; Since processing is async, it's possible the weather data
        ;; can be out of sync with respect to the weather parameters.
        ;; In that case, we compute synchronously.
        data (if (log/spy (= weather-params weather-data-params))
               weather-data
               (model/weather-grid (assoc weather-params
                                          :cells (for [x (range x-cells)
                                                       y (range y-cells)]
                                                   [x y]))))
        blob (fmap/get-blob data
                            x-cells
                            y-cells)]
    (save-data blob (gstring/format "%d%02d%02d.fmap"
                                    (:day t)
                                    (:hour t)
                                    (:minute t)))))

(defn save-settings
  [_]
  (save-data (js/Blob. #js[(pr-str {:weather-params @weather-params
                                    :movement-params @movement-params
                                    :display-params @display-params
                                    :revision revision})]
                       #js{:type "text/plain"})
             "weathergen-settings.edn"))

(defn load-settings
  [_]
  (let [i (gdom/createElement "input")
        ch (async/chan)]
    (-> i .-type (set! "file"))
    (-> (gdom/getDocument) .-body (gdom/appendChild i))
    (gstyle/showElement i false)
    (-> i .-onchange (set! (fn [e]
                             (async/put! ch e)
                             (async/close! ch))))
    (.click i)
    (go
      (let [e (<! ch)]
        (when-let [file (aget (.. e -target -files) 0)]
          (let [reader (js/FileReader.)]
            (-> reader
                .-onload
                (set! #(let [data (-> %
                                      .-target
                                      .-result
                                      reader/read-string
                                      (model/upgrade revision))]
                         (dosync
                          (let [{:keys [time]} (reset! weather-params (:weather-params data))]
                            (reset! display-params (:display-params data))
                            (reset! movement-params (:movement-params data))
                            (swap! time-params assoc :displayed (:current time)))))))
            (.readAsText reader file)))))))

;;; Help

(let [help-states (cell {})]
  (defelem help [{:keys []} contents]
    (let [id (str (gensym))
          content-id (str "content-" id)
          img-id (str "img-" id)]
      (div
       :class "help"
       (div
        :id content-id
        :fade-toggle (cell= (get help-states id))
        :class "content"
        :click #(swap! help-states assoc id false)
        contents)
       (div
        :id img-id
        :class "img"
        :click #(swap! help-states
                       (fn [hs]
                         (merge (zipmap (keys hs) (repeat false))
                                {id (not (get hs id))})))
        ;; TODO; Don't make the help go away if the place the mouse
        ;; has gone is the help content.
        ;; :mouseout (fn []
        ;;             (go (<! (async/timeout 1000))
        ;;                 (swap! help-states assoc id false)))
        "?")))))

(defn help-for
  [help-path]
  (help (or (get-in help/content help-path)
            (p "Help content has not yet been written for this feature."))))


;;; Utility

(defn remove-nth
  [coll n]
  (vec (concat (take n coll) (drop (inc n) coll))))

(defn invert-map
  [m]
  (zipmap (vals m) (keys m)))

(def map-name->key
  {"Israel" :israel
   "Balkans" :balkans
   "Korea" :korea
   "Kuriles" :kuriles})

(def map-key->name
  (invert-map map-name->key))

(def map-image
  {:korea "images/kto.jpg"
   :balkans "images/balkans.png"
   :israel "images/ito.jpg"
   :kuriles "images/kuriles.png"})

(defn map-image-id
  [map]
  (str "map-image-" (name map)))

(def display-name->key
  {"Weather Type" :type
   "Pressure" :pressure
   "Temperature" :temperature})

(def display-key->name
  (invert-map display-name->key))

(def overlay-name->key
  {"Wind" :wind
   "Pressure" :pressure
   "Temperature" :temperature
   "Weather Type" :type})

(def overlay-key->name
  (invert-map overlay-name->key))

(def type-name->key
  {"Sunny" :sunny
   "Fair" :fair
   "Poor" :poor
   "Inclement" :inclement})

(def type-key->name
  (invert-map type-name->key))

(def pressure-unit-name->key
  {"InHg"     :inhg
   "Millibar" :mbar})

(def pressure-unit-key->name
  (invert-map pressure-unit-name->key))

(def mbar-per-inhg 33.8637526)

(defn mbar->inhg
  [mbar]
  (/ mbar mbar-per-inhg))

(defn inhg->mbar
  [inhg]
  (* inhg mbar-per-inhg))

(defn format-pressure
  "Format a pressure in inches Mercury as appropriate for the unit."
  [inhg unit]
  (if (= :inhg unit)
    (.toFixed inhg 2)
    (-> inhg inhg->mbar (.toFixed 0))))

(defn format-time
  [{:keys [day hour minute]}]
  (gstring/format "%02d/%02d%02d"
                  day
                  hour
                  minute))

;;; Page sections

(defelem control-section
  [attributes children]
  (let [visible (cell (not (:collapsed? attributes)))
        change-visibility #(swap! visible not)]
    (fieldset
     :class "controls"
     (dissoc attributes :title :collapsed? :help-for)
     (legend
      (div
       :click change-visibility
       :class (formula-of [visible]
                          {:toggle true
                           :visible visible})
       "")
      (if-let [h (:help-for attributes)]
        (help-for h)
        [])
      (span
       :click change-visibility
       (:title attributes)))
     (div
      :class "control-visibility-container"
      :toggle visible
      :fade-toggle visible
      (div :class "control-container" children)))))

(defn forecast-section
  [{:keys [forecast-link? limit-time?]
    :as opts}]
  (control-section
   :title "Forecast"
   :help-for [:forecast]
   :id "forecast-section"
   (div
    :id "forecast"
    (formula-of
     [selected-cell
      pressure-unit
      location-type
      airbases
      forecast]
     (let [[x y] (:coordinates selected-cell)]
       (div
        (label :for "locations"
               "Forecast for:")
        (select
         :id "locations"
         :change #(change-location @%)
         (option :selected (not= :named location-type)
                 :value ""
                 (case location-type
                   :coordinates (str "Cell " x "," y)
                   :named ""
                   :none "None selected"))
         (for [ab airbases]
           (option :value ab
                   :selected (= ab (:location selected-cell))
                   ab)))
        (if-not forecast-link?
          []
          (a :href (formula-of
                    [weather-params
                     display-params
                     movement-params
                     time-params]
                    (let [deflate #(.deflate js/pako %)
                          write-buf (-> {:weather-params weather-params
                                         :display-params display-params
                                         :movement-params movement-params}
                                        ;; encode
                                        (fress/write {:handlers fress/clojure-write-handlers}))]
                      (str "forecast.html?data="
                           (-> write-buf
                               longshi.fressian.byte-stream-protocols/get-bytes
                               (.subarray 0 (longshi.fressian.byte-stream-protocols/bytes-written write-buf))
                               deflate
                               (base64/encodeByteArray true)))))
             :target "_blank"
             "Shareable Forecast"))
        (table
         (thead
          (tr (td "Day/Time")
              (td "Weather Type")
              (td "Pressure")
              (td "Temperature")
              (td "Wind Speed")
              (td "Wind Heading")))
         (tbody
          (if-not forecast
            (tr (td :colspan 6
                    "No location is selected. Choose a location from the list, or click on the weather map to select one."))
            (for [[time weather] forecast]
              (tr (td (format-time time))
                  (td (-> weather :type type-key->name))
                  (td (-> weather :pressure (format-pressure pressure-unit)))
                  (td (-> weather :temperature (.toFixed 1)))
                  (td (-> weather :wind :speed (.toFixed 0)))
                  (td (-> weather :wind :heading (.toFixed 0))))))))))))))

;;; Grid interaction

(defn grid-click
  "Handle the mouse clicking on the canvas"
  [e canvas-id cell-count dimensions]
  (let [[width height] dimensions
        [nx ny] cell-count
        canvas (gdom/getElement canvas-id)
        r (.getBoundingClientRect canvas)
        x (-> e .-clientX (- (.-left r)) (/ width) (* nx) int)
        y (-> e .-clientY (- (.-top r)) (/ height) (* ny) int)]
    (reset! selected-cell {:location nil
                           :coordinates [x y]})))

;;; Grid rendering

(def weather-color
  {:sunny [255 255 255 0.25]
   :fair [0 255 0 1]
   :poor [255 255 0 1]
   :inclement [192 0 0 1]
   nil [255 0 0 1]})

(def pressure-map
  {28.5 [192 0 0 1]
   28.9 [192 0 0 1]
   29.3 [255 255 0 1]
   29.5 [0 255 0 1]
   29.9 [0 128 255 1]
   30.2 [255 255 255 1]
   31.0 [255 255 255 1]})

(defn gradient-color
  [color-map val]
  (let [[[low l] [high h]] (->> color-map
                                (into (sorted-map))
                                (partition 2 1)
                                (filter (fn [[[low l] [high h]]]
                                          (<= low val high)))
                                first)]
    (math/vector-interpolate l h val low high)))

(defn pressure-color
  [pressure]
  (let [[r g b a] (gradient-color pressure-map pressure)]
    [(long r) (long g) (long b) a]))

(def temp-map
  {0  [0 0 255 1]
   20 [0 255 0 1]
   40 [255 0 0 1]})

(defn temperature-color
  [temp]
  (let [[r g b a] (gradient-color temp-map temp)]
    [(long r) (long g) (long b) a]))

(defn fill-color
  [display w]
  ;;(println "fill-color" :w w :display display :alpha alpha)
  (case display
    :type (-> w :type weather-color)
    :pressure (-> w
                  :pressure
                  pressure-color)
    :temperature (-> w
                     :temperature
                     temperature-color)))

(defn convert-pressure
  "Return a pressure in inches Mercury given a value and a unit"
  [val unit]
  (if (= :inhg unit)
    val
    (mbar->inhg val)))

(def max-wind-vector-speed 50)

(defn wind-vector-id
  [speed]
  (str "wind-vector-" (math/clamp 5 max-wind-vector-speed (math/nearest speed 5))))

(defn update-wind-layer
  [weather-data display-params]
  (with-time "update-wind-layer"
    (doseq [[[x y] weather] weather-data
            :let [{:keys [speed heading]} (:wind weather)
                  cell (gdom/getElement (str "grid-wind-cell-" x "-" y))]]
      (.setAttribute cell
                     "transform"
                     (str "rotate(" (long heading) ")"))
      (.setAttributeNS cell
                       "http://www.w3.org/1999/xlink"
                       "href"
                       (str "#" (wind-vector-id speed))))))

(defmulti overlay-text
  (fn [weather display-params] (:overlay display-params)))

(defmethod overlay-text :default
  [_ _]
  "")

(defmethod overlay-text :pressure
  [weather display-params]
  (-> weather :pressure (format-pressure (:pressure-unit display-params))))

(defmethod overlay-text :temperature
  [weather _]
  (-> weather :temperature (.toFixed 1)))

(defmethod overlay-text :type
  [weather _]
  (-> weather :type {:sunny "S"
                     :fair "F"
                     :poor "P"
                     :inclement "I"}))

(defmethod overlay-text :coords
  [weather overlay]
  (str (:x weather) "," (:y weather)))

(defn update-text-layer
  [weather-data display-params]
  (with-time "update-text-layer"
    (doseq [[[x y] weather] weather-data
            :let [text (overlay-text weather display-params)
                  cell (gdom/getElement (str "grid-text-cell-" x "-" y))]]
      (-> cell .-innerHTML (set! text)))))

(defn update-overlay
  [weather-data display-params]
  (with-time "update-overlay"
   (condp contains? (:overlay display-params)
     #{:wind}
     (update-wind-layer weather-data display-params)

     #{:type :pressure :temperature}
     (update-text-layer weather-data display-params)
     nil)))

(defn update-primary-layer
  [weather-data display-params]
  (with-time "update-primary-layer"
    (doseq [[[x y] weather] weather-data
            :let [[r g b a] (fill-color (:display display-params) weather)
                  cell (gdom/getElement (str "grid-primary-cell-" x "-" y))]]
      (.setAttribute cell "fill" (str "rgba("
                                      r ","
                                      g ","
                                      b ","
                                      a ")")))))

(defn update-grid-data
  [weather-data display-params]
  (with-time "update-grid-data"
    (do
      (update-primary-layer weather-data display-params)
      (update-overlay weather-data display-params))))

(defn update-grid
  [weather-data display-params]
  (with-time "update-grid"
    (update-grid-data weather-data display-params)))

(def wind-vector-defs
  (svg/defs
    (for [speed (range 5 (inc max-wind-vector-speed) 5)]
      (let [ticks (math/clamp 1 100 (int (/ speed 5)))
            full-tails (int (/ ticks 2))
            half-tail? (odd? ticks)
            scale 1
            offset 0.1
            tail-step 0.18
            tail-slant 0.1
            tail-width (* 0.25 1.5)]
        (svg/g
         :id (wind-vector-id speed)
         ;; TODO: Combine the vector line and the first tail into a
         ;; single stroke so we eliminate the corner artifacts.
         ;; Vector line
         (svg/line
          :attr {:class "wind-vector"}
          :x1 0
          :x2 0
          :y1 (- 0.5 tail-slant)
          :y2 (+ -0.5 tail-slant))

         ;; Full tails
         (svg/g
          :attr {:class "wind-vector full-tail"}
          (for [n (range full-tails)]
            (svg/line :x1 0
                      :y1 (+ (+ -0.5 tail-slant)
                             (* tail-step n))
                      :x2 tail-width
                      :y2 (+ -0.50 (* tail-step n)))))

         ;; Half tails
         (when half-tail?
           (svg/line
            :attr {:class "wind-vector half-tail"}
            :x1 0
            :y1 (+ (+ -0.50 tail-slant)
                   (* tail-step full-tails))
            :x2 (* tail-width 0.5)
            :y2 (+ -0.50 (+ (* tail-step full-tails)
                            (* 0.5 tail-step))))))))))

(defelem grid
  [{:keys [display-params
           selected-cell
           weather-data
           wind-stability-areas
           weather-overrides
           computing
           nx
           ny]
    :as attrs}]
  (let [primary-layer (svg/g
                       :id "primary-layer"
                       :toggle (cell= (-> display-params :display some?))
                       :css (cell= {:opacity (-> display-params :opacity)}))
        wind-overlay (svg/g
                      :id "wind-overlay"
                      :toggle (cell= (-> display-params
                                         :overlay
                                         (= :wind))))
        text-overlay (svg/g
                      :id "text-overlay"
                      :attr (cell= {:class (str "text-overlay "
                                                (-> display-params
                                                    :overlay
                                                    name))})
                      :toggle (cell= (-> display-params
                                         :overlay
                                         #{:pressure :temperature :type})))
        wind-stability-overlay (formula-of
                                [wind-stability-areas]
                                (svg/g
                                 :id "wind-stability-overlay"
                                 (for [area wind-stability-areas]
                                   (let [{:keys [x y width height]} (:bounds area)]
                                     [(svg/rect
                                       :attr {:class "wind-stability-area"}
                                       :x x
                                       :y y
                                       :width width
                                       :height height)
                                      (svg/rect
                                       :attr {:class "wind-stability-area alternate"}
                                       :x x
                                       :y y
                                       :width width
                                       :height height)]))))
        selected-cell-overlay (formula-of
                               [selected-cell]
                               (let [[x y] (:coordinates selected-cell)]
                                 (if (and x y)
                                   (svg/rect
                                    :id "selected-cell-overlay"
                                    :x x
                                    :y y
                                    :width 1
                                    :height 1)
                                   [])))
        weather-overrides-overlay (formula-of
                                   [weather-overrides]
                                   (svg/g
                                    :id "weather-overrides-overlay"
                                    (for [override weather-overrides
                                          :let [{:keys [location radius]} override
                                                {:keys [x y]} location]]
                                      (svg/circle
                                       :attr {:class "weather-override-area"}
                                       :cx (+ x 0.5)
                                       :cy (+ y 0.5)
                                       :r (- radius 0.5)))))]
    (with-let [elem (svg/svg
                     (-> attrs
                         (dissoc :display-params
                                 :selected-cell
                                 :weather-data
                                 :wind-stability-areas
                                 :weather-overrides)
                         (assoc :viewBox (gstring/format "0 0 %d %d" nx ny)
                                :width (cell= (-> display-params :dimensions first))
                                :height (cell= (-> display-params :dimensions second))
                                :attr {"xmlns:xlink" "http://www.w3.org/1999/xlink"
                                       "xmlns" "http://www.w3.org/2000/svg"}))
                     wind-vector-defs
                     ;; TODO: Not working in Firefox/Safari because
                     ;; the image tag doesn't render as
                     ;; self-closing. Not sure how to convince
                     ;; Hoplon to change that.
                     (svg/image
                      :id "map-image"
                      :toggle (cell= (-> display-params :map #{:none nil} not))
                      :xlink-href (cell= (or (-> display-params :map map-image) ""))
                      :x 0
                      :y 0
                      :width nx
                      :height ny)
                     primary-layer
                     wind-overlay
                     text-overlay
                     wind-stability-overlay
                     weather-overrides-overlay
                     selected-cell-overlay)]
      ;; TODO: We're capturing the value of the number of cells, but it
      ;; never changes. One of these days I should probably factor this
      ;; out. Either that or just react to changes in the number of
      ;; cells by re-inserting the child rects.
      (with-init!
        (with-time "Initial create"
          (do
            ;; Primary layer
            (let [frag (.createDocumentFragment js/document)]
              (doseq [x (range nx)
                      y (range ny)]
                (let [r (doto (.createElementNS
                               js/document
                               "http://www.w3.org/2000/svg"
                               "rect")
                          (.setAttribute "id" (str "grid-primary-cell-" x "-" y))
                          (.setAttribute "x" (str x))
                          (.setAttribute "y" (str y))
                          (.setAttribute "width" "1")
                          (.setAttribute "height" "1")
                          (-> .-onclick
                              (set! #(reset! selected-cell {:location nil
                                                            :coordinates [x y]}))))]
                  (gdom/appendChild frag r)))
              (gdom/appendChild primary-layer frag))
            ;; Wind vector layer
            (let [frag (.createDocumentFragment js/document)]
              (doseq [x (range nx)
                      y (range ny)]
                (let [g (doto (.createElementNS
                               js/document
                               "http://www.w3.org/2000/svg"
                               "g")
                          (.setAttribute "transform"
                                         (gstring/format "translate(%f, %f)"
                                                         (+ x 0.5)
                                                         (+ y 0.5))))
                      r (doto (.createElementNS
                               js/document
                               "http://www.w3.org/2000/svg"
                               "use")
                          (.setAttribute "id" (str "grid-wind-cell-" x "-" y)))]
                  (gdom/appendChild g r)
                  (gdom/appendChild frag g)))
              (gdom/appendChild wind-overlay frag))
            ;; Text overlay layer
            (let [frag (.createDocumentFragment js/document)
                  scale 0.03]
              (doseq [x (range nx)
                      y (range ny)]
                (let [t (doto (.createElementNS
                               js/document
                               "http://www.w3.org/2000/svg"
                               "text")
                          (.setAttribute "transform"
                                         ;; TODO: figure out how to center these stupid things
                                         (gstring/format "scale(%f) translate(%f, %f)"
                                                         scale
                                                         (/ (+ x 0.5) scale)
                                                         (/ (+ y 0.9) scale)))
                          (.setAttribute "id" (str "grid-text-cell-" x "-" y))
                          (.setAttribute "text-anchor" "middle"))]
                  (gdom/appendChild frag t)))
              (gdom/appendChild text-overlay frag))))
        (let [display-params* (formula-of
                               [display-params]
                               (dissoc display-params :dimensions :opacity :map))]
          (formula-of
           [weather-data display-params*]
           (update-grid weather-data display-params*)))))))


;;; User Interface

(def ESCAPE_KEY 27)
(def ENTER_KEY 13)

(def colors
  {:invalid       "#c70505"
   :error-message "#c70505"})

(defn two-column
  [left right]
  (div :class "two-column"
       (div :class "left-column" left)
       (div :class "right-column" right)))

(defn conform-nonnegative-integer
  [s]
  (let [n (js/Number. s)
        l (long n)
        valid? (and (int? l)
                    (not (neg? l)))]
    {:valid? valid?
     :message (when-not valid?
                "Must be whole number, greather than or equal to zero.")
     :value l}))

(defn conform-positive-integer
  [s]
  (let [n (js/Number. s)
        l (long n)
        valid? (and (int? l)
                    (pos? l))]
    {:valid? valid?
     :message (when-not valid?
                "Must be an integer greater than zero.")
     :value l}))

(defelem validating-edit
  [{:keys [conform fmt source update width placeholder] :as attrs}
   _]
  (let [attrs (dissoc attrs :source :conform :update :width :fmt :placeholder)
        interim (cell nil)
        parsed (formula-of
                [interim]
                (if interim
                  (conform interim)
                  {:valid? true
                   :value @source}))
        state (cell :set)]
    (div
     attrs
     :css {:position "relative"}
     (input :type "text"
            :placeholder placeholder
            :input #(do
                      (reset! interim @%)
                      (if (and (:valid? @parsed)
                               (= (:value @parsed) @source))
                        (dosync
                         (reset! state :set)
                         (reset! interim nil))
                        (reset! state :editing)))
            :change #(do
                       (let [p @parsed]
                         (log/debug "it changed to " @%)
                         (log/debug "@parsed" @parsed)
                         (when (:valid? p)
                           (update (:value p))
                           (dosync
                            (reset! interim nil)
                            (reset! state :set)))))
            :keyup (fn [e]
                     (log/debug "keyup" (.-keyCode e))
                     (when (= ESCAPE_KEY (.-keyCode e))
                       (dosync
                        (reset! interim nil)
                        (reset! state :set))))
            :css (cell= {"font-style" (if (= state :editing)
                                        "italic"
                                        "")
                         "color" (if (:valid? parsed)
                                   ""
                                   (colors :invalid))
                         "width" (or width "initial")})
            :value (formula-of
                    [interim source]
                    (if interim
                      interim
                      (fmt source))))
     (img :src "images/error.png"
          :title (cell= (:message parsed))
          :css (formula-of
                [parsed]
                {"width" "14px"
                 "vertical-align" "middle"
                 "margin-left" "3px"
                 "opacity" (if (:valid? parsed)
                             "0"
                             "1")})))))

;; TODO: We could consider using a lens here instead of separate
;; source and update
(defelem time-edit
  [{:keys [source update] :as attrs} _]
  (validating-edit
   attrs
   :width "50px"
   :fmt format-time
   :placeholder "dd/hhmm"
   :conform #(let [[all dd hh mm] (re-matches #"(\d+)/(\d\d)(\d\d)" %)
                   day            (->> dd js/Number. long)
                   hour           (->> hh js/Number. long)
                   min            (->> mm js/Number. long)
                   valid?         (and dd hh mm
                                       (int? day)
                                       (int? hour)
                                       (int? min)
                                       (<= 0 hour 23)
                                       (<= 0 min 59))
                   val            {:day    day
                                   :hour   hour
                                   :minute min}
                   over-max?      (and valid?
                                       @max-time
                                       (log/spy
                                        (< (model/falcon-time->minutes @max-time)
                                           (model/falcon-time->minutes val))))]
               {:valid? (and valid? (not over-max?))
                :message (cond
                           (not valid?) "Time must be in the format 'dd/hhmm'"
                           over-max? (str "Time cannot be set later than " (format-time @max-time)))
                :value {:day    day
                        :hour   hour
                        :minute min}})))

(defn edit-field
  ([c path] (edit-field c path {}))
  ([c path opts]
   ;; TODO: Add conversion to/from string and validation
   (let [{:keys [change-fn]} opts]
    (input :type "text"
           :value (cell= (get-in c path))
           :change (if change-fn
                     #(change-fn (js/Number @%))
                     #(swap! c assoc-in path (js/Number @%)))))))

(defn pressure-edit-field
  "Renders an input that will edit a pressure value, in whatever units
  the cell `unit-c` indicates"
  [c path unit-c]
  (input :type "text"
         :value (formula-of
                 [c unit-c]
                 (format-pressure (get-in c path) unit-c))
         :change #(swap! c assoc-in path (-> @%
                                             js/Number
                                             (convert-pressure @unit-c)))))

(defn time-entry
  [c path]
  ;; TODO: Make fancier
  (time-edit
   :source (formula-of [c] (get-in c path))
   :update #(swap! c assoc-in path %)))

(defn button-bar
  []
  (div :class "button-bar"
       :css {"position" "relative"}
       (button :id "enlarge-grid"
               :click #(swap! display-params
                              update
                              :dimensions
                              (fn [[x y]]
                                [(+ x 50) (+ y 50)]))
               :title "Enlarge grid"
               (img :src "images/bigger.png"))
       (button :id "shrink-grid"
               :click #(swap! display-params
                              update
                              :dimensions
                              (fn [[x y]]
                                [(- x 50) (- y 50)]))
               :title "Shrink grid"
               (img :src "images/smaller.png"))
       (span
        :css {"position" "absolute"
              "right" "27px"
              "bottom" "0"}
        "Day/Time: " (formula-of
                      [weather-data-params]
                      (let [t (-> weather-data-params :time :current)]
                        (if t
                          (format-time t)
                          "--/----"))))
       (formula-of
        [computing]
        (if-not computing
          []
          (img :src "images/spinner.gif"
               :width "24"
               :height "24"
               :css {"vertical-align" "bottom"
                     "position" "absolute"
                     "right" "3px"
                     "bottom" "0"})))))

(defn control-layout
  "Lays out controls for a control section"
  [controls]
  (let [field      (fn [& kids] (apply div :class "field" kids))
        field-help (fn [& kids] (apply div :class "field-elem field-help" kids))
        field-input (fn [& kids] (apply div :class "field-elem field-input" kids))
        field-label (fn [& kids] (apply div :class "field-elem field-label" kids))]
    (for [[label selector {:keys [extra type help-base help-path cell] :as opts}]
          controls
          :let [cell (or cell weather-params)]]
      (field
       (field-help (help-for (into [(or help-base :weather-params)]
                                   (or help-path selector))))
       (field-label label)
       (field-input (if (= :pressure type)
                      (pressure-edit-field cell
                                           selector
                                           pressure-unit)
                      (edit-field cell selector))
                    (or extra []))))))

(defn display-controls
  [{:keys [prevent-map-change?]}]
  (let [dropdown (fn [{:keys [k key->name name->key change]}]
                   (select
                    :change (if change
                              #(change (name->key @%))
                              #(swap! display-params assoc k (name->key @%)))
                    (for [name (conj (keys name->key) "None")]
                      (option
                       :value name
                       :selected (cell= (-> display-params
                                            k
                                            key->name
                                            (= name)))
                       name))))
        select-row (fn [{:keys [label k key->name name->key change]} help-path]
                     (tr (td (help-for help-path))
                         (td label)
                         (td (select
                              :change (if change
                                        #(change (name->key @%))
                                        #(swap! display-params assoc k (name->key @%)))
                              (for [name (conj (keys name->key) "None")]
                                (option
                                 :value name
                                 :selected (cell= (-> display-params
                                                      k
                                                      key->name
                                                      (= name)))
                                 name))))))
        field      (fn [& kids] (apply div :class "field" kids))
        field-help (fn [& kids] (apply div :class "field-elem field-help" kids))
        field-input (fn [& kids] (apply div :class "field-elem field-input" kids))
        field-label (fn [& kids] (apply div :class "field-elem field-label" kids))]
    (control-section
     :title "Display controls"
     :id "display-controls-section"
     (if prevent-map-change?
       []
       (field
        (field-help (help-for [:display-controls :map]))
        (field-label "Map")
        (field-input (dropdown {:k :map
                                :key->name map-key->name
                                :name->key map-name->key
                                :change change-theater}))))
     (field
      (field-help (help-for [:display-controls :display]))
      (field-label "Display")
      (field-input (dropdown {:k :display
                              :key->name display-key->name
                              :name->key display-name->key})))
     (field
      (field-help (help-for [:display-controls :overlay]))
      (field-label "Overlay")
      (field-input (dropdown {:label "Overlay"
                              :k :overlay
                              :key->name overlay-key->name
                              :name->key overlay-name->key})))
     (field
      (field-help (help-for [:display-controls :pressure]))
      (field-label "Pressure")
      (field-input (select
                    :change #(do
                               (log/debug "Changing pressure unit to " @%)
                               (swap! display-params
                                      assoc
                                      :pressure-unit
                                      (pressure-unit-name->key @%)))
                    (for [name (keys pressure-unit-name->key)]
                      (option
                       :value name
                       :selected (cell= (-> display-params
                                            :pressure-unit
                                            pressure-unit-key->name
                                            (= name)))
                       name)))))
     (field
      (field-help (help-for [:display-controls :opacity]))
      (field-label "Opacity:")
      (field-input (input {:type "range"
                           :min 0
                           :max 100
                           :value (cell= (-> display-params
                                             :opacity
                                             (* 100)
                                             long))
                           :change #(swap! display-params
                                           assoc
                                           :opacity
                                           (/ @% 100.0))}))))))

(defn weather-parameters
  [_]
  (control-section
   :title "Weather parameters"
   :id "weather-params-section"
   (control-layout
    [["Seed"             [:seed] {:extra (button
                                          :click #(swap! weather-params
                                                         assoc
                                                         :seed
                                                         (+ (rand-int 5000) 100))
                                          "Random")}]
     ["Min pressure"     [:pressure :min] {:type :pressure}]
     ["Max pressure"     [:pressure :max] {:type :pressure}]
     ["Prevailing wind"  [:prevailing-wind :heading]]
     ["Weather heading"  [:direction :heading] {:cell movement-params
                                                :help-base :movement-params}]
     ["Weather speed"    [:direction :speed]   {:cell movement-params
                                                :help-base :movement-params}]])))

(let [indexed-wind-stability-areas (->> weather-params
                                        :wind-stability-areas
                                        (map-indexed vector)
                                        cell=)]
  (defn wind-stability-parameters
    [_]
    (control-section
     :title "Wind stability regions"
     :id "wind-stability-params-section"
     :help-for [:wind-stability-areas]
     (div
      :class "wind-stability-boxes"
      (loop-tpl :bindings [[index area] indexed-wind-stability-areas]
        (div
         :class "wind-stability-params"
         (table
          (tbody
           (tr (td "NW corner")
               (td (edit-field weather-params [:wind-stability-areas @index :bounds :x]))
               (td (edit-field weather-params [:wind-stability-areas @index :bounds :y])))
           (tr (td "Width/height")
               (td (edit-field weather-params [:wind-stability-areas @index :bounds :width]))
               (td (edit-field weather-params [:wind-stability-areas @index :bounds :height])))
           (tr (td "Wind spd/hdg")
               (td (edit-field weather-params [:wind-stability-areas @index :wind :speed]))
               (td (edit-field weather-params [:wind-stability-areas @index :wind :heading])))))
         (button
          :click #(swap! weather-params
                         update
                         :wind-stability-areas
                         (fn [areas]
                           (remove-nth areas @index)))
          "Remove")
         (hr))))
     (button
      :click #(swap! weather-params
                     update
                     :wind-stability-areas
                     (fn [areas]
                       (conj areas
                             {:bounds {:x 0 :y 0 :width 10 :height 10}
                              :wind {:heading 45
                                     :speed 5}
                              :index (count areas)})))
      "Add New"))))

(let [indexed-weather-overrides (formula-of
                                 [weather-params]
                                 (->> weather-params
                                      :weather-overrides
                                      (map-indexed vector)))]
  (defn weather-override-parameters
    [_]
    (control-section
     :title "Weather override regions"
     :id "weather-override-params-section"
     :help-for [:weather-overrides :overview]
     (div
      :class "weather-override-boxes"
      (loop-tpl :bindings [[index override] indexed-weather-overrides]
        (div
         :class "weather-overrides"
         (table
          :id "weather-override-params"
          (tbody
           (tr (td (help-for [:weather-overrides :center]))
               (td :class "override-label" "Center X/Y")
               (td (edit-field weather-params [:weather-overrides @index :location :x]))
               (td (edit-field weather-params [:weather-overrides @index :location :y])))
           (tr (td (help-for [:weather-overrides :radius]))
               (td :class "override-label" "Radius")
               (td (edit-field weather-params [:weather-overrides @index :radius])))
           (tr (td (help-for [:weather-overrides :falloff]))
               (td :class "override-label" "Falloff")
               (td (edit-field weather-params [:weather-overrides @index :falloff])))
           (tr (td (help-for [:weather-overrides :pressure]))
               (td :class "override-label" "Pressure")
               (td (pressure-edit-field
                    weather-params
                    [:weather-overrides @index :pressure]
                    pressure-unit)))
           (tr (td (help-for [:weather-overrides :strength]))
               (td :class "override-label" "Strength")
               (td (edit-field weather-params [:weather-overrides @index :strength])))))
         (let [id (gensym)]
           (div
            :class "weather-override-animate-checkbox"
            (help-for [:weather-overrides :animate?])
            (input :id id
                   :type "checkbox"
                   :checked (:animate? override)
                   :change (fn [_] (swap! weather-params update-in [:weather-overrides @index :animate?] not)))
            (label :for id "Fade in/out?")))
         (table
          :toggle (cell= (:animate? override))
          (for [[label k] [["Begin" :begin]
                           ["Peak" :peak]
                           ["Taper" :taper]
                           ["End" :end]]]
            (tr (td (help-for [:weather-overrides k]) label)
                (td (time-entry weather-params [:weather-overrides @index k])))))
         (button
          :click #(swap! weather-params
                         update
                         :weather-overrides
                         (fn [overrides]
                           (remove-nth overrides @index)))
          "Remove"))))
     (button
      :click #(swap! weather-params
                     (fn [wp]
                       (update wp
                               :weather-overrides
                               (fn [overrides]
                                 (conj overrides
                                       {:location {:x 30
                                                   :y 30}
                                        :radius 8
                                        :falloff 6
                                        :begin (-> wp :time :current)
                                        :peak (-> wp :time :current (model/add-time 60))
                                        :taper (-> wp :time :current (model/add-time 180))
                                        :end (-> wp :time :current (model/add-time 240))
                                        :pressure (-> wp :pressure :min)
                                        :strength 1})))))
      "Add New"))))

(defn weather-type-configuration
  [_]
  (control-section
   :title "Weather type configuration"
   :id "weather-type-configuration-section"
   (table
    :id "category-params"
    (thead
     (tr (td "")
         (td :colspan 2 "Pressure" (help-for [:weather-type-config :pressure]))
         (td :colspan 3 "Wind" (help-for [:weather-type-config :wind]))
         (td :colspan 3 "Temperature" (help-for [:weather-type-config :temp])))
     (tr (map #(apply td %)
              [[""]
               ["From"] ["To"]
               ["Min"] ["Mean"] ["Max"]
               ["Min"] ["Mean"] ["Max"]])))
    (tbody
     (for [category [:sunny :fair :poor :inclement]]
       (tr (td
            :class (str "weather-type " (name category))
            :css {"background-color" (let [[r g b] (weather-color category)]
                                       (str "rgb(" r "," g "," b ")"))}
            (type-key->name category))
           (condp contains? category
             #{:sunny}
             [(td
               :class "derived"
               (formula-of
                [weather-params pressure-unit]
                (-> weather-params
                    (get-in [:categories :fair :pressure])
                    (format-pressure pressure-unit))))
              (td
               :class "derived"
               (formula-of
                [weather-params pressure-unit]
                (-> weather-params
                    (get-in [:pressure :max])
                    (format-pressure pressure-unit))))]

             #{:fair :poor}
             [(td
               :class "derived"
               (formula-of
                [weather-params pressure-unit]
                (->  weather-params
                     (get-in [:categories
                              (if (= :fair category)
                                :poor :inclement)
                              :pressure])
                     (format-pressure pressure-unit))))
              (td (div
                   :class "edit-field"
                   (pressure-edit-field
                    weather-params
                    [:categories category :pressure]
                    pressure-unit)))]

             #{:inclement}
             [(td
               :class "derived"
               (formula-of
                [weather-params pressure-unit]
                (-> weather-params
                    (get-in [:pressure :min])
                    (format-pressure pressure-unit))))
              (td (div
                   :class "edit-field"
                   (pressure-edit-field
                    weather-params
                    [:categories category :pressure]
                    pressure-unit)))])
           (for [param [:wind :temp]
                 metric [:min :mean :max]]
             (td :class (str (name param) " " (name metric))
                 (div :class "edit-field"
                      (edit-field weather-params [:categories category param metric]))))))))))

(defn advanced-controls
  [_]
  (control-section
   :id "advanced-params-section"
   :title "Advanced Controls"
   (control-layout
    [["X Offset"         [:origin 0] {:help-path [:origin :x]}]
     ["Y Offset"         [:origin 1] {:help-path {:origin :y}}]
     ["T Offset"         [:time :offset]]
     ["Evolution"        [:evolution]]
     ["Wind uniformity"  [:wind-uniformity]]
     ["Temp uniformity"  [:temp-uniformity]]
     ["Warp strength"    [:turbulence :power]]
     ["Crossfade"        [:crossfade]]
     ["Zoom"             [:feature-size]]])))

(defn step-controls
  [{:keys [mode]}]
  (control-section
   :id "time-location-params"
   :title "Time controls"
   (table
    (tbody
     (if (= mode :browse)
       (tr (td (help-for [:weather-params :time :falcon-time]))
           (td "Falcon time: ")
           (td (cell= (format-time max-time)))
           (td (button
                :click #(swap! weather-params assoc-in [:time :current] @max-time)
                "Jump to")))
       [])
     (if (= mode :browse)
       (tr (td (help-for [:weather-params :time :browse-time]))
           (td "Time")
           (td (time-entry weather-params [:time :current])))
       (tr (td (help-for [:displayed-time]))
           (td "Time")
           (td (time-entry time-params [:displayed]))
           (td (button
                :click jump-to-time
                "Jump to")
               (button
                :click set-time
                "Set to"))))
     (tr (map td [(help-for [:step])
                  "Step interval"
                  (validating-edit
                   :width "50px"
                   :source (cell= (:step movement-params))
                   :conform conform-positive-integer
                   :update #(swap! movement-params assoc :step %)
                   :placeholder "e.g. 60"
                   :fmt str)]))))
   (button :title "Step back in time"
           :click #(move -1)
           "<< Step Back")
   (formula-of
    [weather-params max-time]
    (if (and max-time
             (<= (-> max-time model/falcon-time->minutes)
                 (-> weather-params :time :current model/falcon-time->minutes)))
      []
      (button :title "Step forward in time"
              :click #(move 1)
              "Step Forward >>")))))

(defn serialization-controls
  [_]
  (control-section
   :id "load-save-controls"
   :title "Load/save"
   (div
    :class "button-container"
    (a :click (fn []
                (save-fmap @weather-params @weather-data)
                (move 1))
       :class "button"
       "Save Current as FMAP")
    "(Steps forward in time)")
   (div
    :class "button-container"
    (cell=
     (let [blob (js/Blob. (clj->js [(pr-str {:weather-params weather-params
                                             :movement-params movement-params
                                             :display-params display-params
                                             :revision revision})])
                          #js{:type "text/plain"})
           url (-> js/window .-URL (.createObjectURL blob))]
       (a :href url
          :download "weathergen-settings.edn"
          :class "button"
          "Save Settings"))))
   (div
    :class "button-container"
    (button :class "button" :click load-settings "Load Settings"))))

(defn debug-info
  []
  #_(div "Route" route))

(defmethod do! :viewBox
  [elem _ value]
  (if (= false value)
    (.removeAttribute elem "viewBox")
    (.setAttribute elem "viewBox" value)))

(defmethod do! :xlink-href
  [elem _ value]
  (if (= false value)
    (.removeAttributeNS elem "http://www.w3.org/1999/xlink" "href")
    (do
      (log/debug "Setting xlink attr")
      (.setAttributeNS elem "http://www.w3.org/1999/xlink" "href" value))))

(defmethod do! :preserveAspectRatio
  [elem _ value]
  (if (= false value)
    (.removeAttribute elem "preserveAspectRatio")
    (.setAttribute elem "preserveAspectRatio" value)))


(defn test-section
  [{:keys []}]
  (time-edit
   :id "test"
   :source (cell= (-> weather-params :time :current))
   :update #(swap! weather-params assoc-in [:time :current] %)))


;;; General layout

(defn head
  []
  (h/head
   (title "WeatherGen")
   (link :href "style.css" :rel "stylesheet" :title "main" :type "text/css")
   (link :href "https://fonts.googleapis.com/css?family=Open+Sans+Condensed:300"
         :rel "stylesheet"
         :type "text/css")))

(defelem body
  [{:keys [] :as attrs}
   contents]
  (h/body
   (div
    :id "app"
    (div :id "titlebar"
           (div :id "words"
                  (span :id "title"
                          "WeatherGen")
                  (span :id "byline"
                          "by"
                          (a :href "http://firstfighterwing.com/VFW/member.php?893-Tyrant"
                               :target "_blank"
                               "Tyrant"))
                  (span :id "helpstring"
                          "Help? Bug? Feature request? Click"
                          (a :href "help.html"
                               :target "_blank"
                               "here")
                          "."))
           (a :href "http://firstfighterwing.com"
                :target "_blank"
                (img :id "winglogo"
                       :src "images/1stVFW_Insignia-64.png")))
    contents)))

(def sections
  {:serialization-controls serialization-controls
   :step-controls step-controls
   :display-controls display-controls
   :weather-parameters weather-parameters
   :forecast-section forecast-section
   :weather-type-configuration weather-type-configuration
   :wind-stability-parameters wind-stability-parameters
   :weather-override-parameters weather-override-parameters
   :advanced-controls advanced-controls
   :test-section test-section})

(defn weather-page
  [& section-infos]
  (html
   (head)
   (body
    (div :class "two-column"
         (div :class "left-column"
              (button-bar)
              (grid :display-params display-params
                    :weather-data weather-data
                    :selected-cell selected-cell
                    :wind-stability-areas wind-stability-areas
                    :weather-overrides weather-overrides
                    :computing computing
                    ;; TODO: Make these reactive, although they never
                    ;; change, so maybe not
                    :nx (first (:cell-count @weather-params))
                    :ny (second (:cell-count @weather-params))))
         (div :class "right-column"
              (for [[section opts] (partition 2 section-infos)]
                ((sections section) opts))))
    (debug-info))))