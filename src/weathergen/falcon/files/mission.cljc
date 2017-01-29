(ns weathergen.falcon.files.mission
  (:require [clojure.string :as str]
            [octet.core :as buf]
            [taoensso.timbre :as log
             :refer-macros (log trace debug info warn error fatal report
                                logf tracef debugf infof warnf errorf fatalf reportf
                                spy get-env log-env)]
            [weathergen.falcon.constants :refer :all]
            [weathergen.falcon.files :refer :all]
            [weathergen.filesystem :as fs]
            [weathergen.lzss :as lzss]
            [weathergen.util :as util]))

;;; Class Table - classtbl.h

;; Ref: vuentity.h
(def vu-entity
  (buf/spec :id                      buf/uint16
            :collision-type          buf/uint16
            :collision-radius        buf/float
            :class-info              (buf/spec :domain buf/ubyte
                                               :class  buf/ubyte
                                               :type   buf/ubyte
                                               :stype  buf/ubyte
                                               :sptype buf/ubyte
                                               :owner  buf/ubyte
                                               :field6 buf/ubyte
                                               :field7 buf/ubyte)
            :update-rate             buf/uint32
            :update-tolerance        buf/uint32
            :fine-update-range       buf/float
            :fine-update-force-range buf/float
            :fine-update-multiplier  buf/float
            :damage-speed            buf/uint32
            :hitpoints               buf/int32
            :major-revision-number   buf/uint16
            :minor-revision-number   buf/uint16
            :create-priority         buf/uint16
            :management-domain       buf/ubyte
            :transferable            buf/ubyte
            :private                 buf/ubyte
            :tangible                buf/ubyte
            :collidable              buf/ubyte
            :global                  buf/ubyte
            :persistent              buf/ubyte
            :padding                 (buf/repeat 3 buf/byte)))

(def falcon4-entity
  (buf/spec :vu-class-data      vu-entity
            :vis-type           (buf/repeat 7 buf/int16)
            :vehicle-data-index buf/int16
            :data-type          buf/ubyte
            ;; Only a pointer in the sense of indexing into the
            ;; appropriate class table.
            :data-pointer       buf/int32))

