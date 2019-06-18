package com.evolutiongaming.scache

import cats.effect.concurrent.Ref
import cats.effect.{Clock, Concurrent, Resource, Timer}
import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.scache.Cache.EntryRefs

import scala.concurrent.duration._

object ExpiringCache {

  def of[F[_] : Concurrent : Timer, K, V](expireAfter: FiniteDuration): Resource[F, Cache[F, K, V]] = {

    val cooldown      = expireAfter.toMillis / 5
    val expireAfterMs = expireAfter.toMillis + (cooldown / 2)
    val sleep         = Timer[F].sleep((expireAfterMs / 10).millis)

     def removeExpired(ref: Ref[F, EntryRefs[F, K, Entry[V]]]) = {

       def removeExpired(key: K, entryRefs: EntryRefs[F, K, Entry[V]]) = {

         def removeExpired(entry: Entry[V]) = {
           for {
             now    <- Clock[F].millis
             result <- if (entry.timestamp + expireAfterMs < now) ref.update { _ - key } else ().pure[F]
           } yield result
         }

         val entryRef = entryRefs.get(key)
         entryRef.foldMapM { entryRef =>
           for {
             entry  <- entryRef.get
             result <- entry match {
               case entry: Cache.Entry.Loaded[F, Entry[V]]  => removeExpired(entry.a)
               case _    : Cache.Entry.Loading[F, Entry[V]] => ().pure[F]
             }
           } yield result
         }
       }
       
       val fa = for {
         _         <- sleep
         entryRefs <- ref.get
         result    <- entryRefs.keys.toList.foldMapM { key => removeExpired(key, entryRefs) }
       } yield result

       fa.foreverM[Unit]
     }

     val result = for {
       ref   <- Ref[F].of(Cache.EntryRefs.empty[F, K, Entry[V]])
       cache  = Cache(ref)
       fiber <- Concurrent[F].start { removeExpired(ref) }
     } yield {
       val release = fiber.cancel
       val result = apply(ref, cache, cooldown)
       (result, release)
     }
     Resource(result)
   }


   def apply[F[_] : Monad : Clock, K, V](
     ref: Ref[F, Cache.EntryRefs[F, K, Entry[V]]],
     cache: Cache[F, K, Entry[V]],
     cooldown: Long,
   ): Cache[F, K, V] = {

     implicit val monoidUnit = Applicative.monoid[F, Unit]

     def touch(key: K, entry: Entry[V]) = {

       def touch(timestamp: Long): F[Unit] = {

         def touch(entryRef: Cache.EntryRef[F, Entry[V]]) = {
           entryRef.update {
             case entry: Cache.Entry.Loaded[F, Entry[V]]  => Cache.Entry.loaded(entry.a.touch(timestamp))
             case entry: Cache.Entry.Loading[F, Entry[V]] => entry
           }
         }

         for {
           map    <- ref.get
           result <- map.get(key).foldMap(touch)
         } yield result
       }

       def shouldTouch(now: Long) = (entry.timestamp + cooldown) <= now

       /*TODO randomize cooldown to avoid contention?*/
       for {
         now    <- Clock[F].millis
         result <- if (shouldTouch(now)) touch(now) else ().pure[F]
       } yield result
     }

     new Cache[F, K, V] {

       def get(key: K) = {
         for {
           entry <- cache.get(key)
           _     <- entry.foldMap { entry => touch(key, entry) }
         } yield for {
           entry <- entry
         } yield {
           entry.value
         }
       }

       def getOrUpdate(key: K)(value: => F[V]) = {
         for {
           entry <- cache.getOrUpdate(key) {
             for {
               value     <- value
               timestamp <- Clock[F].millis
             } yield {
               Entry(value, timestamp)
             }
           }
           _    <- touch(key, entry)
         } yield {
           entry.value
         }
       }

       def put(key: K, value: V) = {
         for {
           timestamp <- Clock[F].millis
           entry      = Entry(value, timestamp)
           entry     <- cache.put(key, entry)
         } yield for {
           entry <- entry
         } yield for {
           entry <- entry
         } yield {
           entry.value
         }
       }

       def keys = cache.keys

       def values = {
         for {
           values <- cache.values
         } yield {
           values.mapValues { e =>
             for {
               e <- e
             } yield {
               e.value
             }
           }
         }
       }

       def remove(key: K) = {
         for {
           entry <- cache.remove(key)
         } yield for {
           entry <- entry
         } yield for {
           entry <- entry
         } yield {
           entry.value
         }
       }

       def clear = cache.clear
     }
   }


  final case class Entry[A](value: A, timestamp: Long) { self =>

    def touch(timestamp: Long): Entry[A] = {
      if (timestamp > self.timestamp) copy(timestamp = timestamp) else self
    }
  }
}