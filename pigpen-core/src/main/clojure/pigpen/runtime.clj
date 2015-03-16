;;
;;
;;  Copyright 2013-2015 Netflix, Inc.
;;
;;     Licensed under the Apache License, Version 2.0 (the "License");
;;     you may not use this file except in compliance with the License.
;;     You may obtain a copy of the License at
;;
;;         http://www.apache.org/licenses/LICENSE-2.0
;;
;;     Unless required by applicable law or agreed to in writing, software
;;     distributed under the License is distributed on an "AS IS" BASIS,
;;     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;     See the License for the specific language governing permissions and
;;     limitations under the License.
;;
;;

(ns pigpen.runtime
  "Functions for evaluating user code at runtime")

(set! *warn-on-reflection* true)

(defmacro with-ns
  "Evaluates f within ns. Calls (require 'ns) first."
  [ns f]
  `(do
     (require '~ns)
     (binding [*ns* (find-ns '~ns)]
       (eval '~f))))

(defn map->bind
  "For use with pigpen.core.op/bind$

Takes a map-style function (one that takes a single input and returns a
single output) and returns a bind function that performs the same logic.

  Example:

    (map->bind (fn [x] (* x x)))

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [f]
  (fn [args]
    [[(apply f args)]])) ;; wrap twice - single value, single arg to next fn

(defn mapcat->bind
  "For use with pigpen.core.op/bind$

Takes a mapcat-style function (one that takes a single input and returns zero
to many outputs) and returns a bind function that performs the same logic.

  Example:

    (mapcat->bind (fn [x] (seq x)))

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [f]
  (fn [args]
    (map vector (apply f args)))) ;; wrap each value as arg to next fn

(defn filter->bind
  "For use with pigpen.core.op/bind$

Takes a filter-style function (one that takes a single input and returns a
boolean output) and returns a bind function that performs the same logic.

  Example:

    (filter->bind (fn [x] (even? x)))

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [f]
  (fn [args]
    (if-let [result (apply f args)]
      [args] ;; wrap as arg to next fn
      [])))

(defn key-selector->bind
  "For use with pigpen.core.op/bind$

Creates a key-selector function based on `f`. The resulting bind function
returns a tuple of [(f x) x]. This is generally used to separate a key for
subsequent use in a sort, group, or join.

  Example:

    (key-selector->bind (fn [x] (:foo x)))

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [f]
  (fn [args]
    [[(apply f args) (first args)]])) ;; wrap twice - single value, two args to next fn

(defn keyword-field-selector->bind
  "For use with pigpen.core.op/bind$

Selects a set of fields from a map and projects them as native fields. The
bind function takes a single arg, which is a map with keyword keys. The
parameter `fields` is a sequence of keywords to select. The input relation
should have a single field that is a map value.

  Example:

    (keyword-field-selector->bind [:foo :bar :baz])

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [fields]
  (fn [args]
    (let [values (first args)]
      [(map values fields)])))

(defn indexed-field-selector->bind
  "For use with pigpen.core.op/bind$

Selects the first n fields and projects them as fields. The input relation
should have a single field, which is sequential. Applies f to the remaining args.

  Example:

    (indexed-field-selector->bind 2 pr-str)

  See also: pigpen.core.op/bind$
"
  {:added "0.3.0"}
  [n f]
  (fn [args]
    (let [values (first args)]
      [(concat
         (take n values)
         [(f (drop n values))])])))

(defn process->bind
  "Wraps the output of pre- and post-process with a vector."
  [f]
  (comp vector f))

(defn args->map
  "Returns a fn that converts a list of args into a map of named parameter
   values. Applies f to all the values."
  [f]
  (fn [& args]
    (->> args
      (partition 2)
      (map (fn [[k v]] [(keyword k) (f v)]))
      (into {}))))

(defn sentinel-nil
  "Coerces nils into a sentinel value. Useful for nil handling in outer joins."
  [value]
  (if (nil? value)
    ::nil
    value))

(defn debug [& args]
  "Creates a debug string for the tuple"
  (try
    (->> args (mapcat (juxt type str)) (clojure.string/join "\t"))
    (catch Exception z (str "Error getting value: " z))))

(defn eval-string
  "Reads code from a string & evaluates it"
  [f]
  (when (not-empty f)
    (try
      (eval (read-string f))
      (catch Throwable z
        (throw (RuntimeException. (str "Exception evaluating: " f) z))))))

(defmulti hybrid->clojure
  "Converts a hybrid data structure into 100% clojure. Platforms should add
methods for any types they expect to see."
  type)

(defmethod hybrid->clojure :default
  [value]
  value)

(defmethod hybrid->clojure java.util.Map [value]
  (->> value
    (map (fn [[k v]] [(hybrid->clojure k) (hybrid->clojure v)]))
    (into {})))

(defmulti native->clojure
  "Converts native data structures into 100% clojure. Platforms should add
methods for any types they expect to see. No clojure should be seen here."
  type)

(defmethod native->clojure :default
  [value]
  value)

(defmethod native->clojure java.util.Map [value]
  (->> value
    (map (fn [[k v]] [(native->clojure k) (native->clojure v)]))
    (into {})))

(defmulti pre-process
  "Optionally deserializes incoming data. Should return a fn that takes a single
'args' param and returns a seq of processed args. 'platform' is what will be
running the code (:pig, :cascading, etc). 'serialization-type' defines which of
the fields to serialize. It will be one of:

  :native - everything should be native values. You may need to coerce host
            types into clojure types here.
  :frozen - deserialize everything
"
  (fn [platform serialization-type]
    [platform serialization-type]))

(defmethod pre-process :default [_ _] identity)

(defmulti post-process
  "Optionally serializes outgoing data. Should return a fn that takes a single
'args' param and returns a seq of processed args. 'platform' is what will be
running the code (:pig, :cascading, etc). 'serialization-type' defines which of
the fields to serialize. It will be one of:

  :native - don't serialize
  :frozen - serialize everything
  :frozen-with-nils - serialize everything except nils
  :native-key-frozen-val - expect a tuple, freeze only the second value
"
  (fn [platform serialization-type]
    [platform serialization-type]))

(defmethod post-process :default [_ _] identity)

(defn exec
  "Applies the composition of fs, flattening intermediate results. Each f must
produce a seq-able output that is flattened as input to the next command."
  [fs]
  (fn [args]
    (reduce (fn [vs f] (mapcat f vs)) [args] fs)))