(defn read-class-table
  "Given a path to a class table (.ct) file, read,
  parse, and return it."
  [path]
  (let [buf (fs/file-buf path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      (buf/read buf (larray buf/int16 falcon4-entity)))))

(defn read-strings
  "Given paths to the strings.idx and strings.wch files, return
  a function, given an index that will yield the string at that index."
  [idx-path wch-path]
  (let [idx-buf (fs/file-buf idx-path)
        wch-buf (fs/file-buf wch-path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      (let [n (buf/read idx-buf buf/uint16)
            indices (buf/read idx-buf
                              (buf/repeat n buf/uint16)
                              {:offset 2})
            strings (buf/read wch-buf
                              (fixed-string (nth indices (dec n))))]
        (fn [n]
          (subs strings (nth indices n) (nth indices (inc n))))))))

;;; Unit class table

;; This is the one I started with. It's broken, but at least the name
;; winds up in the right place.
#_(def unit-class-data
  ;; Source: entity.h
  (buf/spec :index buf/int16
            :num-elements (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                      buf/int32)
            :vehicle-type  (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                       buf/int16)
            :vehicle-class (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                       (buf/repeat 8 buf/byte))
            :flags buf/int32
            :name (fixed-string 20)
            :movement-type buf/int32
            :movement-speed buf/int16
            :max-range buf/int16
            :fuel buf/int32
            :rate buf/int16
            :pt-data-index buf/int16
            :scores (buf/repeat MAXIMUM_ROLES buf/byte)
            :role buf/byte
            :hit-chance (buf/repeat MOVEMENT_TYPES buf/byte)
            :strength (buf/repeat MOVEMENT_TYPES buf/byte)
            :range (buf/repeat MOVEMENT_TYPES buf/byte)
            :detection (buf/repeat MOVEMENT_TYPES buf/byte)
            :damage-mod (buf/repeat (inc OtherDam) buf/byte)
            :radar-vehicle buf/byte
            :special-index buf/int16
            :padding0 (buf/repeat 2 buf/byte)
            :icon-index buf/int16
            ;; This is a sign that the structure is wrong - just needed to add this to get the alignment right
            :padding (buf/repeat 3 buf/byte)))


(def unit-class-data
  ;; Source: entity.cpp::LoadUnitData and UcdFile.cs
  (buf/spec :index buf/int16
            :num-elements (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                      buf/int32)
            :vehicle-type  (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                       buf/int16)
            :vehicle-class (buf/repeat VEHICLE_GROUPS_PER_UNIT
                                       (buf/repeat 8 buf/ubyte))
            :flags buf/uint32
            :name (fixed-string 20)
            :padding buf/int16
            :movement-type buf/int32
            :movement-speed buf/int16
            :max-range buf/int16
            :fuel buf/int32
            :rate buf/int16
            :pt-data-index buf/int16
            :scores (buf/repeat MAXIMUM_ROLES buf/ubyte)
            :role buf/ubyte
            :hit-chance (buf/repeat MOVEMENT_TYPES buf/ubyte)
            :strength (buf/repeat MOVEMENT_TYPES buf/ubyte)
            :range (buf/repeat MOVEMENT_TYPES buf/ubyte)
            :detection (buf/repeat MOVEMENT_TYPES buf/ubyte)
            :damage-mod (buf/repeat (inc OtherDam) buf/ubyte)
            :radar-vehicle buf/ubyte
            :padding2 buf/byte
            :special-index buf/int16
            :icon-index buf/uint16
            :padding3 (buf/repeat 2 buf/byte)))

(defn read-unit-class-data
  "Given a path to the falcon4.ucd file, read, parse, and return it."
  [path]
  (let [buf (fs/file-buf path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      (buf/read buf (larray buf/uint16 unit-class-data)))))

(def campaign-time
  (reify
    octet.spec/ISpecSize
    (size [_]
      (buf/size buf/uint32))

    octet.spec/ISpec
    (read [_ buff pos]
      (let [[size data] (buf/read* buff buf/uint32 {:offset pos})]
        [size
         (let [d (-> data (/ 24 60 60 1000) long)
               h (-> data (mod (* 24 60 60 1000)) (/ 60 60 1000) long)
               m (-> data (mod (* 60 60 1000)) (/ 60 1000) long)
               s (-> data (mod (* 60 1000)) (/ 1000) long)
               ms (mod data 1000)]
           {:day (inc d)
            :hour h
            :minute m
            :second s
            :millisecond ms})]))

    (write [_ buff pos {:keys [day hour minute second millisecond]}]
      (buf/write! buff buf/int32 (-> day
                                     (* 24)
                                     (+ hour)
                                     (* 60)
                                     (+ minute)
                                     (* 60)
                                     (+ second)
                                     (* 1000)
                                     (+ millisecond))))))

(defn extension
  [file-name]
  (let [i (str/last-index-of file-name ".")]
    (str/upper-case (subs file-name i))))

(defn file-type
  [file-name]
  (get {".CMP" :campaign-info
        ".OBJ" :objectives-list
        ".OBD" :objectives-deltas
        ".UNI" :units
        ".TEA" :teams
        ".EVT" :events
        ".POL" :primary-objectives
        ".PLT" :pilots
        ".PST" :persistent-objects
        ".WTH" :weather
        ".VER" :version
        ".TE"  :victory-conditions}
       (extension file-name)
       :unknown))

(defmulti read-embedded-file*
  (fn [type entry buf database]
    type))

(defn read-embedded-file
  [type entry buf database]
  (log/debug "read-embedded-file"
             :type type
             :file-name (:file-name entry))
  (read-embedded-file* type entry buf database))

(def directory-entry
  (buf/spec :file-name (lstring buf/ubyte)
            :offset buf/uint32
            :length buf/uint32))

(defn find-install-dir
  "Given a path somewhere in the filesystem, figure out which theater
  it's in and return a map describing it."
  [path]
  (loop [dir (fs/parent path)]
    (when dir
      (if (every? #(fs/exists? (fs/path-combine dir %))
                  ["Bin" "Data" "Tools" "User"])
        dir
        (recur (fs/parent dir))))))

(defn campaign-dir
  "Return the path to the campaign directory."
  [installation theater]
  (fs/path-combine (:data-dir installation)
                   (:campaigndir theater)))

(defn object-dir
  "Return the path to the objects directory."
  [installation theater]
  #_(log/debug "object-dir"
             :installation installation
             :theater theater)
  (fs/path-combine (:data-dir installation)
                   (:objectdir theater)))

(defn parse-theater-def-line
  "Parse a line from the theater.tdf file."
  [line]
  (if-let [idx (str/index-of line " ")]
    [(-> line (subs 0 idx) keyword)
     (-> line (subs (inc idx)))]))

(defn read-theater-def
  "Reads the theater TDF from the given path, relative to `data-dir`."
  [data-dir path]
  (->> path
       (fs/path-combine data-dir)
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       (remove #(.startsWith % "#"))
       (map parse-theater-def-line)
       (into {})))

(defn read-theater-defs
  "Read, parse, and return information about the installled theaters
  from the theater list."
  [data-dir]
  (->> "Terrdata/theaterdefinition/theater.lst"
       (fs/path-combine data-dir)
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove #(.startsWith % "#"))
       (remove str/blank?)
       (map #(read-theater-def data-dir %))))

(defn read-image-list
  "Reads an image list file consisting of pairs of names and ids. Returns a seq of those pairs."
  [installation path]
  (->> path
       (fs/path-combine (:data-dir installation))
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       (map #(str/split % #"[ \t]+"))
       (map (fn [[name id]]
              [name (util/str->long id)]))))

(defn read-image-ids
  "Read in the image IDs in this installation. Returns a map with
  keys :id->name and :name->id mapping in each direction."
  [installation]
  (let [pairs (->> (-> installation
                       :art-dir
                       (fs/path-combine "IMAGEIDS.LST"))
                   fs/file-text
                   str/split-lines
                   (map str/trim)
                   (remove str/blank?)
                   (mapcat #(read-image-list installation %)))]
    {:name->id (zipmap (map first pairs) (map second pairs))
     :id->name (zipmap (map second pairs) (map first pairs))}))

(defn load-installation
  "Return information about the installed theaters."
  [install-dir]
  (let [data-dir (fs/path-combine install-dir "Data")
        art-dir (fs/path-combine data-dir "Art")]
    {:install-dir install-dir
     :data-dir data-dir
     :art-dir art-dir
     :theaters (read-theater-defs data-dir)}))

(defn find-theater
  "Given the path to a mission file, return the theater it's in."
  [installation path]
  (->> installation
       :theaters
       (filter #(fs/ancestor? (campaign-dir installation %)
                              path))
       first))

(defn load-database
  "Load all the files needed to process a mission in a given theater."
  [installation theater]
  {:class-table (read-class-table
                 (fs/path-combine
                  (object-dir installation theater)
                  "FALCON4.ct"))
   :unit-class-data (read-unit-class-data
                      (fs/path-combine
                       (object-dir installation theater)
                       "FALCON4.UCD"))
   :image-ids (read-image-ids installation)
   :strings (read-strings (fs/path-combine
                           (campaign-dir installation theater)
                           "Strings.idx")
                          (fs/path-combine
                           (campaign-dir installation theater)
                           "Strings.wch"))})

(defn read-mission
  "Given a path to a mission (.cam/.tac/.trn) file, read,
  parse, and return it."
  [path]
  (let [install-dir  (find-install-dir path)
        installation (load-installation install-dir)
        theater      (find-theater installation path)
        database     (load-database installation theater)
        buf          (fs/file-buf path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      ;; TODO: Make this whole thing into a spec
      (let [dir-offset (buf/read buf buf/uint32)
            dir-file-count (buf/read buf buf/uint32 {:offset dir-offset})
            directory (buf/read buf (buf/repeat dir-file-count directory-entry)
                                {:offset (+ dir-offset 4)})
            files (for [entry directory
                        :let [type (-> entry
                                       :file-name
                                       file-type)]]
                    (assoc entry
                           :type type
                           :data (read-embedded-file type entry buf database)))]
        ;; Making the assumption here that there's exactly one file
        ;; per type
        (assert (->> files
                     (map :type)
                     distinct
                     count
                     (= (count files))))
        {:files (zipmap (map :type files) files)
         :database database
         :installation installation
         :theater theater}))))

;; Common structures
(def vu-id (buf/spec :name buf/uint32
                     :creator buf/uint32))

;; Campaign details file
(def team-basic-info
  (buf/spec :flag buf/ubyte
            :color buf/ubyte
            :name (fixed-string 20)
            :motto (fixed-string 200)))

(def squad-info
  (buf/spec :x                 buf/float
            :y                 buf/float
            :id                vu-id
            :description-index buf/int16
            :name-id           buf/int16
            :airbase-icon      buf/int16
            :squadron-path     buf/int16
            :specialty         buf/ubyte
            :current-strength  buf/ubyte
            :country           buf/ubyte
            :airbase-name      (fixed-string 40)
            :padding           buf/byte))

(def event-node
  (buf/spec :x buf/int16
            :y buf/int16
            :time campaign-time
            :flags buf/ubyte
            :team buf/ubyte
            :padding (buf/repeat 2 buf/byte)
            :event-text buf/int32 ; Pointer - no meaning in file
            :ui-event-node buf/int32 ; Pointer - no meaning in file
            :event-text (lstring buf/uint16)))

(def cmp-spec
  (buf/spec :current-time       campaign-time
            :te-start           campaign-time
            :te-time-limit      campaign-time
            :te-victory-points  buf/int32
            :te-type            buf/int32
            :te-num-teams       buf/int32
            :te-num-aircraft    (buf/repeat 8 buf/int32)
            :te-num-f16s        (buf/repeat 8 buf/int32)
            :te-team            buf/int32
            :te-team-points     (buf/repeat 8 buf/int32)
            :te-flags           buf/int32
            :team-info          (buf/repeat 8 team-basic-info)
            :last-major-event   buf/uint32
            :last-resupply      buf/uint32
            :last-repair        buf/uint32
            :last-reinforcement buf/uint32
            :time-stamp         buf/int16
            :group              buf/int16
            :ground-ratio       buf/int16
            :air-ratio          buf/int16
            :air-defense-ratio  buf/int16
            :naval-ratio        buf/int16
            :brief              buf/int16
            :theater-size-x     buf/int16
            :theater-size-y     buf/int16
            :current-day        buf/ubyte
            :active-team        buf/ubyte
            :day-zero           buf/ubyte
            :endgame-result     buf/ubyte
            :situation          buf/ubyte
            :enemy-air-exp      buf/ubyte
            :enemy-ad-exp       buf/ubyte
            :bullseye-name      buf/ubyte
            :bullseye-x         buf/int16
            :bullseye-y         buf/int16
            :theater-name       (fixed-string 40)
            :scenario           (fixed-string 40)
            :save-file          (fixed-string 40)
            :ui-name            (fixed-string 40)
            :player-squadron-id vu-id
            :recent-event-entries (larray buf/int16 event-node)
            :priority-event-entries (larray buf/int16 event-node)
            :map                (larray buf/int16 buf/ubyte)
            :last-index-num     buf/int16
            :squad-info         (larray buf/int16 squad-info)
            :tempo              buf/ubyte
            :creator-ip         buf/int32
            :creation-time      buf/int32
            :creation-rand      buf/int32))

(defmethod read-embedded-file* :campaign-info
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :uncompressed-size buf/int32)
          {:keys [compressed-size
                  uncompressed-size]} (buf/read buf header-spec {:offset offset})
          ;; For some weird reason, the compressed size includes the field
          ;; for the uncompressed size
          adjusted-compressed-size (- compressed-size (buf/size buf/int32))
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            adjusted-compressed-size
                            uncompressed-size)]
      (into (sorted-map) (buf/read data cmp-spec)))))

;; Objectives file
(defmethod read-embedded-file* :objectives-list
  [_ {:keys [offset length] :as entry} buf _]
  :todo
  #_(binding [octet.buffer/*byte-order* :little-endian]
    (let [num-objectives (buf/read buf buf/int16 {:offset offset})
          uncompressed-size (buf/read buf buf/int32 {:offset (+ offset 2)})
          compressed-size (buf/read buf/int32 {:offset (+ offset 6)})
          data (lzss/expand buf (+ offset 10) compressed-size uncompressed-size)]
      {:compressed-size compressed-size
       :uncompressed-size uncompressed-size
       :data (->> data .array (into []) (take 10))})))

;; Objectives delta file
(def objective-deltas
  (buf/spec :id vu-id
            :last-repair buf/uint32
            :owner buf/ubyte
            :supply buf/ubyte
            :fuel buf/ubyte
            :losses buf/ubyte
            :f-status (larray buf/ubyte buf/ubyte)))

(defmethod read-embedded-file* :objectives-deltas
  [_ {:keys [offset length] :as entry} buf _]
  :todo
  #_(binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :num-deltas buf/int16
                                :uncompressed-size buf/int32)
          {:keys [num-deltas
                  compressed-size
                  uncompressed-size]} (buf/read buf
                                                header-spec
                                                {:offset offset})
          _ (log/debug :num-deltas num-deltas
                       :compressed-size compressed-size
                       :uncompressed-size uncompressed-size)
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            compressed-size
                            uncompressed-size)]
      {:compressed-size compressed-size
       :uncompressed-size uncompressed-size
       :data (->> data .array (into []) (map #(format "0x%02x" %)) (take 20))
       #_:todo #_(buf/read data
                       (buf/repeat num-deltas
                                   objective-delta))})))

