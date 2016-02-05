(ns scheje.interpreter
  (:require [clojure.core.match :refer [match]]
            [scheje.tools :refer :all] 
            [scheje.unifier :as unifier]
            [scheje.expander :as expander]))


(def
  root-env
  {'true true
   'false false
   'else true
   :keywords ['if 'cons 'car 'cdr 'null? 'atom? '+ '- 'eq? '< '<= '> '>= '/ '* 'false 'true 'else]
   :syntax ['{:name let, :literals (),
              :rules (((let ((var expr) ...) body ...)
                       ((lambda (var ...) body ...) expr ...)))}
            '{:name and, :literals (),
              :rules (((and x) x) ((and) true)
                      ((and x y ...) (if x (and y ...) false)))}
            '{:name or, :literals (),
              :rules (((or) true)
                      ((or x) x)
                      ((or x y ...) (if x true (or y ...))))}]})

(def ts (atom 0))

(declare form-eval)

(defn form-apply
  [exp a]
  (match [exp]
         [([(f :guard atom?) & r] :seq )] (cond
                                            (= f 'null?) (=  '(()) r)                                          
                                            (= f 'car) (-> r first first)
                                            (= f 'cdr) (-> r first rest )
                                            (= f 'cons) (cons (first r) (second r))
                                            (= f 'atom?) (atom? (first r))
                                            (= f '+) (apply + (into [] r))
                                            (= f '-) (apply - (into [] r))
                                            (= f '/) (apply / (into [] r))
                                            (= f '*) (apply * (into [] r))
                                            (= f '<) (apply < (into [] r))
                                            (= f '>) (apply > (into [] r))
                                            (= f '<=) (apply <= (into [] r))
                                            (= f '>=) (apply >= (into [] r))
                                            :else (form-apply (cons  (form-eval f a) r) a))
         [([(['lambda parms body]:seq) & args ]:seq)] (form-eval body (pairlis parms args a))))

(defn evcon
  [conds a]
  (cond
    (form-eval (-> conds first first) a) (form-eval (-> conds first rest first) a)
    :else (evcon (rest conds) a)))

(defn evlis
  [exps a]
  (map #(form-eval % a) exps))


(defn define-syntax
  [a
   syn-name
   literals
   pattern-rules]
  (update-in a [:syntax ] conj {:name syn-name
                                :literals literals
                                :rules  pattern-rules}))

(defn define
  [a
   sym
   binding]
  (assoc a sym
         (form-eval binding a)))

(defn form-eval-quasi
  [exp a]

  (cond
    (atom? exp) exp 

    (or  (= '() exp) 
         (nil? exp)) '()

    (or (number? exp)
        (string? exp)
        (rational? exp)) exp
    (atom? (first exp)) (cons (first exp) (form-eval-quasi (rest exp) a))
    (= (-> exp first first) 'unquote)  (cons  (form-eval (-> exp first second ) a)
                                              (form-eval-quasi (rest exp) a))
    (= (-> exp first first) 'unquote-splicing) (into (form-eval (-> exp first second ) a)
                                                     (form-eval-quasi (rest exp) a) )
    :else (cons (form-eval-quasi (-> exp first) a)
                (form-eval-quasi (rest exp ) a))))



(defn form-eval
  [exp a]
    (cond
      (or  (= '() exp) 
           (nil? exp)) '()
      (or (number? exp)
          (string? exp)
          (rational? exp))  exp
      (atom? exp) (let [scope (get-in a [:scopes exp])]
                    (if (not (nil? scope))
                      (get scope exp)
                      (let [from-root (get a exp)]
                        (if (not (nil? from-root))
                          from-root
                          (if (some #{'\%} (name  exp) )
                          
                            (form-eval  (symbol  (:sym  (unifier/get-symbol-idx (name  exp)))) a)
                            ;; maybe a macro symbol that we want captured, a function name?
                            (throw (Exception. (str  "No bindnig found for " exp))))))))

      (atom? (first exp))   (let [some-syn (get-syntax (first  exp) (:syntax a))]
                              (cond
                                (not (nil?  some-syn)) (let [{:keys [rules]} some-syn]  
                                                         (loop [remaining rules]
                                                           (let [cur-rule (first remaining)
                                                                 cur-pattern (first cur-rule)
                                                                 cur-tpl (second cur-rule)
                                                                 a-match (unifier/unify cur-pattern exp (swap! ts inc) a)]
                                                             (cond
                                                               (nil? (get a-match :error)) (let [expanded-tpl
                                                                                                 (expander/expand-w-bindings cur-tpl
                                                                                                                             a-match)]
                                                                                             (form-eval expanded-tpl
                                                                                                        (assoc-in  a
                                                                                                                   [:scopes expanded-tpl]                                                                                                                  (conj root-env  a-match)))) 
                                                               :else (if (seq remaining)
                                                                       (recur (rest remaining))
                                                                       (throw (Exception. (str  "Error in resolving syntax in: "
                                                                                                exp))))))))
                                

                                
                                (or (rational? (first exp))
                                    (string? (first exp))
                                    (number? (first exp))) (throw  (Exception.  (str "error: The Scalar: `" (first exp)
                                                                                     "` Cannot be Applied on " (rest exp) "!!")))

                                (= (first exp) 'lambda) exp
                                (= (first exp) 'quasiquote) (form-eval-quasi (second exp) a )
                                (= (first exp) 'unquote) (throw (Exception.
                                                                 (str "error: unquote can only be called "
                                                                      "in a quasiquoted form!")))
                                (= (first exp) 'unquote-splicing) (throw (Exception.
                                                                          (str "error: unquote-splicing can only be"
                                                                               "called in a quasiquoted form!")))
                                (= (first exp) 'quote) (-> exp rest first)
                                (= (first exp) 'cond) (evcon (rest exp) a)
                                (= (first exp) 'if) (if (form-eval (-> exp rest first) a)
                                                      (form-eval (-> exp rest second) a)
                                                      (form-eval (-> exp rest (nth 2)) a))
                                :else (form-apply (cons (first exp) (evlis (rest exp) a)) a)))
      :else (form-apply (cons (first exp) (evlis (rest exp) a)) a)))

(defn eval-prog-with-env
  [a exprs]  
  (loop [remaining exprs
         eval-result {}
         env a]
    (if (seq remaining)
      (let [exp (first remaining)
            [new-env the-eval] (try
                                 (cond
                                   (and (seq? exp)
                                        (= (first exp) 'define-syntax)) [(define-syntax env
                                                                           (second exp)
                                                                           (-> exp rest second second)
                                                                           (-> exp rest second rest rest))
                                                                         (second exp)]

                                   (and (seq? exp)
                                        (= (first exp) 'define)) [(define env
                                                                    (second exp)
                                                                    (-> exp rest rest first))
                                                                  (second exp)]
                                   :else [env (form-eval exp env)])
                                 (catch Exception e   [env {:error (str "in " exp " : " e)}]))]
        (recur (rest remaining)
               (assoc eval-result exp the-eval)
               new-env))
      {:env env
       :evals eval-result})))

(def eval-prog (comp last vals :evals (partial eval-prog-with-env root-env)))
