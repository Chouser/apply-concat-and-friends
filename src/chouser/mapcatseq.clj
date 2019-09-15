(ns chouser.mapcatseq
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.pprint :refer [print-table]]
            [clojure.repl :refer :all]))

(def sort-idempotent-prop
  (prop/for-all [v (gen/vector gen/int)]
    (= (sort v) (sort (sort v)))))

(defn lazier-mapcat [f coll]
  (lazy-seq
   (when-let [[xs] (seq coll)]
     (if-let [[x] (seq xs)]
       (cons x (lazier-mapcat f (cons (rest xs) (rest coll))))
       (lazier-mapcat f (rest coll))))))

;; eduction cat
;; reduce concat
;; mapcat macroexpand
;; mapcat eduction

(def squashers
  {:apc (fn [xs] (apply concat xs))
   ;; :api  (fn [xs] (apply into xs)) ;; nope
   :flt (fn [xs] (flatten xs))
   :mci (fn [xs] (mapcat identity xs))
   :mcs (fn [xs] (mapcat seq xs))
   :rei (fn [xs] (reduce into xs))
   :sqc (fn [xs] (sequence cat xs))
   :edc (fn [xs] (eduction cat xs))
   :lmc (fn [xs] (lazier-mapcat identity xs))})

(defn t0 [a b]
  (prop/for-all [v (gen/vector (gen/vector gen/int))]
    (= (a v) (b v))))

(defn catch-all [f]
  (fn [xs]
    (try
      (doall (f xs)) ;; laziness!
      (catch Throwable t
        :error))))

(defn test-all [num t]
  (doseq [[k f] squashers]
    (prn k (tc/quick-check num (t (catch-all (:mcs squashers)) (catch-all f))))))