;; Victory conditions
(defmethod read-embedded-file* :victory-conditions
  [_ {:keys [offset length] :as entry} buf _]
  (buf/read buf (buf/string length) {:offset offset}))

;; Team definition file

(def team-air-action
  (buf/spec :start campaign-time
            :stop campaign-time
            :objective vu-id
            :last-objective vu-id
            :type buf/ubyte
            :padding (buf/repeat 3 buf/byte)))

(def team-ground-action
  (buf/spec :time campaign-time
            :timeout campaign-time
            :objective vu-id
            :type buf/ubyte
            :tempo buf/ubyte
            :points buf/ubyte))

(def team-status
  (buf/spec :air-defence-vehicles buf/uint16
            :aircraft buf/uint16
            :ground-vehicles buf/uint16
            :ships buf/uint16
            :supply buf/uint16
            :fuel buf/uint16
            :airbases buf/uint16
            :supply-level buf/ubyte
            :fuel-level buf/ubyte))

(def atm-airbase
  (buf/spec :id vu-id
            :schedule (buf/repeat ATM_MAX_CYCLES buf/ubyte)))

(def tasking-manager-fields
  [:id vu-id
   :entity-type buf/uint16
   :manager-flags buf/int16
   :owner buf/ubyte])

(def mission-request
  ;; Ref AirTaskingManager.cs
  (buf/spec
   :requester      vu-id
   :target         vu-id
   :secondary      vu-id
   :pak            vu-id
   :who            buf/ubyte
   :vs             buf/ubyte
   :padding        (buf/repeat 2 buf/byte)
   :tot            buf/uint32
   :tx             buf/int16
   :ty             buf/int16
   :flags          buf/uint32
   :caps           buf/int16
   :target-num     buf/int16
   :speed          buf/int16
   :match-strength buf/int16
   :priority       buf/int16
   :tot-type       buf/ubyte
   :action-type    buf/ubyte
   :mission        buf/ubyte
   :aircraft       buf/ubyte
   :context        buf/ubyte
   :roe-check      buf/ubyte
   :delayed        buf/ubyte
   :start-block    buf/ubyte
   :final-block    buf/ubyte
   :slots          (buf/repeat 4 buf/ubyte)
   :min-to         buf/byte ; Yes, signed
   :max-to         buf/byte ; Yes, signed
   :padding        (buf/repeat 3 buf/byte)))

