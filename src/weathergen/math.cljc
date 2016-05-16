(ns weathergen.math
  "Functions for generating pseudorandom noise fields")

(defn low-bits
  [^long x n]
  (bit-and x (dec (bit-shift-left 1 n))))

(defn high-bits
  [^long x n]
  (bit-shift-right x (- 32 n)))

(defn bit-rotate-right
  [^long x n]
  (unchecked-add (high-bits x (- 32 n))
                 (bit-shift-left (low-bits x n) (- 32 n))))

(defn bit-rotate-left
  [^long x n]
  (unchecked-add (high-bits x n) (bit-shift-left (low-bits x (- 32 n)) n)))

(defn frac
  ^double [^double x]
  (- x (long x)))

(def whole long)

(defn mean
  [x y]
  (/ (+ x y) 2.0))

(defn interpolate
  [x y f]
  (+ (* (- 1.0 f) x)
     (* f y)))

(defn vector-interpolate
  [v1 v2 x x1 x2]
  (let [f      (/ (- x x1) (double (- x2 x1)))
        interp (fn [a b] (+ (* (- 1 f) a) (* f b)))]
    (mapv interp v1 v2)))

(defn scramble
  (^double [^long x] (scramble x 1))
  (^double [^long x ^long seed]
   (let [a (-> x (* seed) Math/sin Math/abs)
         b (* a 1E9)
         c (whole b)
         d (frac b)]
     (+ (frac b) (/ (whole b) 1.0E18)))))

(defn discrete-noise-field
  (^double [^long x ^long y] (discrete-noise-field x y 1))
  (^double [^long x ^long y seed]
   (-> x (* 65521) (+ y) (scramble seed))))

(defn continuous-noise-field
  (^double [^double x ^double y] (continuous-noise-field x y 1.0))
  (^double [^double x ^double y seed]
   (let [x-frac (frac x)
         y-frac (frac y)
         x-whole (whole x)
         y-whole (whole y)]
     (interpolate (interpolate (discrete-noise-field x-whole y-whole seed)
                               (discrete-noise-field (inc x-whole) y-whole seed)
                               x-frac)
                  (interpolate (discrete-noise-field x-whole (inc y-whole) seed)
                               (discrete-noise-field (inc x-whole) (inc y-whole) seed)
                               x-frac)
                  y-frac))))

(defn fractal-field
  ([x y zoom] (fractal-field x y zoom 1))
  ([x y zoom seed]
   (loop [result 0
          z zoom]
     (if (< z 1.0)
       result
       (recur (+ result (* (continuous-noise-field (/ x z) (/ y z) seed)
                           (/ z zoom 2.0)))
              (/ z 2))))))


(defn magnitude
  [[x y]]
  (Math/sqrt (+ (* x x) (* y y))))

(defn heading
  [[x y]]
  (-> (Math/atan2 x y) (* 180.0) (/ Math/PI) (mod 360)))

(defn gradient
  [x y field delta]
  (let [f0 (field x y)
        fx (field (+ x delta) y)
        fy (field x (+ y delta))
        dfdx (/ (- fx f0) delta)
        dfdy (/ (- fy f0) delta)]
    [dfdx dfdy]))

(defn clamp
  [min max val]
  (cond
    (< val min) min
    (< max val) max
    :else val))
