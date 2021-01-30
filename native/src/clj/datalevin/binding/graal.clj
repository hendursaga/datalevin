(ns datalevin.binding.graal
  "LMDB binding for GraalVM native image"
  (:require [datalevin.bits :as b]
            [datalevin.util :refer [raise]]
            [datalevin.constants :as c]
            [datalevin.binding.scan :as scan]
            [datalevin.lmdb :refer [open-lmdb IBuffer IRange IRtx IDB IKV
                                    ILMDB]]
            [clojure.string :as s])
  (:import [java.util Iterator]
           [java.util.concurrent ConcurrentHashMap]
           [java.nio.charset StandardCharsets]
           [java.nio ByteBuffer]
           [java.lang AutoCloseable]
           [java.lang.annotation Retention RetentionPolicy]
           [org.graalvm.nativeimage.c CContext]
           [org.graalvm.nativeimage.c.type CTypeConversion WordPointer
            CTypeConversion$CCharPointerHolder]
           [datalevin.ni BufVal Lib Lib$Directives Lib$MDB_env Lib$MDB_txn
            Lib$MDB_cursor Lib$LMDBException Lib$BadReaderLockException
            Lib$MDB_dbiPointer Lib$MDB_cursor_op Lib$MDB_cursorPointer
            Lib$MDB_txnPointer Lib$MDB_envPointer Lib$MDB_envinfo
            Lib$MDB_stat Lib$MapFullException]
           ))

(defn- mask-flags
  [flags]
  (reduce (fn [m flag] (bit-or ^int m ^int flag)) flags))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    Rtx [^Lib$MDB_txnPointer txnPtr
         ^:volatile-mutable ^Boolean use?
         ^BufVal kp
         ^BufVal vp
         ^BufVal start-kp
         ^BufVal stop-kp]
  IBuffer
  (put-key [_ x t]
    (try
      (let [^ByteBuffer kb (.inBuf kp)]
        (.clear kb)
        (b/put-buffer kb x t)
        (.flip kb))
      (catch Exception e
        (raise "Error putting read-only transaction key buffer: "
               (ex-message e)
               {:value x :type t}))))
  (put-val [_ x t]
    (raise "put-val not allowed for read only txn buffer" {}))

  IRange
  (put-start-key [_ x t]
    (try
      (when x
        (let [^ByteBuffer start-kb (.inBuf start-kp)]
          (.clear start-kb)
          (b/put-buffer start-kb x t)
          (.flip start-kb)))
      (catch Exception e
        (raise "Error putting read-only transaction start key buffer: "
               (ex-message e)
               {:value x :type t}))))
  (put-stop-key [_ x t]
    (try
      (when x
        (let [^ByteBuffer stop-kb (.inBuf stop-kp)]
          (.clear stop-kb)
          (b/put-buffer stop-kb x t)
          (.flip stop-kb)))
      (catch Exception e
        (raise "Error putting read-only transaction stop key buffer: "
               (ex-message e)
               {:value x :type t}))))

  IRtx
  (close-rtx [_]
    (Lib/mdb_txn_abort (.read txnPtr))
    (Lib/freeTxnPtr txnPtr)
    (.close kp)
    (.close start-kp)
    (.close stop-kp)
    (set! use? false))
  (reset [this]
    (Lib/mdb_txn_reset (.read txnPtr))
    (set! use? false)
    this)
  (renew [this]
    (when-not use?
      (set! use? true)
      (Lib/checkRc (Lib/mdb_txn_renew (.read txnPtr)))
      this)))