(def air-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16
                :average-ca-strength buf/int16
                :average-ca-missions buf/int16
                :sample-cycles buf/ubyte
                :airbases (larray buf/ubyte atm-airbase)
                :cycle buf/ubyte
                ;; This differs between the C# and the CPP...
                :mission-requests (larray buf/int16 mission-request)])))

(def ground-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16])))

(def naval-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16])))

(def team
  (buf/spec :id vu-id
            :entity-type buf/uint16
            :who buf/ubyte
            :c-team buf/ubyte
            :flags buf/uint16
            :members (buf/repeat 8 buf/ubyte)
            :stance (buf/repeat 8 buf/int16)
            :first-colonel buf/int16
            :first-commander buf/int16
            :first-wingman buf/int16
            :last-wingman buf/int16
            :air-experience buf/ubyte
            :air-defense-experience buf/ubyte
            :ground-experience buf/ubyte
            :naval-experience buf/ubyte
            :initiatve buf/int16
            :supply-available buf/uint16
            :fuel-available buf/uint16
            :replacements-available buf/uint16
            :player-rating buf/float
            :last-player-mission buf/uint32
            :current-stats team-status
            :start-stats team-status
            :reinforcement buf/int16
            :bonus-objs (buf/repeat 20 vu-id)
            :bonus-time (buf/repeat 20 buf/uint32)
            ;; TODO: These next few should read into something with
            ;; names rather than an array
            :obj-type-priority (buf/repeat 36 buf/ubyte)
            :unit-type-priority (buf/repeat 20 buf/ubyte)
            :mission-priority (buf/repeat 41 buf/ubyte)
            :max-vehicle (buf/repeat 4 buf/ubyte)
            :team-flag buf/ubyte
            :team-color buf/ubyte
            :equipment buf/ubyte
            :name (fixed-string 20)
            :motto (fixed-string 200)
            :ground-action team-ground-action
            :defensive-air-action team-air-action
            :offensive-air-action team-air-action))

