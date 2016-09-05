(ns oops.main
  (:require [cljs.test :refer-macros [deftest testing is are run-tests use-fixtures]]
            [clojure.string :as string]
            [oops.core :refer [oget oset! ocall! oapply! ocall oapply]]
            [oops.config :refer [with-runtime-config]]
            [oops.tools
             :refer [with-captured-console]
             :refer-macros [init-test!
                            when-advanced-mode when-none-mode
                            with-console-recording
                            when-compiler-config when-not-compiler-config]]))

(init-test!)

(use-fixtures :once with-captured-console)

(deftest test-oget
  (let [sample-obj #js {:key               "val"
                        "@#$%fancy key^&*" "fancy-val"
                        "nested"           #js {:nested-key1  "nk1"
                                                "nested-key2" 2}}]
    (testing "simple static key/path fetch"
      (are [key expected] (= (oget sample-obj key) expected)
        "non-existent" nil
        "key" "val"
        "@#$%fancy key^&*" "fancy-val"
        ["nested" "nested-key2"] 2))
    (testing "simple dynamic key/path fetch"
      (let [dynamic-key-fn (fn [name] name)]
        (is (= (oget sample-obj (dynamic-key-fn "key")) "val"))
        (is (= (oget sample-obj (dynamic-key-fn "xxx")) nil))
        (is (= (oget sample-obj (dynamic-key-fn "nested") "nested-key1") "nk1"))
        (is (= (oget sample-obj [(dynamic-key-fn "nested") "nested-key1"]) "nk1"))
        (when-none-mode
          (are [input] (thrown-with-msg? js/Error #"Invalid dynamic selector" (oget sample-obj (dynamic-key-fn input)))
            'sym
            identity
            0
            #js {}
            #js []))))
    (when-none-mode
      (testing "object access validation should throw by default"
        (are [o msg] (thrown-with-msg? js/Error msg (oget o "key"))
          nil #"Unexpected object value \(nil\)"
          js/undefined #"Unexpected object value \(undefined\)"
          "s" #"Unexpected object value \(string\)"
          42 #"Unexpected object value \(number\)"
          true #"Unexpected object value \(boolean\)"
          false #"Unexpected object value \(boolean\)")
        (with-runtime-config {:object-access-validation-mode false}
          (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
            nil #"null is not an object"
            js/undefined #"undefined is not an object")
          (are [o] (= (oget o "key") nil)
            "s"
            42
            true
            false)))
      (testing "with {:object-access-validation-mode :report} object access validation should report errors to console"
        (with-runtime-config {:object-access-validation-mode :report}
          (let [recorder (atom [""])
                expected-warnings "
ERROR: (\"Unexpected object value (nil)\" nil)
ERROR: (\"Unexpected object value (undefined)\" nil)
ERROR: (\"Unexpected object value (string)\" \"s\")
ERROR: (\"Unexpected object value (number)\" 42)
ERROR: (\"Unexpected object value (boolean)\" true)
ERROR: (\"Unexpected object value (boolean)\" false)"]
            (with-console-recording recorder
              (are [o] (= (oget o "key") nil)
                nil
                js/undefined
                "s"
                42
                true
                false))
            (is (= (string/join "\n" @recorder) expected-warnings)))))
      (testing (str "with {:object-access-validation-mode :sanitize} object access validation should not report errors"
                    "but still should sanitize results as nil")
        (with-runtime-config {:object-access-validation-mode :sanitize}
          (let [recorder (atom [""])]
            (with-console-recording recorder
              (are [o] (= (oget o "key") nil)
                nil
                js/undefined
                "s"
                42
                true
                false))
            (is (= (string/join "\n" @recorder) "")))))
      (testing "with {:object-access-validation-mode false} object access validation should be elided"
        (with-runtime-config {:object-access-validation-mode false}
          (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
            nil #"null is not an object"
            js/undefined #"undefined is not an object")
          (are [o] (= (oget o "key") nil)
            "s"
            42
            true
            false)))
      (when-advanced-mode                                                                                                     ; advanced optimizations
        (testing "object access validation should crash or silently fail in advanced mode (no diagnostics)"
          (when-not-compiler-config {:atomic-get-mode :goog}
                                    (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
                                      nil #"null is not an object"
                                      js/undefined #"undefined is not an object")
                                    (are [o] (= (oget o "key") nil)
                                      "s"
                                      42
                                      true
                                      false)))))
    (testing "oget corner cases"
      ; TODO
      )))

(deftest test-oset
  (testing "simple key store"
    (let [sample-obj #js {"nested" #js {}}]
      (are [selector] (= (oget (oset! sample-obj selector "val") selector) "val")
        ["xxx"]
        ["yyy"]
        ["nested" "y"])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"y\":\"val\"},\"xxx\":\"val\",\"yyy\":\"val\"}"))))
  (testing "simple dynamic selector set"
    (let [sample-obj #js {"nested" #js {}}
          dynamic-key-fn (fn [name] name)]
      (are [selector] (= (oget (oset! sample-obj selector "val") selector) "val")
        (dynamic-key-fn "key")
        [(dynamic-key-fn "nested") (dynamic-key-fn "key2")])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"key2\":\"val\"},\"key\":\"val\"}"))))
  (testing "oset corner cases"
    ; TODO
    ))

(deftest test-ocall
  (testing "simple invocation via call"
    (let [counter (volatile! 0)
          sample-obj #js {"inc-fn"    #(vswap! counter inc)
                          "return-fn" (fn [& args] args)
                          "add-fn"    (fn [n] (vswap! counter + n))
                          "add*-fn"   (fn [& args] (vreset! counter (apply + @counter args)))}]
      (ocall sample-obj "inc-fn")                                                                                             ; note ocall should work the same as ocall!
      (is (= @counter 1))
      (is (= (ocall! sample-obj "return-fn" 1) '(1)))
      (is (= (ocall! sample-obj "return-fn") nil))
      (is (= (ocall! sample-obj "return-fn" 1 2 3) '(1 2 3)))
      (ocall! sample-obj "add-fn" 1)
      (is (= @counter 2))
      (ocall! sample-obj "add-fn" 1 2 3 4)
      (is (= @counter 3))
      (ocall! sample-obj "add*-fn" 1 2 3 4)
      (is (= @counter 13)))))

(deftest test-oapply
  (testing "simple invocation via apply"
    (let [counter (volatile! 0)
          sample-obj #js {"inc-fn"    #(vswap! counter inc)
                          "return-fn" (fn [& args] args)
                          "add-fn"    (fn [n] (vswap! counter + n))
                          "add*-fn"   (fn [& args] (vreset! counter (apply + @counter args)))}]
      (oapply sample-obj "inc-fn" [])                                                                                         ; note oapply should work the same as oapply!
      (is (= @counter 1))
      (is (= (oapply! sample-obj "return-fn" [1]) '(1)))
      (is (= (oapply! sample-obj "return-fn" []) nil))
      (is (= (oapply! sample-obj "return-fn" [1 2 3]) '(1 2 3)))
      (oapply! sample-obj "add-fn" (list 1))
      (is (= @counter 2))
      (oapply! sample-obj "add-fn" (list 1 2 3 4))
      (is (= @counter 3))
      (oapply! sample-obj "add*-fn" (range 5))
      (is (= @counter 13)))))
