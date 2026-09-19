(ns dvergr.intake.schema
  "malli schemas shared by the intakes.

   Every public intake fn carries its malli function schema as metadata:

     (defn fetch-top
       {:malli/schema [:=> [:cat (schema/kwargs :count :int :query :string)]
                           (schema/result Story)]}
       [& {:keys [count query]}] ...)

   `(sandbox/doc 'dvergr.intake.hn)` shows it, and `malli.core` (loaded in
   the sandbox) validates data against it:

     (m/validate Story x)   (m/explain (schema/result Story) (hn/fetch-top))

   Schemas are plain data built from these helpers, so they print readably.
   Prefer keyword types (`:int`, `:string`, ...). Where a predicate schema is
   needed, quote it (`'inst?`): in the sandbox some core predicates evaluate
   to SCI's own implementations, which malli does not recognize.")

(def Error
  "What every intake returns instead of data when a request fails."
  [:map [:error [:maybe :string]]])

(defn result
  "An intake result: a vector of `item` or an `Error` map."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [item]
  [:or [:vector item] Error])

(defn one
  "A single `x` or an `Error` map."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [x]
  [:or x Error])

(defn kwargs
  "Schema for trailing keyword arguments (`& {:keys [...]}`), given as
   alternating keyword and schema: `(kwargs :count :int :query :string)`.
   Every keyword argument is optional."
  {:malli/schema [:=> [:cat [:* :any]] [:vector :any]]}
  [& kvs]
  [:* (into [:alt] (for [[k s] (partition 2 kvs)] [:cat [:= k] s]))])

(def Url :string)

(def IsoDate
  "An ISO-8601 date or timestamp string."
  :string)