(def team-record
  (buf/spec :team team
            :air-tasking-manager air-tasking-manager
            :ground-tasking-manager ground-tasking-manager
            :naval-tasking-manager naval-tasking-manager))

(defmethod read-embedded-file* :teams
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (buf/read buf
              (larray buf/int16 team-record)
              {:offset offset})))

;; Version file
(defmethod read-embedded-file* :version
  [_ {:keys [offset length] :as entry} buf _]
  (let [version-string (buf/read buf (buf/string length) {:offset offset})]
    {:version #?(:clj (Long. version-string)
                 :cljs (-> version-string js/Number. .valueOf long))}))

;; Units file
(def base-fields
  [:id         vu-id
   :type-id    buf/int16
   :x          buf/int16
   :y          buf/int16
   :z          buf/float
   :spot-time  campaign-time
   :spotted    buf/int16
   :base-flags buf/int16
   :owner      buf/ubyte
   :camp-id    buf/int16])

(def waypoint
  (reify
    octet.spec/ISpec
    (read [_ buf pos]
      (read-> buf
              pos
              (constantly (buf/spec
                           :haves        (bitflags buf/ubyte
                                                   {:deptime 0x01
                                                    :target  0x02})
                           :grid-x       buf/int16
                           :grid-y       buf/int16
                           :grid-z       buf/int16
                           :arrive       campaign-time
                           :action       buf/ubyte
                           :route-action buf/ubyte
                           :formation    buf/ubyte
                           :flags        buf/uint32))
              (fn [{:keys [haves]}]
                (if (haves :target)
                  (buf/spec :target-id vu-id
                            :target-building buf/ubyte)
                  (constant {:target-id {:name    0
                                         :creator 0}
                             :target-building 255})))
              (fn [{:keys [haves arrive]}]
                (if (haves :deptime)
                  (buf/spec :depart campaign-time)
                  (constant {:depart arrive})))))

    ;; TODO: Implement write
    ))