(defn test-some [num t sqs]
  (doseq [[k f] (map #(find squashers %) sqs)]
    (prn k (tc/quick-check num (t (catch-all (:mcs squashers)) (catch-all f))))))

(defn t1 [a b]
  (prop/for-all [v (gen/vector (gen/vector (gen/vector gen/int)))]
                (= (a v) (b v))))

(defn t2 [a b]
  (prop/for-all [v gen/any]
                (= (a v) (b v))))

(def ^:dynamic *hold-the-lazy* false)

(defn write-lazy-seq [space? coll w]
  (if (and *hold-the-lazy*
           (instance? clojure.lang.IPending coll)
           (not (realized? coll)))
    (.write w (str (when space? " ") "..."))
    (when (seq coll)
      (when space?
        (.write w " "))
      (.write w (pr-str (first coll)))
      (write-lazy-seq true (rest coll) w))))

(defmethod print-method clojure.lang.ISeq [coll w]
  (.write w "(")
  (write-lazy-seq false coll w)
  (.write w ")"))

(defn print-results []
  (print-table [:arg :flt :rei :apc :mci :mcs :sqc :lmc]
               (for [arg '[[[1 2] [3 4] [5 6]]
                           [[1 2] [3 [4 4 4]]]
                           [#{1 2} #{3 4}]
                           [(1 2) (3 4) (5 6)]
                           ["12" "34"]]]

                 (assoc
                  (zipmap (keys squashers)
                          (map #(pr-str ((catch-all %) arg)) (vals squashers)))
                  :arg arg))))

(defn print-results []
  (let [args '[[[1 2] [3 4] [5 6]]
               [[1 2] [3 [4 4 4]]]
               [#{1 2} #{3 4}]
               [(1 2) (3 4) (5 6)]
               ["12" "34"]]]
    (print-table (cons :fn args)
                 (for [k [:flt :rei :apc :mci :mcs :sqc :lmc]]
                   (assoc
                    (zipmap args (map #(pr-str ((catch-all (squashers k)) %)) args))
                    :fn k)))))

(defn seq1 [s]
  (lazy-seq
   (when-let [[x] (seq s)]
     (cons x (seq1 (rest s))))))

(defn print-laziness []
  (print-table [:fn :a :c]
               (for [k [:flt :rei :apc :mci :mcs :sqc :lmc :edc]]
                 (let [a (seq1 (map (fn [i] (seq1 (range (* i 3) (+ (* i 3) 3))))
                                    (range 5)))
                       b (seq1 [(seq1 [1 2 3]) (seq1 [4 5 6]) (seq1 [7 8 9]) (seq1 [10 11 12])])
                       c (seq1 [(seq1 [1 2 (seq1 [3 4])]) (seq1 [5 6 (seq1 [7 8])])])]
                   (doseq [x [a b c]]
                     ((squashers k) x))
                   (binding [*hold-the-lazy* true]
                     {:fn k :a (pr-str a) :b (pr-str b) :c (pr-str c)})))))

(defn find-phrases []
  (let [xs [[1 2 3] [4 5 6] [7 8 9]]
        goal (mapcat seq xs)
        exclude-syms '#{iterate gen-interface split-at primitives-classnames
                        var-set trampoline cycle hash-ordered-coll var? meta
                        read+string volatile? seque refer-clojure bytes
                        denominator fnext spit}
        vars (->> (vals (ns-publics 'clojure.core))
              (remove #(exclude-syms (.sym %)))
              (filter #(instance? clojure.lang.IFn @%)))]

    (doall
     (for [v0 vars
           v1 vars
           :when (not (= '#{[keep-indexed take-nth] [bytes bytes]}
                         [(.sym v0) (.sym v1)]))
           :when (try
                    (= goal (@v0 @v1 xs))
                    (catch Throwable t false))]
       (list v0 v1 'xs)))))

;; read+string hash-ordered-coll

(comment
  (tc/quick-check 100 sort-idempotent-prop)
  (tc/quick-check 1000 (t0 ac f))
  (test-all 500 t0)
  (test-all 100 t1)
  (test-all 10 t2)

  (test-some 10000 t2 [:apc :mci :mcs :sqc :lmc])

  (def ps (find-phrases)))

;;;;; (print-laziness)
;; |  :fn |                                             :a |                    :c |
;; |------+------------------------------------------------+-----------------------|
;; | :flt |                  ((...) (...) (...) (...) ...) |         ((...) (...)) |
;; | :rei | ((0 ...) (3 4 5) (6 7 8) (9 10 11) (12 13 14)) | ((1 ...) (5 6 (...))) |
;; | :apc |                  ((...) (...) (...) (...) ...) |         ((...) (...)) |
;; | :mci |                  ((...) (...) (...) (...) ...) |         ((...) (...)) |
;; | :mcs |          ((0 ...) (3 ...) (6 ...) (9 ...) ...) |     ((1 ...) (5 ...)) |
;; | :sqc |                                  ((0 1 2) ...) |     ((1 2 (...)) ...) |
;; | :lmc |                                          (...) |                 (...) |
;; | :edc |                                          (...) |                 (...) |

;;;;; (print-laziness) with (first)
;; |  :fn |                                             :a |                        :c |
;; |------+------------------------------------------------+---------------------------|
;; | :flt |                ((0 1 2) (...) (...) (...) ...) |       ((1 2 (...)) (...)) |
;; | :rei | ((0 ...) (3 4 5) (6 7 8) (9 10 11) (12 13 14)) |     ((1 ...) (5 6 (...))) |
;; | :apc |                ((0 ...) (...) (...) (...) ...) |           ((1 ...) (...)) |
;; | :mci |                ((0 ...) (...) (...) (...) ...) |           ((1 ...) (...)) |
;; | :mcs |          ((0 ...) (3 ...) (6 ...) (9 ...) ...) |         ((1 ...) (5 ...)) |
;; | :sqc | ((0 1 2) (3 4 5) (6 7 8) (9 10 11) (12 13 14)) | ((1 2 (...)) (5 6 (...))) |
;; | :lmc |                                  ((0 ...) ...) |             ((1 ...) ...) |
;; | :edc | ((0 1 2) (3 4 5) (6 7 8) (9 10 11) (12 13 14)) | ((1 2 (...)) (5 6 (...))) |
