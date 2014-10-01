(ns patalyze.core-test
  (:require [clojure.test :refer :all]
            [patalyze.core :refer :all]))

;; (clojure.test/run-tests)

;; (def inventors
;;   {:v15 ["Lawrence A. Clark" "James L. Priest"]
;;    :v43 ["Daniel Francis Lowery" "Jon M. Malinoski"]})

;; (deftest inventors-test
;;   (testing "inventors v15"
;;     (is (= (:inventors (patentxml->map (:v15 (version-samples))))
;;           '("Lawrence A. Clark" "James L. Priest"))))

;;   (testing "Inventors v43"
;;     (is (= (:inventors (patentxml->map (:v43 (version-samples))))
;;           '("Daniel Francis Lowery" "Jon M. Malinoski")))))
;;           '("David C. Holland")
;;           '("Tina Goldkind")
;;           '("Andrea Tomann" "Ken Zemach" "Bill McDonough" "Tim Smith")
;;           '("Philip A. Eckhoff" "Roderick A. Hyde" "Muriel Y. Ishikawa" "Jordin T. Kare" "Lowell L. Wood, JR.")
;;           '("Daniel Francis Lowery" "Jon M. Malinoski"))))))