(def unit-fields
  (into base-fields
        [:last-check    campaign-time
         :roster        buf/int32
         :unit-flags    (bitflags buf/int32 {:dead        0x1
                                             :b3          0x2
                                             :assigned    0x04
                                             :ordered     0x08
                                             :no-pllaning 0x10
                                             :parent      0x20
                                             :engaged     0x40
                                             :b1          0x80
                                             :scripted    0x100
                                             :commando    0x200
                                             :moving      0x400
                                             :refused     0x800
                                             :has-ecm     0x1000
                                             :cargo       0x2000
                                             :combat      0x4000
                                             :broken      0x8000
                                             :losses      0x10000
                                             :inactive    0x20000
                                             :fragmented  0x40000
                                             ;; Ground unit specific
                                             :targeted    0x100000
                                             :retreating  0x200000
                                             :detached    0x400000
                                             :supported   0x800000
                                             :temp-dest   0x1000000
                                             ;; Air unit specific
                                             :final       0x100000
                                             :has-pilots  0x200000
                                             :diverted    0x400000
                                             :fired       0x800000
                                             :locked      0x1000000
                                             :ia-kill     0x2000000
                                             :no-abort    0x4000000})
         :destination-x buf/int16
         :destination-y buf/int16
         :target-id     vu-id
         :cargo-id      vu-id
         :moved         buf/ubyte
         :losses        buf/ubyte
         :tactic        buf/ubyte
         :current-wp    buf/uint16
         :name-id       buf/int16
         :reinforcement buf/int16
         :waypoints     (larray buf/uint16 waypoint)]))

;; Air units

(def pilot
  (buf/spec :id               buf/int16
            :skill-and-rating buf/ubyte
            :status           buf/ubyte
            :aa-kills         buf/ubyte
            :ag-kills         buf/ubyte
            :as-kills         buf/ubyte
            :an-kills         buf/ubyte
            :missions-flown   buf/int16))

(def loadout
  (buf/spec :id    (buf/repeat 16 buf/uint16) ; Widened from byte in version 73
            :count (buf/repeat 16 buf/ubyte)))

;; Ref: flight.cpp::FlightClass ctor
(def flight
  (apply buf/spec
         (into unit-fields
               [:type              (constant :flight)
                :pos-z             buf/float
                :fuel-burnt        buf/int32
                :last-move         campaign-time
                :last-combat       campaign-time
                :time-on-target    campaign-time
                :mission-over-time campaign-time
                :mission-target    buf/int16
                :loadouts          (larray buf/ubyte loadout)
                :mission           buf/ubyte
                :old-mission       buf/ubyte
                :last-direction    buf/ubyte
                :priority          buf/ubyte
                :mission-id        buf/ubyte
                :eval-flags        buf/ubyte ; Only shows up in lightning's tools - not on the PMC wiki. It's also in the freefalcon source.
                :mission-context   buf/ubyte
                :package           vu-id
                :squadron          vu-id
                :requester         vu-id
                :slots             (buf/repeat 4 buf/ubyte)
                :pilots            (buf/repeat 4 buf/ubyte)
                :plane-stats       (buf/repeat 4 buf/ubyte)
                :player-slots      (buf/repeat 4 buf/ubyte)
                :last-player-slot  buf/ubyte
                :callsign-id       buf/ubyte
                :callsign-num      buf/ubyte
                :refuel-quantity   buf/uint32 ; >= 72
                ])))