(defprotocol IRtxPool
  (close-pool [this] "Close all transactions in the pool")
  (new-rtx [this] "Create a new read-only transaction")
  (get-rtx [this] "Obtain a ready-to-use read-only transaction"))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    RtxPool [^Lib$MDB_env env
             ^ConcurrentHashMap rtxs
             ^:volatile-mutable ^long cnt]
  IRtxPool
  (close-pool [this]
    (doseq [^Rtx rtx (.values rtxs)] (.close-rtx rtx))
    (.clear rtxs)
    (set! cnt 0))
  (new-rtx [this]
    (when (< cnt c/+use-readers+)
      (let [txnPtr (Lib/allocateTxnPtr)
            _      (Lib/checkRc
                     (Lib/mdb_txn_begin env nil (Lib/MDB_RDONLY) txnPtr))
            rtx    (->Rtx txnPtr
                          false
                          (BufVal/create c/+max-key-size+)
                          (BufVal/create 1)
                          (BufVal/create c/+max-key-size+)
                          (BufVal/create c/+max-key-size+))]
        (.put rtxs cnt rtx)
        (set! cnt (inc cnt))
        (.reset ^Rtx rtx)
        (.renew ^Rtx rtx))))
  (get-rtx [this]
    (try
      (locking this
        (if (zero? cnt)
          (new-rtx this)
          (loop [i (.getId ^Thread (Thread/currentThread))]
            (let [^long i' (mod i cnt)
                  ^Rtx rtx (.get rtxs i')]
              (or (.renew rtx)
                  (new-rtx this)
                  (recur (long (inc i'))))))))
      (catch Lib$BadReaderLockException _
        (raise
          "Please do not open multiple LMDB connections to the same DB
           in the same process. Instead, a LMDB connection should be held onto
           and managed like a stateful resource. Refer to the documentation of
           `datalevin.lmdb/open-lmdb` for more details." {})))))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    KV [^BufVal kp ^BufVal vp]
  IKV
  (k [this] (.outBuf kp))
  (v [this] (.outBuf vp)))

(defprotocol IState
  (set-started [this] "Set the cursor state to be started")
  (set-ended [this] "Set the cursor state to be ended"))

(defn- cursor-type
  [range-type]
  (case range-type
    :all               [true false false false false]
    :all-back          [false false false false false]
    :at-least          [true true true false false]
    :at-least-back     [false true true false false]
    :at-most           [true false false true true]
    :at-most-back      [false false false true true]
    :closed            [true true true true true]
    :closed-back       [false true true true true]
    :closed-open       [true true true true false]
    :closed-open-back  [false true true true false]
    :greater-than      [true true false false false]
    :greater-than-back [false true false false false]
    :less-than         [true false false true false]
    :less-than-back    [false false false true false]
    :open              [true true false true false]
    :open-back         [false true false true false]
    :open-closed       [true true false true true]
    :open-closed-back  [false true false true true]))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    CursorIterable [^:volatile-mutable started?
                    ^:volatile-mutable ended?
                    ^Lib$MDB_cursorPointer cptr
                    ^int dbi
                    ^Rtx rtx
                    forward?
                    start-key?
                    include-start?
                    stop-key?
                    include-stop?]
  AutoCloseable
  (close [_]
    (Lib/mdb_cursor_close (.read cptr))
    (Lib/freeCursorPtr cptr))

  IState
  (set-started [_] (set! started? true))
  (set-ended [_] (set! ended? true))

  Iterable
  (iterator [this]
    (let [^Lib$MDB_val k         (.getVal ^BufVal (.-kp rtx))
          ^Lib$MDB_val v         (.getVal ^BufVal (.-vp rtx))
          ^Lib$MDB_val sk        (.getVal ^BufVal (.-start-kp rtx))
          ^Lib$MDB_val ek        (.getVal ^BufVal (.-stop-kp rtx))
          ^Lib$MDB_txn txn       (.read ^Lib$MDB_txnPointer (.-txnPtr rtx))
          ^Lib$MDB_cursor cursor (.read cptr)]
      ;; assuming hasNext is always called before next, hasNext will
      ;; position the cursor, next will get the data
      (reify
        Iterator
        (hasNext [this]
          (let [has?  #(if (= % (Lib/MDB_NOTFOUND))
                         false
                         (do (Lib/checkRc %) true))
                found #(if stop-key?
                         (do (Lib/checkRc
                               (Lib/mdb_cursor_get
                                 cursor k v
                                 (Lib$MDB_cursor_op/MDB_GET_CURRENT)))
                             (if (= 0 (Lib/mdb_cmp txn dbi k ek))
                               (do (set-ended this)
                                   include-stop?)
                               true))
                         true)]
            (if ended?
              false
              (if started?
                (if forward?
                  (if (has? (Lib/mdb_cursor_get
                              cursor k v (Lib$MDB_cursor_op/MDB_NEXT)))
                    (found)
                    false)
                  (if (has? (Lib/mdb_cursor_get
                              cursor k v (Lib$MDB_cursor_op/MDB_PREV)))
                    (found)
                    false))
                (do
                  (set-started this)
                  (if start-key?
                    (if (has? (Lib/mdb_cursor_get
                                cursor sk v (Lib$MDB_cursor_op/MDB_SET)))
                      (if include-start?
                        true
                        (if forward?
                          (has? (Lib/mdb_cursor_get
                                  cursor k v (Lib$MDB_cursor_op/MDB_NEXT)))
                          (has? (Lib/mdb_cursor_get
                                  cursor k v (Lib$MDB_cursor_op/MDB_PREV)))))
                      false)
                    (if forward?
                      (has? (Lib/mdb_cursor_get
                              cursor k v (Lib$MDB_cursor_op/MDB_FIRST)))
                      (has? (Lib/mdb_cursor_get
                              cursor k v (Lib$MDB_cursor_op/MDB_LAST))))))))))
        (next [this]
          (Lib/checkRc (Lib/mdb_cursor_get
                         cursor k v (Lib$MDB_cursor_op/MDB_GET_CURRENT)))
          (->KV k v))))))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    DBI [^Lib$MDB_dbiPointer dbiPtr
         ^String dbi-name
         ^BufVal kp
         ^:volatile-mutable ^BufVal vp]
  IBuffer
  (put-key [this x t]
    (let [^ByteBuffer kb (.inBuf kp)]
      (try
        (.clear kb)
        (b/put-buffer kb x t)
        (.flip kb)
        (catch Exception e
          (raise "Error putting r/w key buffer of "
                 dbi-name ": " (ex-message e)
                 {:value x :type t :dbi dbi-name})))))
  (put-val [this x t]
    (let [^ByteBuffer vb (.inBuf vp)]
      (try
        (.clear vb)
        (b/put-buffer vb x t)
        (.flip vb)
        (catch Exception e
          (if (s/includes? (ex-message e) c/buffer-overflow)
            (let [size (* 2 ^long (b/measure-size x))]
              (.close vp)
              (set! vp (BufVal/create size))
              (let [^ByteBuffer vb (.inBuf vp)]
                (b/put-buffer vb x t)
                (.flip vb)))
            (raise "Error putting r/w value buffer of "
                   dbi-name ": " (ex-message e)
                   {:value x :type t :dbi dbi-name}))))))

  IDB
  (dbi-name [_]
    dbi-name)

  (put [this txn]
    (.put this txn nil))
  (put [_ txn flags]
    (let [dbi (.read dbiPtr)]
      (Lib/checkRc
        (if flags
          (Lib/mdb_put txn dbi (.getVal kp) (.getVal vp) flags)
          (Lib/mdb_put txn dbi (.getVal kp) (.getVal vp) 0)))))

  (del [_ txn]
    (Lib/checkRc (Lib/mdb_del txn (.read dbiPtr) (.getVal kp) (.getVal vp))))

  (get-kv [_ rtx]
    (let [^BufVal kp (.-kp ^Rtx rtx)
          ^BufVal vp (.-vp ^Rtx rtx)]
      (Lib/checkRc
        (Lib/mdb_get (.read ^Lib$MDB_txnPointer (.-txnPtr ^Rtx rtx))
                     (.read dbiPtr) (.getVal kp) (.getVal vp)))
      (.outBuf vp)))

  (iterate-kv [this rtx range-type]
    (let [txn                  (.read ^Lib$MDB_txnPointer (.-txnPtr ^Rtx rtx))
          cptr                 (Lib/allocateCursorPtr)
          [f? sk? is? ek? ie?] (cursor-type range-type)
          dbi                  (.read dbiPtr)]
      (Lib/checkRc (Lib/mdb_cursor_open txn dbi cptr))
      (->CursorIterable false false cptr dbi rtx f? sk? is? ek? ie?))))

(deftype ^{Retention RetentionPolicy/RUNTIME
           CContext  {:value Lib$Directives}}
    LMDB [^Lib$MDB_envPointer envPtr
          ^String dir
          ^RtxPool pool
          ^ConcurrentHashMap dbis
          ^:volatile-mutable closed?]
  ILMDB
  (close-env [_]
    (close-pool pool)
    (doseq [^DBI dbi (.values dbis)] (Lib/freeDbiPtr (.-dbiPtr dbi)))
    (Lib/mdb_env_close (.read envPtr))
    (Lib/freeEnvPtr envPtr)
    (set! closed? true))

  (closed? [_]
    closed?)

  (dir [_]
    dir)

  (open-dbi [this dbi-name]
    (.open-dbi
      this dbi-name c/+max-key-size+ c/+default-val-size+ (Lib/MDB_CREATE)))
  (open-dbi [this dbi-name key-size]
    (.open-dbi this dbi-name key-size c/+default-val-size+ (Lib/MDB_CREATE)))
  (open-dbi [this dbi-name key-size val-size]
    (.open-dbi this dbi-name key-size val-size (Lib/MDB_CREATE)))
  (open-dbi [_ dbi-name key-size val-size flags]
    (assert (not closed?) "LMDB env is closed.")
    (let [kp                         (BufVal/create key-size)
          vp                         (BufVal/create val-size)
          env                        (.read envPtr)
          ^Lib$MDB_dbiPointer dbiPtr (Lib/allocateDbiPtr)
          ^Lib$MDB_txnPointer txnPtr (Lib/allocateTxnPtr)
          ]
      (Lib/checkRc (Lib/mdb_txn_begin env nil 0 txnPtr))
      (Lib/checkRc (Lib/mdb_dbi_open
                     (.read txnPtr) (.get (CTypeConversion/toCString dbi-name))
                     flags dbiPtr))
      (Lib/mdb_txn_abort (.read txnPtr))
      (Lib/freeTxnPtr txnPtr)
      (let [dbi (->DBI dbiPtr dbi-name kp vp)]
        (.put dbis dbi-name dbi)
        dbi)))

  (get-dbi [_ dbi-name]
    (or (.get dbis dbi-name)
        (raise "`open-dbi` was not called for " dbi-name {})))

  (clear-dbi [this dbi-name]
    (assert (not closed?) "LMDB env is closed.")
    (try
      (let [^Lib$MDB_txnPointer txnPtr (Lib/allocateTxnPtr)
            ^Lib$MDB_dbiPointer dbiPtr (.-dbiPtr ^DBI (.get-dbi this dbi-name))]
        (Lib/checkRc (Lib/mdb_txn_begin (.read envPtr) nil 0 txnPtr))
        (Lib/checkRc (Lib/mdb_drop (.read txnPtr) (.read dbiPtr) 0))
        (Lib/checkRc (Lib/mdb_txn_commit (.read txnPtr)))
        (Lib/freeTxnPtr txnPtr))
      (catch Exception e
        (raise "Fail to clear DBI: " dbi-name " " (ex-message e) {}))))

  (drop-dbi [this dbi-name]
    (assert (not closed?) "LMDB env is closed.")
    (try
      (let [^Lib$MDB_txnPointer txnPtr (Lib/allocateTxnPtr)
            ^Lib$MDB_dbiPointer dbiPtr (.-dbiPtr ^DBI (.get-dbi this dbi-name))]
        (Lib/checkRc (Lib/mdb_txn_begin (.read envPtr) nil 0 txnPtr))
        (Lib/checkRc (Lib/mdb_drop (.read txnPtr) (.read dbiPtr) 1))
        (Lib/checkRc (Lib/mdb_txn_commit (.read txnPtr)))
        (Lib/freeTxnPtr txnPtr)
        (.remove dbis dbi-name))
      (catch Exception e
        (raise "Fail to drop DBI: " dbi-name (ex-message e) {}))))

  (entries [this dbi-name]
    (assert (not closed?) "LMDB env is closed.")
    (let [^DBI dbi           (.get-dbi this dbi-name)
          i                  (.read ^Lib$MDB_dbiPointer (.-dbiPtr dbi))
          ^Rtx rtx           (get-rtx pool)
          ^Lib$MDB_txn txn   (.read ^Lib$MDB_txnPointer (.-txnPtr rtx))
          ^Lib$MDB_stat stat (Lib/allocateStat)]
      (try
        (Lib/mdb_stat txn i stat)
        (let [entries (.ms_entries stat)]
          (Lib/freeStat stat)
          entries)
        (catch Exception e
          (raise "Fail to get entries: " (ex-message e)
                 {:dbi dbi-name}))
        (finally (.reset rtx)))))

  (transact [this txs]
    (assert (not closed?) "LMDB env is closed.")
    (try
      (let [^Lib$MDB_txnPointer txnPtr (Lib/allocateTxnPtr)]
        (Lib/checkRc (Lib/mdb_txn_begin (.read envPtr) nil 0 txnPtr))
        (let [^Lib$MDB_txn txn (.read txnPtr)]
          (doseq [[op dbi-name k & r] txs
                  :let                [^DBI dbi (.get-dbi this dbi-name)]]
            (case op
              :put (let [[v kt vt flags] r]
                     (.put-key dbi k kt)
                     (.put-val dbi v vt)
                     (if flags
                       (.put dbi txn flags)
                       (.put dbi txn)))
              :del (let [[kt] r]
                     (.put-key dbi k kt)
                     (.del dbi txn))))
          (Lib/checkRc (Lib/mdb_txn_commit txn)))
        (Lib/freeTxnPtr txnPtr))
      (catch Lib$MapFullException _
        (let [^Lib$MDB_envinfo info (Lib/allocateEnvinfo)
              ^Lib$MDB_env env      (.read envPtr)]
          (Lib/checkRc (Lib/mdb_env_info env info))
          (Lib/checkRc (Lib/mdb_env_set_mapsize env (* 10 (.me_mapsize info))))
          (Lib/freeEnvinfo info)
          (.transact this txs)))
      (catch Exception e
        (raise "Fail to transact to LMDB: " (ex-message e) {:txs txs}))))

  (get-value [this dbi-name k]
    (.get-value this dbi-name k :data :data true))
  (get-value [this dbi-name k k-type]
    (.get-value this dbi-name k k-type :data true))
  (get-value [this dbi-name k k-type v-type]
    (.get-value this dbi-name k k-type v-type true))
  (get-value [this dbi-name k k-type v-type ignore-key?]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-value dbi rtx k k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-value: " (ex-message e)
                 {:dbi dbi-name :k k :k-type k-type :v-type v-type}))
        (finally (.reset rtx)))))

  (get-first [this dbi-name k-range]
    (.get-first this dbi-name k-range :data :data false))
  (get-first [this dbi-name k-range k-type]
    (.get-first this dbi-name k-range k-type :data false))
  (get-first [this dbi-name k-range k-type v-type]
    (.get-first this dbi-name k-range k-type v-type false))
  (get-first [this dbi-name k-range k-type v-type ignore-key?]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-first dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-first: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (.reset rtx)))))

  (get-range [this dbi-name k-range]
    (.get-range this dbi-name k-range :data :data false))
  (get-range [this dbi-name k-range k-type]
    (.get-range this dbi-name k-range k-type :data false))
  (get-range [this dbi-name k-range k-type v-type]
    (.get-range this dbi-name k-range k-type v-type false))
  (get-range [this dbi-name k-range k-type v-type ignore-key?]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-range dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-range: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (.reset rtx)))))

  (range-count [this dbi-name k-range]
    (.range-count this dbi-name k-range :data))
  (range-count [this dbi-name k-range k-type]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-range-count dbi rtx k-range k-type)
        (catch Exception e
          (raise "Fail to range-count: " (ex-message e)
                 {:dbi dbi-name :k-range k-range :k-type k-type}))
        (finally (.reset rtx)))))

  (get-some [this dbi-name pred k-range]
    (.get-some this dbi-name pred k-range :data :data false))
  (get-some [this dbi-name pred k-range k-type]
    (.get-some this dbi-name pred k-range k-type :data false))
  (get-some [this dbi-name pred k-range k-type v-type]
    (.get-some this dbi-name pred k-range k-type v-type false))
  (get-some [this dbi-name pred k-range k-type v-type ignore-key?]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-some dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-some: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (.reset rtx)))))

  (range-filter [this dbi-name pred k-range]
    (.range-filter this dbi-name pred k-range :data :data false))
  (range-filter [this dbi-name pred k-range k-type]
    (.range-filter this dbi-name pred k-range k-type :data false))
  (range-filter [this dbi-name pred k-range k-type v-type]
    (.range-filter this dbi-name pred k-range k-type v-type false))
  (range-filter [this dbi-name pred k-range k-type v-type ignore-key?]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-range-filtered dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to range-filter: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (.reset rtx)))))

  (range-filter-count [this dbi-name pred k-range]
    (.range-filter-count this dbi-name pred k-range :data))
  (range-filter-count [this dbi-name pred k-range k-type]
    (assert (not (.closed? this)) "LMDB env is closed.")
    (let [dbi      (.get-dbi this dbi-name)
          ^Rtx rtx (get-rtx pool)]
      (try
        (scan/fetch-range-filtered-count dbi rtx pred k-range k-type)
        (catch Exception e
          (raise "Fail to range-filter-count: " (ex-message e)
                 {:dbi dbi-name :k-range k-range :k-type k-type}))
        (finally (.reset rtx)))))
  )

(defmethod open-lmdb :graal
  [dir]
  (try
    (b/file dir)
    (let [^Lib$MDB_envPointer envPtr (Lib/allocateEnvPtr)]
      (Lib/checkRc (Lib/mdb_env_create envPtr))
      (let [^Lib$MDB_env env (.read envPtr)]
        (Lib/checkRc (Lib/mdb_env_set_mapsize
                       env (* ^long c/+init-db-size+ 1024 1024)))
        (Lib/checkRc (Lib/mdb_env_set_maxreaders env c/+max-readers+))
        (Lib/checkRc (Lib/mdb_env_set_maxdbs env c/+max-dbs+))
        (Lib/checkRc (Lib/mdb_env_open
                       env
                       (.get (CTypeConversion/toCString dir))
                       (mask-flags [(Lib/MDB_NORDAHEAD)
                                    (Lib/MDB_MAPASYNC)
                                    (Lib/MDB_WRITEMAP)])
                       0664))
        (->LMDB envPtr
                dir
                (->RtxPool (.read env) (ConcurrentHashMap.) 0)
                (ConcurrentHashMap.)
                false)))
    (catch Exception e
      (raise
        "Fail to open LMDB database: " (ex-message e)
        {:dir dir}))))
