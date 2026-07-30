# Model Selection Grammar

```clojure
model-spec ::= model-id | model-query

model-id ::= string

model-query ::= {:type :model-query
                 query-clause+}

query-clause ::= preference-clause
               | constraint-clause
               | fallback-clause

preference-clause ::= {:prefer [preference+]}

constraint-clause ::= {:require [constraint+]}

fallback-clause ::= {:fallback model-spec}

preference ::= keyword
             | preference-map

constraint ::= keyword
             | constraint-map

preference-map ::= map
constraint-map ::= map

map ::= clojure-map
string ::= clojure-string
keyword ::= clojure-keyword
```