(def squadron
  (apply buf/spec
         (into unit-fields
               [:type           (constant :squadron)
                :fuel           buf/int32
                :specialty      buf/ubyte
                :stores         (buf/repeat 600 buf/ubyte)
                :pilots         (buf/repeat 48 pilot)
                :schedule       (buf/repeat 16 buf/int32)
                :airbase-id     vu-id
                :hot-spot       vu-id
                :rating         (buf/repeat 16 buf/ubyte)
                :aa-kills       buf/int16
                :ag-kills       buf/int16
                :as-kills       buf/int16
                :an-kills       buf/int16
                :missions-flown buf/int16
                :mission-score  buf/int16
                :total-losses   buf/ubyte
                :pilot-losses   buf/ubyte
                :squadron-patch buf/ubyte])))

(def package
  (let [package-common (apply buf/spec
                              (into unit-fields
                                    [:type              (constant :package)
                                     :elements          (larray buf/ubyte vu-id)
                                     :interceptor       vu-id
                                     :awacs             vu-id
                                     :jstar             vu-id
                                     :ecm               vu-id
                                     :tanker            vu-id
                                     :wait-cycles       buf/ubyte]))]
    (reify
      octet.spec/ISpec
      (read [_ buf pos]
        (read-> buf
                pos
                (constantly  package-common)
                (fn [{:keys [unit-flags wait-cycles] :as val}]
                  (if (and (zero? wait-cycles)
                           (unit-flags :final))
                    (buf/spec
                     :requests buf/int16
                     :responses buf/int16
                     :mission-request (buf/spec
                                       :mission buf/int16
                                       :context buf/int16
                                       :requester vu-id
                                       :target vu-id
                                       :tot buf/uint32
                                       :action-type buf/ubyte
                                       :priority buf/int16
                                       :package-flags (constant 0)))
                    (buf/spec
                     :flights buf/ubyte
                     :wait-for buf/int16
                     :iax buf/int16
                     :iay buf/int16
                     :eax buf/int16
                     :eay buf/int16
                     :bpx buf/int16
                     :bpy buf/int16
                     :tpx buf/int16
                     :tpy buf/int16
                     :takeoff campaign-time
                     :tp-time campaign-time
                     :package-flags buf/uint32
                     :caps buf/int16
                     :requests buf/int16
                     :responses buf/int16
                     :ingress-waypoints (larray buf/ubyte waypoint)
                     :egress-waypoints (larray buf/ubyte waypoint)
                     :mission-request mission-request))))))))

;; Land units

(def ground-unit-fields
  (into unit-fields
        [:orders   buf/ubyte
         :division buf/int16
         :aobj     vu-id]))

(def brigade
  (apply buf/spec
         (into ground-unit-fields
               [:type     (constant :brigade)
                :elements (larray buf/ubyte vu-id)])))

(def battalion
  (apply buf/spec
         (into ground-unit-fields
               [:type (constant :battalion)
                :last-move campaign-time
                :last-combat campaign-time
                :parent-id vu-id
                :last-obj vu-id
                :supply buf/ubyte
                :fatigue buf/ubyte
                :morale buf/ubyte
                :heading buf/ubyte
                :final-heading buf/ubyte
                :position buf/ubyte])))

;; Sea units

(def task-force
  (apply buf/spec
         (into unit-fields
               [:type (constant :task-force)
                :orders buf/ubyte
                :supply buf/ubyte])))

;; unit-record

(defn class-info
  "Retrieves the unit class info (the most important elements of which
  are things like domain and type) for a given unit type."
  [{:keys [class-table] :as database} type-id]
  (let [class-entry (nth class-table
                         (- type-id VU_LAST_ENTITY_TYPE))]
    (-> class-entry
        :vu-class-data
        :class-info)))

(defn unit-record
  "Returns an octet spec for unit records against the given
  class table."
  [database]
  (reify
    octet.spec/ISpec
    (read [_ buf pos]
      (let [type-id (buf/read buf buf/int16 {:offset pos})]
        #_(log/debug "unit-record reading"
                   :pos pos
                   :unit-type unit-type)
        (if (zero? type-id)
          (do
            #_(log/debug "unit-record: found zero unit-type entry..")
            [2 nil])
          (let [{:keys [domain type]} (class-info
                                       database
                                       type-id)
                ;; _ (log/debug "unit-record decoding"
                ;;              :pos pos
                ;;              :domain domain
                ;;              :type type)
                spc (condp = [domain type]
                      [DOMAIN_AIR  TYPE_FLIGHT] flight
                      [DOMAIN_AIR  TYPE_PACKAGE] package
                      [DOMAIN_AIR  TYPE_SQUADRON] squadron
                      [DOMAIN_LAND TYPE_BATTALION] battalion
                      [DOMAIN_LAND TYPE_BRIGADE] brigade
                      [DOMAIN_SEA  TYPE_TASKFORCE] task-force)
                [datasize data] (try
                                  (buf/read* buf
                                             spc
                                             {:offset (+ pos 2)})
                                  (catch #?(:clj Throwable
                                            :cljs :default)
                                      x
                                      (log/error x
                                                 "unit-record read"
                                                 :domain domain
                                                 :type type
                                                 :pos pos)
                                      (throw x)))]
            #_(log/debug "unit-record decoded"
                         :data (keys data)
                         :datasize datasize)
            [(+ datasize 2) data]))))

    ;; TODO : implement write
    ))

(defn ordinal-suffix
  "Returns the appropriate ordinal suffix for a given number. I.e. \"st\" for 1, to give \"1st\"."
  [n {:keys [strings] :as database}]
  (cond
    (and (= 1 (mod n 10))
         (not= n 11))
    (strings 15)

    (and (= 2 (mod n 10))
         (not= n 12))
    (strings 16)

    (and (= 3 (mod n 10))
         (not= n 13))
    (strings 17)

    :else
    (strings 18)))

(defn partial=
  "Returns true if the coll1 and coll2 have corresponding elements
  equal, ignoring any excess."
  [coll1 coll2]
  (every? identity (map = coll1 coll2)))

(defn get-size-name
  "Returns the size portion of a unit name"
  [unit database]
  (let [{:keys [type-id]} unit
        {:keys [type domain]} (class-info database type-id)
        {:keys [strings]} database]
    #_(log/debug "get-size-name" :domain domain :type type)
    (strings
     (condp partial= [domain type]
       [DOMAIN_AIR TYPE_SQUADRON]   610
       [DOMAIN_AIR TYPE_FLIGHT]     611
       [DOMAIN_AIR TYPE_PACKAGE]    612
       [DOMAIN_LAND TYPE_BRIGADE]   614
       [DOMAIN_LAND TYPE_BATTALION] 615
       [DOMAIN_SEA]               616
       617))))

(defn data-table
  "Return the appropriate data table."
  [database data-type]
  (let [k (condp = data-type
            DTYPE_UNIT :unit-class-data
            nil)]
    (if k
      (get database k)
      ;; This is a TODO
      (vec (repeat 10000 {})))))

(defn class-data
  "Return the class data appropriate to the type."
  [database type-id]
  (let [{:keys [class-table]} database
        {:keys [data-pointer data-type]} (nth class-table (- type-id VU_LAST_ENTITY_TYPE))]
    (nth (data-table database data-type) data-pointer)))

(defn unit-name
  "Returns a human-readable name for the given unit"
  [unit database]
  (let [{:keys [strings]} database
        {:keys [name-id type-id]} unit
        {:keys [name]} (class-data database type-id)
        {:keys [domain type] :as ci} (class-info database type-id)]
    ;; Ref: unit.cpp::GetName
    (condp = [domain type]
      [DOMAIN_AIR TYPE_FLIGHT]
      (let [{:keys [callsign-id callsign-num]} unit]
       (format "%s %s"
               (strings (+ FIRST_CALLSIGN_ID callsign-id))
               callsign-num))

      [DOMAIN_AIR TYPE_PACKAGE]
      (let [{:keys [camp-id]} unit]
        (format "Package %d" camp-id))

      (let [{:keys [name-id]} unit]
        (format "%d%s %s %s"
                name-id
                (ordinal-suffix name-id database)
                name
                (get-size-name unit database))))))

(defmethod read-embedded-file* :units
  ;; Ref: UniFile.cs, units.cpp
  [_
   {:keys [offset length] :as entry}
   buf
   {:keys [class-table] :as database}]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :num-units buf/int16
                                :uncompressed-size buf/int32)
          {:keys [compressed-size
                  num-units
                  uncompressed-size]} (buf/read buf
                                                header-spec
                                                {:offset offset})
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            (- length 6)
                            uncompressed-size)]
      ;; Oddly, there can be entries in the table where the unit type
      ;; is zero. That'll return a nil unit when read, which we throw
      ;; away.
      #_(log/debug "read-embedded-file* (:units)"
                 :num-units num-units
                 :compressed-size compressed-size
                 :uncompressed-size uncompressed-size)
      (->> (buf/read
            data
            (buf/repeat num-units (unit-record database)))
           (remove nil?)
           (map (fn [unit]
                  (assoc unit :name (unit-name unit database))))))))

(defmethod read-embedded-file* :default
  [_ entry buf _]
  :not-yet-implemented)

