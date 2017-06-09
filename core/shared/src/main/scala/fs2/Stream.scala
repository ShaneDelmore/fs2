package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import cats.{ ~>, Applicative, Eq, Functor, Monoid, Semigroup }
import cats.effect.{ Effect, IO, Sync }
import cats.implicits._

import fs2.async.mutable.Queue
import fs2.internal.{ Algebra, Free }

/**
 * A stream producing output of type `O` and which may evaluate `F`
 * effects. If `F` is [[Pure]], the stream evaluates no effects.
 *
 * Laws (using infix syntax):
 *
 * `append` forms a monoid in conjunction with `empty`:
 *   - `empty append s == s` and `s append empty == s`.
 *   - `(s1 append s2) append s3 == s1 append (s2 append s3)`
 *
 * And `cons` is consistent with using `++` to prepend a single segment:
 *   - `s.cons(seg) == Stream.segment(seg) ++ s`
 *
 * `Stream.fail` propagates until being caught by `onError`:
 *   - `Stream.fail(e) onError h == h(e)`
 *   - `Stream.fail(e) ++ s == Stream.fail(e)`
 *   - `Stream.fail(e) flatMap f == Stream.fail(e)`
 *
 * `Stream` forms a monad with `emit` and `flatMap`:
 *   - `Stream.emit >=> f == f` (left identity)
 *   - `f >=> Stream.emit === f` (right identity - note weaker equality notion here)
 *   - `(f >=> g) >=> h == f >=> (g >=> h)` (associativity)
 *  where `Stream.emit(a)` is defined as `segment(Segment.singleton(a)) and
 *  `f >=> g` is defined as `a => a flatMap f flatMap g`
 *
 * The monad is the list-style sequencing monad:
 *   - `(a ++ b) flatMap f == (a flatMap f) ++ (b flatMap f)`
 *   - `Stream.empty flatMap f == Stream.empty`
 *
 * '''Technical notes'''
 *
 * ''Note:'' since the segment structure of the stream is observable, and
 * `s flatMap Stream.emit` produces a stream of singleton segments,
 * the right identity law uses a weaker notion of equality, `===` which
 * normalizes both sides with respect to segment structure:
 *
 *   `(s1 === s2) = normalize(s1) == normalize(s2)`
 *   where `==` is full equality
 *   (`a == b` iff `f(a)` is identical to `f(b)` for all `f`)
 *
 * `normalize(s)` can be defined as `s.repeatPull(_.echo1)`, which just
 * produces a singly-chunked stream from any input stream `s`.
 *
 * ''Note:'' For efficiency `[[Stream.map]]` function operates on an entire
 * segment at a time and preserves segment structure, which differs from
 * the `map` derived from the monad (`s map f == s flatMap (f andThen Stream.emit)`)
 * which would produce singleton segments. In particular, if `f` throws errors, the
 * segmented version will fail on the first ''segment'' with an error, while
 * the unsegmented version will fail on the first ''element'' with an error.
 * Exceptions in pure code like this are strongly discouraged.
 *
 * @hideImplicitConversion PureOps
 * @hideImplicitConversion EmptyOps
 * @hideImplicitConversion covaryPure
 */
final class Stream[+F[_],+O] private(private val free: Free[Algebra[Nothing,Nothing,?],Unit]) extends AnyVal {

  private[fs2] def get[F2[x]>:F[x],O2>:O]: Free[Algebra[F2,O2,?],Unit] = free.asInstanceOf[Free[Algebra[F2,O2,?],Unit]]

  /**
   * Returns a stream of `O` values wrapped in `Right` until the first error, which is emitted wrapped in `Left`.
   *
   * @example {{{
   * scala> (Stream(1,2,3) ++ Stream.fail(new RuntimeException) ++ Stream(4,5,6)).attempt.toList
   * res0: List[Either[Throwable,Int]] = List(Right(1), Right(2), Right(3), Left(java.lang.RuntimeException))
   * }}}
   *
   * [[rethrow]] is the inverse of `attempt`, with the caveat that anything after the first failure is discarded.
   */
  def attempt: Stream[F,Either[Throwable,O]] =
    map(Right(_)).onError(e => Stream.emit(Left(e)))

  /**
   * Alias for `_.map(_ => o2)`.
   *
   * @example {{{
   * scala> Stream(1,2,3).as(0).toList
   * res0: List[Int] = List(0, 0, 0)
   * }}}
   */
  def as[O2](o2: O2): Stream[F,O2] = map(_ => o2)

  /**
   * Behaves like the identity function, but requests `n` elements at a time from the input.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
   * scala> Stream.range(0, 100).covary[IO].
   *      |   evalMap(i => IO { buf += s">$i"; i }).
   *      |   buffer(4).
   *      |   evalMap(i => IO { buf += s"<$i"; i }).
   *      |   take(10).
   *      |   runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
   * scala> buf.toList
   * res1: List[String] = List(>0, >1, >2, >3, <0, <1, <2, <3, >4, >5, >6, >7, <4, <5, <6, <7, >8, >9, >10, >11, <8, <9)
   * }}}
   */
  def buffer(n: Int): Stream[F,O] =
    this.repeatPull { _.unconsN(n, allowFewer = true).flatMap {
      case Some((hd,tl)) => Pull.output(hd).as(Some(tl))
      case None => Pull.pure(None)
    }}

  /**
   * Behaves like the identity stream, but emits no output until the source is exhausted.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
   * scala> Stream.range(0, 10).covary[IO].
   *      |   evalMap(i => IO { buf += s">$i"; i }).
   *      |   bufferAll.
   *      |   evalMap(i => IO { buf += s"<$i"; i }).
   *      |   take(4).
   *      |   runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(0, 1, 2, 3)
   * scala> buf.toList
   * res1: List[String] = List(>0, >1, >2, >3, >4, >5, >6, >7, >8, >9, <0, <1, <2, <3)
   * }}}
   */
  def bufferAll: Stream[F,O] = bufferBy(_ => true)

  /**
   * Behaves like the identity stream, but requests elements from its
   * input in blocks that end whenever the predicate switches from true to false.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
   * scala> Stream.range(0, 10).covary[IO].
   *      |   evalMap(i => IO { buf += s">$i"; i }).
   *      |   bufferBy(_ % 2 == 0).
   *      |   evalMap(i => IO { buf += s"<$i"; i }).
   *      |   runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
   * scala> buf.toList
   * res1: List[String] = List(>0, >1, <0, <1, >2, >3, <2, <3, >4, >5, <4, <5, >6, >7, <6, <7, >8, >9, <8, <9)
   * }}}
   */
  def bufferBy(f: O => Boolean): Stream[F,O] = {
    def go(buffer: Catenable[Chunk[O]], last: Boolean, s: Stream[F,O]): Pull[F,O,Unit] = {
      s.pull.unconsChunk.flatMap {
        case Some((hd,tl)) =>
          val (out, buf, newLast) = {
            hd.fold((Catenable.empty: Catenable[Chunk[O]], Vector.empty[O], last)) { case ((out, buf, last), i) =>
              val cur = f(i)
              if (!cur && last) (out :+ Chunk.vector(buf :+ i), Vector.empty, cur)
              else (out, buf :+ i, cur)
            }.run
          }
          if (out.isEmpty) {
            go(buffer :+ Chunk.vector(buf), newLast, tl)
          } else {
            Pull.output(Segment.catenated(buffer ++ out)) >> go(Catenable.singleton(Chunk.vector(buf)), newLast, tl)
          }
        case None => Pull.output(Segment.catenated(buffer))
      }
    }
    go(Catenable.empty, false, this).stream
  }

  /**
   * Emits only elements that are distinct from their immediate predecessors
   * according to `f`, using natural equality for comparison.
   *
   * Note that `f` is called for each element in the stream multiple times
   * and hence should be fast (e.g., an accessor). It is not intended to be
   * used for computationally intensive conversions. For such conversions,
   * consider something like: `src.map(o => (o, f(o))).changesBy(_._2).map(_._1)`
   *
   * @example {{{
   * scala> import cats.implicits._
   * scala> Stream(1,1,2,4,6,9).changesBy(_ % 2).toList
   * res0: List[Int] = List(1, 2, 9)
   * }}}
   */
  def changesBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F,O] =
    filterWithPrevious((o1, o2) => eq.neqv(f(o1), f(o2)))

  /**
   * Outputs all chunks from the source stream.
   *
   * @example {{{
   * scala> (Stream(1) ++ Stream(2, 3) ++ Stream(4, 5, 6)).chunks.toList
   * res0: List[Chunk[Int]] = List(Chunk(1), Chunk(2, 3), Chunk(4, 5, 6))
   * }}}
   */
  def chunks: Stream[F,Chunk[O]] =
    this.repeatPull(_.unconsChunk.flatMap { case None => Pull.pure(None); case Some((hd,tl)) => Pull.output1(hd).as(Some(tl)) })

  /**
   * Outputs chunk with a limited maximum size, splitting as necessary.
   *
   * @example {{{
   * scala> (Stream(1) ++ Stream(2, 3) ++ Stream(4, 5, 6)).chunkLimit(2).toList
   * res0: List[Chunk[Int]] = List(Chunk(1), Chunk(2, 3), Chunk(4, 5), Chunk(6))
   * }}}
   */
  def chunkLimit(n: Int): Stream[F,Chunk[O]] =
    this repeatPull { _.unconsLimit(n) flatMap {
      case None => Pull.pure(None)
      case Some((hd,tl)) => Pull.output1(hd.toChunk).as(Some(tl))
    }}

  /**
   * Filters and maps simultaneously. Calls `collect` on each segment in the stream.
   *
   * @example {{{
   * scala> Stream(Some(1), Some(2), None, Some(3), None, Some(4)).collect { case Some(i) => i }.toList
   * res0: List[Int] = List(1, 2, 3, 4)
   * }}}
   */
  def collect[O2](pf: PartialFunction[O, O2]): Stream[F,O2] = mapSegments(_.collect(pf))

  /**
   * Emits the first element of the stream for which the partial function is defined.
   *
   * @example {{{
   * scala> Stream(None, Some(1), Some(2), None, Some(3)).collectFirst { case Some(i) => i }.toList
   * res0: List[Int] = List(1)
   * }}}
   */
  def collectFirst[O2](pf: PartialFunction[O, O2]): Stream[F,O2] =
    this.pull.find(pf.isDefinedAt).flatMap {
      case None => Pull.pure(None)
      case Some((hd,tl)) => Pull.output1(pf(hd)).as(None)
    }.stream

  /**
   * Prepends a segment onto the front of this stream.
   *
   * @example {{{
   * scala> Stream(1,2,3).cons(Segment.vector(Vector(-1, 0))).toList
   * res0: List[Int] = List(-1, 0, 1, 2, 3)
   * }}}
   */
  def cons[O2>:O](s: Segment[O2,Unit]): Stream[F,O2] =
    Stream.segment(s) ++ this

  /**
   * Prepends a chunk onto the front of this stream.
   *
   * @example {{{
   * scala> Stream(1,2,3).consChunk(Chunk.vector(Vector(-1, 0))).toList
   * res0: List[Int] = List(-1, 0, 1, 2, 3)
   * }}}
   */
  def consChunk[O2>:O](c: Chunk[O2]): Stream[F,O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  /**
   * Prepends a single value onto the front of this stream.
   *
   * @example {{{
   * scala> Stream(1,2,3).cons1(0).toList
   * res0: List[Int] = List(0, 1, 2, 3)
   * }}}
   */
  def cons1[O2>:O](o: O2): Stream[F,O2] =
    cons(Segment.singleton(o))

  /**
   * Lifts this stream to the specified output type.
   *
   * @example {{{
   * scala> Stream(Some(1), Some(2), Some(3)).covaryOutput[Option[Int]]
   * res0: Stream[Pure,Option[Int]] = Stream(..)
   * }}}
   */
  def covaryOutput[O2>:O]: Stream[F,O2] = this.asInstanceOf[Stream[F,O2]]

  /**
   * Skips the first element that matches the predicate.
   *
   * @example {{{
   * scala> Stream.range(1, 10).delete(_ % 2 == 0).toList
   * res0: List[Int] = List(1, 3, 4, 5, 6, 7, 8, 9)
   * }}}
   */
  def delete(p: O => Boolean): Stream[F,O] =
    this.pull.takeWhile(o => !p(o)).flatMap {
      case None => Pull.pure(None)
      case Some(s) => s.drop(1).pull.echo
    }.stream

  /**
   * Removes all output values from this stream.
   *
   * Often used with `merge` to run one side of the merge for its effect
   * while getting outputs from the opposite side of the merge.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.eval(IO(println("x"))).drain.runLog.unsafeRunSync
   * res0: Vector[Nothing] = Vector()
   * }}}
   */
  def drain: Stream[F, Nothing] = this.mapSegments(_ => Segment.empty)

  /**
   * Drops `n` elements of the input, then echoes the rest.
   *
   * @example {{{
   * scala> Stream.range(0,10).drop(5).toList
   * res0: List[Int] = List(5, 6, 7, 8, 9)
   * }}}
   */
  def drop(n: Long): Stream[F,O] =
    this.pull.drop(n).flatMap(_.map(_.pull.echo).getOrElse(Pull.done)).stream

  /**
   * Drops the last element.
   *
   * @example {{{
   * scala> Stream.range(0,10).dropLast.toList
   * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8)
   * }}}
   */
  def dropLast: Stream[F,O] = dropLastIf(_ => true)

  /**
   * Drops the last element if the predicate evaluates to true.
   *
   * @example {{{
   * scala> Stream.range(0,10).dropLastIf(_ > 5).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8)
   * }}}
   */
  def dropLastIf(p: O => Boolean): Stream[F,O] = {
    def go(last: Chunk[O], s: Stream[F,O]): Pull[F,O,Unit] = {
      s.pull.unconsChunk.flatMap {
        case Some((hd,tl)) =>
          if (hd.nonEmpty) Pull.output(last) >> go(hd,tl)
          else go(last,tl)
        case None =>
          val o = last(last.size - 1)
          if (p(o)) {
            val (prefix,_) = last.strict.splitAt(last.size - 1)
            Pull.output(prefix)
          } else Pull.output(last)
      }
    }
    def unconsNonEmptyChunk(s: Stream[F,O]): Pull[F,Nothing,Option[(Chunk[O],Stream[F,O])]] =
      s.pull.unconsChunk.flatMap {
        case Some((hd,tl)) =>
          if (hd.nonEmpty) Pull.pure(Some((hd,tl)))
          else unconsNonEmptyChunk(tl)
        case None => Pull.pure(None)
      }
    unconsNonEmptyChunk(this).flatMap {
      case Some((hd,tl)) => go(hd,tl)
      case None => Pull.done
    }.stream
  }

  /**
   * Outputs all but the last `n` elements of the input.
   *
   * @example {{{
   * scala> Stream.range(0,10).dropRight(5).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4)
   * }}}
   */
  def dropRight(n: Int): Stream[F,O] = {
    if (n <= 0) this
    else {
      def go(acc: Vector[O], s: Stream[F,O]): Pull[F,O,Option[Unit]] = {
        s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd,tl)) =>
            val all = acc ++ hd.toVector
            Pull.output(Chunk.vector(all.dropRight(n))) >> go(all.takeRight(n), tl)
        }
      }
      go(Vector.empty, this).stream
    }
  }

  /**
   * Like [[dropWhile]], but drops the first value which tests false.
   *
   * @example {{{
   * scala> Stream.range(0,10).dropThrough(_ != 4).toList
   * res0: List[Int] = List(5, 6, 7, 8, 9)
   * }}}
   */
  def dropThrough(p: O => Boolean): Stream[F,O] =
    this.pull.dropThrough(p).flatMap(_.map(_.pull.echo).getOrElse(Pull.done)).stream

  /**
   * Drops elements from the head of this stream until the supplied predicate returns false.
   *
   * @example {{{
   * scala> Stream.range(0,10).dropWhile(_ != 4).toList
   * res0: List[Int] = List(4, 5, 6, 7, 8, 9)
   * }}}
   */
  def dropWhile(p: O => Boolean): Stream[F,O] =
    this.pull.dropWhile(p).flatMap(_.map(_.pull.echo).getOrElse(Pull.done)).stream

  /**
   * Emits `true` as soon as a matching element is received, else `false` if no input matches.
   *
   * @example {{{
   * scala> Stream.range(0,10).exists(_ == 4).toList
   * res0: List[Boolean] = List(true)
   * scala> Stream.range(0,10).exists(_ == 10).toList
   * res1: List[Boolean] = List(false)
   * }}}
   */
  def exists(p: O => Boolean): Stream[F, Boolean] =
    this.pull.forall(!p(_)).flatMap(r => Pull.output1(!r)).stream

  /**
   * Emits only inputs which match the supplied predicate.
   *
   * @example {{{
   * scala> Stream.range(0,10).filter(_ % 2 == 0).toList
   * res0: List[Int] = List(0, 2, 4, 6, 8)
   * }}}
   */
  def filter(p: O => Boolean): Stream[F,O] = mapSegments(_ filter p)

  /**
   * Like `filter`, but the predicate `f` depends on the previously emitted and
   * current elements.
   *
   * @example {{{
   * scala> Stream(1, -1, 2, -2, 3, -3, 4, -4).filterWithPrevious((previous, current) => previous < current).toList
   * res0: List[Int] = List(1, 2, 3, 4)
   * }}}
   */
  def filterWithPrevious(f: (O, O) => Boolean): Stream[F,O] = {
    def go(last: O, s: Stream[F,O]): Pull[F,O,Option[Unit]] =
      s.pull.uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          // Check if we can emit this chunk unmodified
          Pull.segment(hd.fold((true, last)) { case ((acc, last), o) => (acc && f(last, o), o) }).flatMap { case (allPass, newLast) =>
            if (allPass) {
              Pull.output(hd) >> go(newLast, tl)
            } else {
              Pull.segment(hd.fold((Vector.empty[O], last)) { case ((acc, last), o) =>
                if (f(last, o)) (acc :+ o, o)
                else (acc, last)
              }).flatMap { case (acc, newLast) =>
                Pull.output(Chunk.vector(acc)) >> go(newLast, tl)
              }
            }
          }
      }
    this.pull.uncons1.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) => Pull.output1(hd) >> go(hd, tl)
    }.stream
  }

  /**
   * Emits the first input (if any) which matches the supplied predicate.
   *
   * @example {{{
   * scala> Stream.range(1,10).find(_ % 2 == 0).toList
   * res0: List[Int] = List(2)
   * }}}
   */
  def find(f: O => Boolean): Stream[F,O] =
    this.pull.find(f).flatMap { _.map { case (hd,tl) => Pull.output1(hd) }.getOrElse(Pull.done) }.stream

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator,
   * and emits a single element stream.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).fold(0)(_ + _).toList
   * res0: List[Int] = List(15)
   * }}}
   */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] =
    this.pull.fold(z)(f).flatMap(Pull.output1).stream

  /**
   * Folds all inputs using the supplied binary operator, and emits a single-element
   * stream, or the empty stream if the input is empty.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).fold1(_ + _).toList
   * res0: List[Int] = List(15)
   * }}}
   */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] =
    this.pull.fold1(f).flatMap(_.map(Pull.output1).getOrElse(Pull.done)).stream

  /**
   * Alias for `map(f).foldMonoid`.
   *
   * @example {{{
   * scala> import cats.implicits._
   * scala> Stream(1, 2, 3, 4, 5).foldMap(_ => 1).toList
   * res0: List[Int] = List(5)
   * }}}
   */
  def foldMap[O2](f: O => O2)(implicit O2: Monoid[O2]): Stream[F,O2] =
    fold(O2.empty)((acc, o) => O2.combine(acc, f(o)))

  /**
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).forall(_ < 10).toList
   * res0: List[Boolean] = List(true)
   * }}}
   */
  def forall(p: O => Boolean): Stream[F, Boolean] =
    this.pull.forall(p).flatMap(Pull.output1).stream

  /**
   * Partitions the input into a stream of chunks according to a discriminator function.
   *
   * Each chunk in the source stream is grouped using the supplied discriminator function
   * and the results of the grouping are emitted each time the discriminator function changes
   * values.
   *
   * @example {{{
   * scala> import cats.implicits._
   * scala> Stream("Hello", "Hi", "Greetings", "Hey").groupBy(_.head).toList
   * res0: List[(Char,Vector[String])] = List((H,Vector(Hello, Hi)), (G,Vector(Greetings)), (H,Vector(Hey)))
   * }}}
   */
  def groupBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Vector[O])] = {

    def go(current: Option[(O2,Vector[O])], s: Stream[F,O]): Pull[F,(O2,Vector[O]),Unit] = {
      s.pull.unconsChunk.flatMap {
        case Some((hd,tl)) =>
          val (k1, out) = current.getOrElse((f(hd(0)), Vector[O]()))
          doChunk(hd, tl, k1, out, Vector.empty)
        case None =>
          val l = current.map { case (k1, out) => Pull.output1((k1, out)) } getOrElse Pull.pure(())
          l >> Pull.done
      }
    }

    @annotation.tailrec
    def doChunk(chunk: Chunk[O], s: Stream[F,O], k1: O2, out: Vector[O], acc: Vector[(O2, Vector[O])]): Pull[F,(O2,Vector[O]),Unit] = {
      val differsAt = chunk.indexWhere(v => eq.neqv(f(v), k1)).getOrElse(-1)
      if (differsAt == -1) {
        // whole chunk matches the current key, add this chunk to the accumulated output
        val newOut: Vector[O] = out ++ chunk.toVector
        if (acc.isEmpty) {
          go(Some((k1, newOut)), s)
        } else {
          // potentially outputs one additional chunk (by splitting the last one in two)
          Pull.output(Chunk.vector(acc)) >> go(Some((k1, newOut)), s)
        }
      } else {
        // at least part of this chunk does not match the current key, need to group and retain chunkiness
        // split the chunk into the bit where the keys match and the bit where they don't
        val matching = chunk.take(differsAt)
        val newOut: Vector[O] = out ++ matching.toVector
        val nonMatching = chunk.drop(differsAt).fold(_ => Chunk.empty, identity).toChunk
        // nonMatching is guaranteed to be non-empty here, because we know the last element of the chunk doesn't have
        // the same key as the first
        val k2 = f(nonMatching(0))
        doChunk(nonMatching, s, k2, Vector[O](), acc :+ ((k1, newOut)))
      }
    }

    go(None, this).stream
  }

  /**
   * Emits the first element of this stream (if non-empty) and then halts.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).head.toList
   * res0: List[Int] = List(1)
   * }}}
   */
  def head: Stream[F,O] = take(1)

  /**
   * Emits the specified separator between every pair of elements in the source stream.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).intersperse(0).toList
   * res0: List[Int] = List(1, 0, 2, 0, 3, 0, 4, 0, 5)
   * }}}
   */
  def intersperse[O2 >: O](separator: O2): Stream[F,O2] =
    this.pull.echo1.flatMap {
      case None => Pull.pure(None)
      case Some(s) =>
        s.repeatPull { _.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd,tl)) =>
            val interspersed = {
              val bldr = Vector.newBuilder[O2]
              hd.toVector.foreach { o => bldr += separator; bldr += o }
              Chunk.vector(bldr.result)
            }
            Pull.output(interspersed) >> Pull.pure(Some(tl))
        }}.pull.echo
    }.stream

  /**
   * Returns the last element of this stream, if non-empty.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).last.toList
   * res0: List[Option[Int]] = List(Some(3))
   * }}}
   */
  def last: Stream[F,Option[O]] =
    this.pull.last.flatMap(Pull.output1).stream

  /**
   * Returns the last element of this stream, if non-empty, otherwise the supplied `fallback` value.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).lastOr(0).toList
   * res0: List[Int] = List(3)
   * scala> Stream.empty.lastOr(0).toList
   * res1: List[Int] = List(0)
   * }}}
   */
  def lastOr[O2 >: O](fallback: => O2): Stream[F,O2] =
    this.pull.last.flatMap {
      case Some(o) => Pull.output1(o)
      case None => Pull.output1(fallback)
    }.stream

  /**
    * Maps a running total according to `S` and the input with the function `f`.
    *
    * @example {{{
    * scala> Stream("Hello", "World").mapAccumulate(0)((l, s) => (l + s.length, s.head)).toVector
    * res0: Vector[(Int, Char)] = Vector((5,H), (10,W))
    * }}}
    */
  def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] = {
    val f2 = (s: S, o: O) => {
      val (newS, newO) = f(s, o)
      (newS, (newS, newO))
    }
    this.scanSegments(init)((acc, seg) => seg.mapAccumulate(acc)(f2).mapResult(_._2))
  }

  /**
   * Applies the specified pure function to each input and emits the result.
   *
   * @example {{{
   * scala> Stream("Hello", "World!").map(_.size).toList
   * res0: List[Int] = List(5, 6)
   * }}}
   */
  def map[O2](f: O => O2): Stream[F,O2] =
    this.repeatPull(_.uncons.flatMap { case None => Pull.pure(None); case Some((hd, tl)) => Pull.output(hd map f).as(Some(tl)) })

  /**
   * Applies the specified pure function to each segment in this stream.
   *
   * @example {{{
   * scala> (Stream.range(1,5) ++ Stream.range(5,10)).mapSegments(s => s.scan(0)(_ + _).voidResult).toList
   * res0: List[Int] = List(0, 1, 3, 6, 10, 0, 5, 11, 18, 26, 35)
   * }}}
   */
  def mapSegments[O2](f: Segment[O,Unit] => Segment[O2,Unit]): Stream[F,O2] =
    this.repeatPull { _.uncons.flatMap { case None => Pull.pure(None); case Some((hd,tl)) => Pull.output(f(hd)).as(Some(tl)) }}

  /**
   * Behaves like the identity function but halts the stream on an error and does not return the error.
   *
   * @example {{{
   * scala> (Stream(1,2,3) ++ Stream.fail(new RuntimeException) ++ Stream(4, 5, 6)).mask.toList
   * res0: List[Int] = List(1, 2, 3)
   * }}}
   */
  def mask: Stream[F,O] = this.onError(_ => Stream.empty)

  /**
   * Emits each output wrapped in a `Some` and emits a `None` at the end of the stream.
   *
   * `s.noneTerminate.unNoneTerminate == s`
   *
   * @example {{{
   * scala> Stream(1,2,3).noneTerminate.toList
   * res0: List[Option[Int]] = List(Some(1), Some(2), Some(3), None)
   * }}}
   */
  def noneTerminate: Stream[F,Option[O]] = map(Some(_)) ++ Stream.emit(None)

  /**
   * Repeat this stream an infinite number of times.
   *
   * `s.repeat == s ++ s ++ s ++ ...`
   *
   * @example {{{
   * scala> Stream(1,2,3).repeat.take(8).toList
   * res0: List[Int] = List(1, 2, 3, 1, 2, 3, 1, 2)
   * }}}
   */
  def repeat: Stream[F,O] = this ++ repeat

  /**
   * Converts a `Stream[F,Either[Throwable,O]]` to a `Stream[F,O]`, which emits right values and fails upon the first `Left(t)`.
   * Preserves chunkiness.
   *
   * @example {{{
   * scala> Stream(Right(1), Right(2), Left(new RuntimeException), Right(3)).rethrow.onError(t => Stream(-1)).toList
   * res0: List[Int] = List(-1)
   * }}}
   */
  def rethrow[O2](implicit ev: O <:< Either[Throwable,O2]): Stream[F,O2] = {
    val _ = ev // Convince scalac that ev is used
    this.asInstanceOf[Stream[F,Either[Throwable,O2]]].segments.flatMap { s =>
      val errs = s.collect { case Left(e) => e }
      errs.uncons1 match {
        case Left(()) => Stream.segment(s.collect { case Right(i) => i })
        case Right((hd,tl)) => Stream.fail(hd)
      }
    }
  }

  /** Alias for [[fold1]]. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = fold1(f)

  /**
   * Left fold which outputs all intermediate results.
   *
   * @example {{{
   * scala> Stream(1,2,3,4).scan(0)(_ + _).toList
   * res0: List[Int] = List(0, 1, 3, 6, 10)
   * }}}
   *
   * More generally:
   *   `Stream().scan(z)(f) == Stream(z)`
   *   `Stream(x1).scan(z)(f) == Stream(z, f(z,x1))`
   *   `Stream(x1,x2).scan(z)(f) == Stream(z, f(z,x1), f(f(z,x1),x2))`
   *   etc
   */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] =
    scan_(z)(f).stream

  private def scan_[O2](z: O2)(f: (O2, O) => O2): Pull[F,O2,Unit] =
    this.pull.uncons.flatMap {
      case None => Pull.output1(z) >> Pull.done
      case Some((hd,tl)) =>
        Pull.segment(hd.scan(z, emitFinal = false)(f)).flatMap { acc =>
          tl.scan_(acc)(f)
        }
    }

  /**
   * Like `[[scan]]`, but uses the first element of the stream as the seed.
   *
   * @example {{{
   * scala> Stream(1,2,3,4).scan1(_ + _).toList
   * res0: List[Int] = List(1, 3, 6, 10)
   * }}}
   */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] =
    this.pull.uncons1.flatMap {
      case None => Pull.done
      case Some((hd,tl)) => tl.scan_(hd: O2)(f)
    }.stream

  /**
   * Outputs the segments of this stream as output values, ensuring each segment has maximum size `n`, splitting segments as necessary.
   *
   * @example {{{
   * scala> Stream(1,2,3).repeat.segmentLimit(2).take(5).toList
   * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2), Chunk(3), Chunk(1, 2), Chunk(3), Chunk(1, 2))
   * }}}
   */
  def segmentLimit(n: Int): Stream[F,Segment[O,Unit]] =
    this repeatPull { _.unconsLimit(n) flatMap {
      case Some((hd,tl)) => Pull.output1(hd).as(Some(tl))
      case None => Pull.pure(None)
    }}

  /**
   * Outputs segments of size `n`.
   *
   * Segments from the source stream are split as necessary.
   * If `allowFewer` is true, the last segment that is emitted may have less than `n` elements.
   *
   * @example {{{
   * scala> Stream(1,2,3).repeat.segmentN(2).take(5).toList
   * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2), catenated(Chunk(3), Chunk(1)), Chunk(2, 3), Chunk(1, 2), catenated(Chunk(3), Chunk(1)))
   * }}}
   */
  def segmentN(n: Int, allowFewer: Boolean = true): Stream[F,Segment[O,Unit]] =
    this repeatPull { _.unconsN(n, allowFewer).flatMap {
      case Some((hd,tl)) => Pull.output1(hd).as(Some(tl))
      case None => Pull.pure(None)
    }}

  /**
   * Outputs all segments from the source stream.
   *
   * @example {{{
   * scala> Stream(1,2,3).repeat.segments.take(5).toList
   * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3))
   * }}}
   */
  def segments: Stream[F,Segment[O,Unit]] =
    this.repeatPull(_.uncons.flatMap { case None => Pull.pure(None); case Some((hd,tl)) => Pull.output1(hd).as(Some(tl)) })

  /**
   * Groups inputs in fixed size chunks by passing a "sliding window"
   * of size `n` over them. If the input contains less than or equal to
   * `n` elements, only one chunk of this size will be emitted.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4).sliding(2).toList
   * res0: List[scala.collection.immutable.Queue[Int]] = List(Queue(1, 2), Queue(2, 3), Queue(3, 4))
   * }}}
   * @throws scala.IllegalArgumentException if `n` <= 0
   */
  def sliding(n: Int): Stream[F,collection.immutable.Queue[O]] = {
    require(n > 0, "n must be > 0")
    def go(window: collection.immutable.Queue[O], s: Stream[F,O]): Pull[F,collection.immutable.Queue[O],Unit] = {
      s.pull.uncons.flatMap {
        case None => Pull.done
        case Some((hd,tl)) =>
          hd.scan(window)((w, i) => w.dequeue._2.enqueue(i)).drop(1) match {
            case Left((w2,_)) => go(w2, tl)
            case Right(out) => Pull.segment(out).flatMap { window => go(window, tl) }
          }
      }
    }
    this.pull.unconsN(n, true).flatMap {
      case None => Pull.done
      case Some((hd, tl)) =>
        val window = hd.fold(collection.immutable.Queue.empty[O])(_.enqueue(_)).run
        Pull.output1(window) >> go(window, tl)
    }.stream
  }

  /**
   * Breaks the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   *
   * @example {{{
   * scala> Stream.range(0, 10).split(_ % 4 == 0).toList
   * res0: List[Segment[Int,Unit]] = List(empty, catenated(Chunk(1), Chunk(2), Chunk(3), empty), catenated(Chunk(5), Chunk(6), Chunk(7), empty), Chunk(9))
   * }}}
   */
  def split(f: O => Boolean): Stream[F,Segment[O,Unit]] = {
    def go(buffer: Catenable[Segment[O,Unit]], s: Stream[F,O]): Pull[F,Segment[O,Unit],Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd,tl)) =>
          hd.splitWhile(o => !(f(o))) match {
            case Left((_,out)) =>
              if (out.isEmpty) go(buffer, tl)
              else go(buffer ++ out, tl)
            case Right((out,tl2)) =>
              val b2 = if (out.nonEmpty) buffer ++ out else buffer
              (if (b2.nonEmpty) Pull.output1(Segment.catenated(b2)) else Pull.pure(())) >>
                go(Catenable.empty, tl.cons(tl2.drop(1).fold(_ => Segment.empty, identity)))
          }
        case None =>
          if (buffer.nonEmpty) Pull.output1(Segment.catenated(buffer)) else Pull.done
      }
    }
    go(Catenable.empty, this).stream
  }

  /**
   * Emits all elements of the input except the first one.
   *
   * @example {{{
   * scala> Stream(1,2,3).tail.toList
   * res0: List[Int] = List(2, 3)
   * }}}
   */
  def tail: Stream[F,O] = drop(1)

  /**
   * Emits the first `n` elements of this stream.
   *
   * @example {{{
   * scala> Stream.range(0,1000).take(5).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4)
   * }}}
   */
  def take(n: Long): Stream[F,O] = this.pull.take(n).stream

  /**
   * Emits the last `n` elements of the input.
   *
   * @example {{{
   * scala> Stream.range(0,1000).takeRight(5).toList
   * res0: List[Int] = List(995, 996, 997, 998, 999)
   * }}}
   */
  def takeRight(n: Long): Stream[F,O] = this.pull.takeRight(n).flatMap(Pull.output).stream

  /**
   * Like [[takeWhile]], but emits the first value which tests false.
   *
   * @example {{{
   * scala> Stream.range(0,1000).takeThrough(_ != 5).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4, 5)
   * }}}
   */
  def takeThrough(p: O => Boolean): Stream[F,O] = this.pull.takeThrough(p).stream

  /**
   * Emits the longest prefix of the input for which all elements test true according to `f`.
   *
   * @example {{{
   * scala> Stream.range(0,1000).takeWhile(_ != 5).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4)
   * }}}
   */
  def takeWhile(p: O => Boolean): Stream[F,O] = this.pull.takeWhile(p).stream

  /**
   * Converts the input to a stream of 1-element chunks.
   *
   * @example {{{
   * scala> (Stream(1,2,3) ++ Stream(4,5,6)).unchunk.segments.toList
   * res0: List[Segment[Int,Unit]] = List(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6))
   * }}}
   */
  def unchunk: Stream[F,O] =
    this repeatPull { _.uncons1.flatMap { case None => Pull.pure(None); case Some((hd,tl)) => Pull.output1(hd).as(Some(tl)) }}

  /**
   * Filters any 'None'.
   *
   * @example {{{
   * scala> Stream(Some(1), Some(2), None, Some(3), None).unNone.toList
   * res0: List[Int] = List(1, 2, 3)
   * }}}
   */
  def unNone[O2](implicit ev: O <:< Option[O2]): Stream[F,O2] = {
    val _ = ev // Convince scalac that ev is used
    this.asInstanceOf[Stream[F,Option[O2]]].collect { case Some(o2) => o2 }
  }

  /**
   * Halts the input stream at the first `None`.
   *
   * @example {{{
   * scala> Stream(Some(1), Some(2), None, Some(3), None).unNoneTerminate.toList
   * res0: List[Int] = List(1, 2)
   * }}}
   */
  def unNoneTerminate[O2](implicit ev: O <:< Option[O2]): Stream[F,O2] =
    this.repeatPull { _.uncons.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) =>
        Pull.segment(hd.takeWhile(_.isDefined).map(_.get)).map(_.fold(_ => Some(tl), _ => None))
    }}

  /**
   * Zips the elements of the input stream with its indices, and returns the new stream.
   *
   * @example {{{
   * scala> Stream("The", "quick", "brown", "fox").zipWithIndex.toList
   * res0: List[(String,Long)] = List((The,0), (quick,1), (brown,2), (fox,3))
   * }}}
   */
  def zipWithIndex: Stream[F,(O,Long)] =
    this.scanSegments(0L) { (index, s) =>
      s.withSize.zipWith(Segment.from(index))((_,_)).mapResult {
        case Left(((_, len), remRight)) => len + index
        case Right((_, remLeft)) => sys.error("impossible")
      }
    }

  /**
   * Zips each element of this stream with the next element wrapped into `Some`.
   * The last element is zipped with `None`.
   *
   * @example {{{
   * scala> Stream("The", "quick", "brown", "fox").zipWithNext.toList
   * res0: List[(String,Option[String])] = List((The,Some(quick)), (quick,Some(brown)), (brown,Some(fox)), (fox,None))
   * }}}
   */
  def zipWithNext: Stream[F,(O,Option[O])] = {
    def go(last: O, s: Stream[F,O]): Pull[F,(O,Option[O]),Unit] =
      s.pull.uncons.flatMap {
        case None => Pull.output1((last, None))
        case Some((hd,tl)) =>
          Pull.segment(hd.mapAccumulate(last) {
            case (prev, next) => (next, (prev, Some(next)))
          }).flatMap { case (_, newLast) => go(newLast, tl)}
      }
    this.pull.uncons1.flatMap {
      case Some((hd,tl)) => go(hd,tl)
      case None => Pull.done
    }.stream
  }

  /**
   * Zips each element of this stream with the previous element wrapped into `Some`.
   * The first element is zipped with `None`.
   *
   * @example {{{
   * scala> Stream("The", "quick", "brown", "fox").zipWithPrevious.toList
   * res0: List[(Option[String],String)] = List((None,The), (Some(The),quick), (Some(quick),brown), (Some(brown),fox))
   * }}}
   */
  def zipWithPrevious: Stream[F,(Option[O],O)] =
    mapAccumulate[Option[O],(Option[O],O)](None) {
      case (prev, next) => (Some(next), (prev, next))
    }.map { case (_, prevNext) => prevNext }

  /**
   * Zips each element of this stream with its previous and next element wrapped into `Some`.
   * The first element is zipped with `None` as the previous element,
   * the last element is zipped with `None` as the next element.
   *
   * @example {{{
   * scala> Stream("The", "quick", "brown", "fox").zipWithPreviousAndNext.toList
   * res0: List[(Option[String],String,Option[String])] = List((None,The,Some(quick)), (Some(The),quick,Some(brown)), (Some(quick),brown,Some(fox)), (Some(brown),fox,None))
   * }}}
   */
  def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] =
    zipWithPrevious.zipWithNext.map {
      case ((prev, that), None) => (prev, that, None)
      case ((prev, that), Some((_, next))) => (prev, that, Some(next))
    }

  /**
   * Zips the input with a running total according to `S`, up to but not including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   *
   * @example {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan1]]
   */
  def zipWithScan[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] =
    this.mapAccumulate(z) { (s,o) => val s2 = f(s,o); (s2, (o,s)) }.map(_._2)

  /**
   * Zips the input with a running total according to `S`, including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   *
   * @example {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_ + _.length).toList
   * res0: List[(String, Int)] = List((uno,3), (dos,6), (tres,10), (cuatro,16))
   * }}}
   *
   * @see [[zipWithScan]]
   */
  def zipWithScan1[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] =
    this.mapAccumulate(z) { (s,o) => val s2 = f(s,o); (s2, (o,s2)) }.map(_._2)

  override def toString: String = "Stream(..)"
}

object Stream {
  private[fs2] def fromFree[F[_],O](free: Free[Algebra[F,O,?],Unit]): Stream[F,O] =
    new Stream(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],Unit]])

  /** Appends `s2` to the end of `s1`. Alias for `s1 ++ s2`. */
  def append[F[_],O](s1: Stream[F,O], s2: => Stream[F,O]): Stream[F,O] =
    fromFree(s1.get.flatMap { _ => s2.get })

  /** Creates a pure stream that emits the supplied values. To convert to an effectful stream, use [[covary]]. */
  def apply[O](os: O*): Stream[Pure,O] = emits(os)

  /**
   * Creates a single element stream that gets its value by evaluating the supplied effect. If the effect fails, a `Left`
   * is emitted. Otherwise, a `Right` is emitted.
   *
   * Use [[eval]] instead if a failure while evaluating the effect should fail the stream.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.attemptEval(IO(10)).runLog.unsafeRunSync
   * res0: Vector[Either[Throwable,Int]] = Vector(Right(10))
   * scala> Stream.attemptEval(IO(throw new RuntimeException)).runLog.unsafeRunSync
   * res1: Vector[Either[Throwable,Nothing]] = Vector(Left(java.lang.RuntimeException))
   * }}}
   */
  def attemptEval[F[_],O](fo: F[O]): Stream[F,Either[Throwable,O]] =
    fromFree(Pull.attemptEval(fo).flatMap(Pull.output1).get)

  /**
   * Creates a stream that depends on a resource allocated by an effect, ensuring the resource is
   * released regardless of how the stream is used.
   *
   * @param r resource to acquire at start of stream
   * @param use function which uses the acquired resource to generate a stream of effectful outputs
   * @param release function which returns an effect that releases the resource
   *
   * A typical use case for bracket is working with files or network sockets. The resource effect
   * opens a file and returns a reference to it. The `use` function reads bytes and transforms them
   * in to some stream of elements (e.g., bytes, strings, lines, etc.). The `release` action closes
   * the file.
   */
  def bracket[F[_],R,O](r: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,O] =
    fromFree(Algebra.acquire[F,O,R](r, release).flatMap { case (r, token) =>
      use(r).onComplete { fromFree(Algebra.release(token)) }.get
    })

  private[fs2] def bracketWithToken[F[_],R,O](r: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,(Algebra.Token,O)] =
    fromFree(Algebra.acquire[F,(Algebra.Token,O),R](r, release).flatMap { case (r, token) =>
      use(r).map(o => (token,o)).onComplete { fromFree(Algebra.release(token)) }.get
    })

  /**
   * Creates a pure stream that emits the elements of the supplied chunk.
   *
   * @example {{{
   * scala> Stream.chunk(Chunk(1,2,3)).toList
   * res0: List[Int] = List(1, 2, 3)
   * }}}
   */
  def chunk[O](os: Chunk[O]): Stream[Pure,O] = segment(os)

  /**
   * Creates an infinite pure stream that always returns the supplied value.
   *
   * Elements are emitted in finite segments with `segmentSize` number of elements.
   *
   * @example {{{
   * scala> Stream.constant(0).take(5).toList
   * res0: List[Int] = List(0, 0, 0, 0, 0)
   * }}}
   */
  def constant[O](o: O, segmentSize: Int = 256): Stream[Pure,O] =
    segment(Segment.constant(o).take(segmentSize).voidResult).repeat

  /**
   * Creates a singleton pure stream that emits the supplied value.
   *
   * @example {{{
   * scala> Stream.emit(0).toList
   * res0: List[Int] = List(0)
   * }}}
   */
  def emit[O](o: O): Stream[Pure,O] = fromFree(Algebra.output1[Pure,O](o))

  /**
   * Creates a pure stream that emits the supplied values.
   *
   * @example {{{
   * scala> Stream.emits(List(1, 2, 3)).toList
   * res0: List[Int] = List(1, 2, 3)
   * }}}
   */
  def emits[O](os: Seq[O]): Stream[Pure,O] = {
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromFree(Algebra.output[Pure,O](Chunk.seq(os)))
  }

  private[fs2] val empty_ = fromFree[Nothing,Nothing](Algebra.pure[Nothing,Nothing,Unit](())): Stream[Nothing,Nothing]

  /** Empty pure stream. */
  def empty: Stream[Pure,Nothing] = empty_

  /**
   * Creates a single element stream that gets its value by evaluating the supplied effect. If the effect fails,
   * the returned stream fails.
   *
   * Use [[attemptEval]] instead if a failure while evaluating the effect should be emitted as a value.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.eval(IO(10)).runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(10)
   * scala> Stream.eval(IO(throw new RuntimeException)).runLog.attempt.unsafeRunSync
   * res1: Either[Throwable,Vector[Nothing]] = Left(java.lang.RuntimeException)
   * }}}
   */
  def eval[F[_],O](fo: F[O]): Stream[F,O] = fromFree(Algebra.eval(fo).flatMap(Algebra.output1))

  /**
   * Creates a stream that evaluates the supplied `fa` for its effect, discarding the output value.
   * As a result, the returned stream emits no elements and hence has output type `Nothing`.
   *
   * Alias for `eval(fa).drain`.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.eval_(IO(println("Ran"))).runLog.unsafeRunSync
   * res0: Vector[Nothing] = Vector()
   * }}}
   */
  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] = eval(fa).drain

  /**
   * Creates a stream that, when run, fails with the supplied exception.
   *
   * @example {{{
   * scala> import scala.util.Try
   * scala> Try(Stream.fail(new RuntimeException).toList)
   * res0: Try[List[Nothing]] = Failure(java.lang.RuntimeException)
   * scala> import cats.effect.IO
   * scala> Stream.fail(new RuntimeException).covary[IO].run.attempt.unsafeRunSync
   * res0: Either[Throwable,Unit] = Left(java.lang.RuntimeException)
   * }}}
   */
  def fail[O](e: Throwable): Stream[Pure,O] = fromFree(Algebra.fail(e))

  /**
   * Lifts an effect that generates a stream in to a stream. Alias for `eval(f).flatMap(_)`.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.force(IO(Stream(1,2,3).covary[IO])).runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(1, 2, 3)
   * }}}
   */
  def force[F[_],A](f: F[Stream[F, A]]): Stream[F,A] =
    eval(f).flatMap(s => s)

  /**
   * Nondeterministically merges a stream of streams (`outer`) in to a single stream,
   * opening at most `maxOpen` streams at any point in time.
   *
   * The outer stream is evaluated and each resulting inner stream is run concurrently,
   * up to `maxOpen` stream. Once this limit is reached, evaluation of the outer stream
   * is paused until one or more inner streams finish evaluating.
   *
   * When the outer stream stops gracefully, all inner streams continue to run,
   * resulting in a stream that will stop when all inner streams finish
   * their evaluation.
   *
   * When the outer stream fails, evaluation of all inner streams is interrupted
   * and the resulting stream will fail with same failure.
   *
   * When any of the inner streams fail, then the outer stream and all other inner
   * streams are interrupted, resulting in stream that fails with the error of the
   * stream that caused initial failure.
   *
   * Finalizers on each inner stream are run at the end of the inner stream,
   * concurrently with other stream computations.
   *
   * Finalizers on the outer stream are run after all inner streams have been pulled
   * from the outer stream -- hence, finalizers on the outer stream will likely run
   * BEFORE the LAST finalizer on the last inner stream.
   *
   * Finalizers on the returned stream are run after the outer stream has finished
   * and all open inner streams have finished.
   *
   * @param maxOpen    Maximum number of open inner streams at any time. Must be > 0.
   * @param outer      Stream of streams to join.
   */
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] = {
    assert(maxOpen > 0, "maxOpen must be > 0, was: " + maxOpen)
    Stream.eval(async.signalOf(false)) flatMap { killSignal =>
    Stream.eval(async.semaphore(maxOpen)) flatMap { available =>
    Stream.eval(async.signalOf(1l)) flatMap { running => // starts with 1 because outer stream is running by default
    Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Segment[O,Unit]]]) flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
      val incrementRunning: F[Unit] = running.modify(_ + 1).as(())
      val decrementRunning: F[Unit] = running.modify(_ - 1).as(())

      // runs inner stream
      // each stream is forked.
      // terminates when killSignal is true
      // if fails will enq in queue failure
      def runInner(inner: Stream[F, O]): Stream[F, Nothing] = {
        Stream.eval_(
          available.decrement >> incrementRunning >>
          async.fork {
            inner.segments.attempt.
              flatMap { r => Stream.eval(outputQ.enqueue1(Some(r))) }.
              interruptWhen(killSignal). // must be AFTER enqueue to the the sync queue, otherwise the process may hang to enq last item while being interrupted
              run >> available.increment >> decrementRunning
          }
        )
      }

      // runs the outer stream, interrupts when kill == true, and then decrements the `running`
      def runOuter: F[Unit] = {
        (outer.interruptWhen(killSignal) flatMap runInner run).attempt flatMap {
          case Right(_) => decrementRunning
          case Left(err) => outputQ.enqueue1(Some(Left(err))) >> decrementRunning
        }
      }

      // monitors when the all streams (outer/inner) are terminated an then supplies None to output Queue
      def doneMonitor: F[Unit] = {
        running.discrete.dropWhile(_ > 0) take 1 flatMap { _ =>
          Stream.eval(outputQ.enqueue1(None))
        } run
      }

      Stream.eval_(async.start(runOuter)) ++
      Stream.eval_(async.start(doneMonitor)) ++
      outputQ.dequeue.unNoneTerminate.flatMap {
        case Left(e) => Stream.fail(e)
        case Right(s) => Stream.segment(s)
      } onFinalize { killSignal.set(true) >> (running.discrete.dropWhile(_ > 0) take 1 run) }
    }}}}
  }

  /** Like [[join]] but races all inner streams simultaneously. */
  def joinUnbounded[F[_],O](outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
    join(Int.MaxValue)(outer)

  /**
   * An infinite `Stream` that repeatedly applies a given function
   * to a start value. `start` is the first value emitted, followed
   * by `f(start)`, then `f(f(start))`, and so on.
   *
   * @example {{{
   * scala> Stream.iterate(0)(_ + 1).take(10).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
   * }}}
   */
  def iterate[A](start: A)(f: A => A): Stream[Pure,A] =
    emit(start) ++ iterate(f(start))(f)

  /**
   * Like [[iterate]], but takes an effectful function for producing
   * the next state. `start` is the first value emitted.
   *
   * @example {{{
   * scala> import cats.effect.IO
   * scala> Stream.iterateEval(0)(i => IO(i + 1)).take(10).runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
   * }}}
   */
  def iterateEval[F[_],A](start: A)(f: A => F[A]): Stream[F,A] =
    emit(start) ++ eval(f(start)).flatMap(iterateEval(_)(f))

  /**
   * Lazily produce the range `[start, stopExclusive)`. If you want to produce
   * the sequence in one chunk, instead of lazily, use
   * `emits(start until stopExclusive)`.
   *
   * @example {{{
   * scala> Stream.range(10, 20, 2).toList
   * res0: List[Int] = List(10, 12, 14, 16, 18)
   * }}}
   */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Stream[Pure,Int] =
    unfold(start){i =>
      if ((by > 0 && i < stopExclusive && start < stopExclusive) ||
          (by < 0 && i > stopExclusive && start > stopExclusive))
        Some((i, i + by))
      else None
    }

  /**
   * Lazily produce a sequence of nonoverlapping ranges, where each range
   * contains `size` integers, assuming the upper bound is exclusive.
   * Example: `ranges(0, 1000, 10)` results in the pairs
   * `(0, 10), (10, 20), (20, 30) ... (990, 1000)`
   *
   * Note: The last emitted range may be truncated at `stopExclusive`. For
   * instance, `ranges(0,5,4)` results in `(0,4), (4,5)`.
   *
   * @example {{{
   * scala> Stream.ranges(0, 20, 5).toList
   * res0: List[(Int,Int)] = List((0,5), (5,10), (10,15), (15,20))
   * }}}
   *
   * @throws IllegalArgumentException if `size` <= 0
   */
  def ranges(start: Int, stopExclusive: Int, size: Int): Stream[Pure,(Int,Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start){
      lower =>
        if (lower < stopExclusive)
          Some((lower -> ((lower+size) min stopExclusive), lower+size))
        else
          None
    }
  }

  /** Alias for `eval(fo).repeat`. */
  def repeatEval[F[_],O](fo: F[O]): Stream[F,O] = eval(fo).repeat

  /**
   * Creates a pure stream that emits the values of the supplied segment.
   *
   * @example {{{
   * scala> Stream.segment(Segment.from(0)).take(5).toList
   * res0: List[Long] = List(0, 1, 2, 3, 4)
   * }}}
   */
  def segment[O](s: Segment[O,Unit]): Stream[Pure,O] =
    fromFree(Algebra.output[Pure,O](s))

  /**
   * Returns a stream that evaluates the supplied by-name each time the stream is used,
   * allowing use of a mutable value in stream computations.
   *
   * Note: it's generally easier to reason about such computations using effectful
   * values. That is, allocate the mutable value in an effect and then use
   * `Stream.eval(fa).flatMap { a => ??? }`.
   *
   * @example {{{
   * scala> Stream.suspend {
   *      |   val digest = java.security.MessageDigest.getInstance("SHA-256")
   *      |   val bytes: Stream[Pure,Byte] = ???
   *      |   bytes.chunks.fold(digest) { (d,c) => d.update(c.toBytes.values); d }
   *      | }
   * }}}
   */
  def suspend[F[_],O](s: => Stream[F,O]): Stream[F,O] =
    fromFree(Algebra.suspend(s.get))

  /**
   * Creates a stream by successively applying `f` until a `None` is returned, emitting
   * each output `O` and using each output `S` as input to the next invocation of `f`.
   *
   * @example {{{
   * scala> Stream.unfold(0)(i => if (i < 5) Some(i -> (i+1)) else None).toList
   * res0: List[Int] = List(0, 1, 2, 3, 4)
   * }}}
   */
  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Stream[Pure,O] =
    segment(Segment.unfold(s)(f))

  /**
   * Like [[unfold]] but each invocation of `f` provides a chunk of output.
   *
   * @example {{{
   * scala> Stream.unfoldSegment(0)(i => if (i < 5) Some(Segment.seq(List.fill(i)(i)) -> (i+1)) else None).toList
   * res0: List[Int] = List(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
   * }}}
   */
  def unfoldSegment[S,O](s: S)(f: S => Option[(Segment[O,Unit],S)]): Stream[Pure,O] =
    unfold(s)(f).flatMap(segment)

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_],S,O](s: S)(f: S => F[Option[(O,S)]]): Stream[F,O] = {
    def go(s: S): Stream[F,O] =
      eval(f(s)).flatMap {
        case Some((o, s)) => emit(o) ++ go(s)
        case None => empty
      }
    suspend(go(s))
  }

  /** Alias for [[unfoldSegmentEval]] with slightly better type inference when `f` returns a `Chunk`. */
  def unfoldChunkEval[F[_],S,O](s: S)(f: S => F[Option[(Chunk[O],S)]])(implicit F: Functor[F]): Stream[F,O] =
    unfoldSegmentEval(s)(s => f(s).widen[Option[(Segment[O,Unit],S)]])

  /** Like [[unfoldSegment]], but takes an effectful function. */
  def unfoldSegmentEval[F[_],S,O](s: S)(f: S => F[Option[(Segment[O,Unit],S)]]): Stream[F,O] = {
    def go(s: S): Stream[F,O] =
      eval(f(s)).flatMap {
        case Some((o, s)) => segment(o) ++ go(s)
        case None => empty
      }
    suspend(go(s))
  }

  implicit def InvariantOps[F[_],O](s: Stream[F,O]): InvariantOps[F,O] = new InvariantOps(s.get)
  final class InvariantOps[F[_],O] private[Stream] (private val free: Free[Algebra[F,O,?],Unit]) extends AnyVal {
    private def self: Stream[F,O] = Stream.fromFree(free)

    def ++[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self, s2)

    def append[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self, s2)

    /**
     * Emits only elements that are distinct from their immediate predecessors,
     * using natural equality for comparison.
     * @example {{{
     * scala> import cats.implicits._
     * scala> Stream(1,1,2,2,2,3,3).changes.toList
     * res0: List[Int] = List(1, 2, 3)
     * }}}
     */
    def changes(implicit eq: Eq[O]): Stream[F,O] = self.filterWithPrevious(eq.neqv)

    /** Lifts this stream to the specified effect type. */
    def covary[F2[x]>:F[x]]: Stream[F2,O] = self.asInstanceOf[Stream[F2,O]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F2[x]>:F[x],O2>:O]: Stream[F2,O2] = self.asInstanceOf[Stream[F2,O2]]

    /** Debounce the stream with a minimum period of `d` between each element */
    def debounce(d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext, scheduler: Scheduler): Stream[F, O] = {
      def unconsLatest(s: Stream[F,O]): Pull[F,Nothing,Option[(O,Stream[F,O])]] =
        s.pull.uncons.flatMap {
          case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
            case (_, Some(last)) => Pull.pure(Some(last -> tl))
            case (_, None) => unconsLatest(tl)
          }
          case None => Pull.pure(None)
        }

      def go(o: O, s: Stream[F,O]): Pull[F,O,Unit] = {
        time.sleep[F](d).pull.unconsAsync.flatMap { l =>
          s.pull.unconsAsync.flatMap { r =>
            (l race r).pull.flatMap {
              case Left(_) =>
                Pull.output1(o) >> r.pull.flatMap {
                  case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
                    case (_, Some(last)) => go(last, tl)
                    case (_, None) => unconsLatest(tl).flatMap {
                      case Some((last, tl)) => go(last, tl)
                      case None => Pull.done
                    }
                  }
                  case None => Pull.done
                }
              case Right(r) => r match {
                case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
                  case (_, Some(last)) => go(last, tl)
                  case (_, None) => go(o, tl)
                }
                case None => Pull.output1(o)
              }
            }
          }
        }
      }
      unconsLatest(self).flatMap {
        case Some((last,tl)) => go(last, tl)
        case None => Pull.done
      }.stream
    }

    /**
     * Pass elements of `s` through both `f` and `g`, then combine the two resulting streams.
     * Implemented by enqueueing elements as they are seen by `f` onto a `Queue` used by the `g` branch.
     * USE EXTREME CARE WHEN USING THIS FUNCTION. Deadlocks are possible if `combine` pulls from the `g`
     * branch synchronously before the queue has been populated by the `f` branch.
     *
     * The `combine` function receives an `F[Int]` effect which evaluates to the current size of the
     * `g`-branch's queue.
     *
     * When possible, use one of the safe combinators like `[[observe]]`, which are built using this function,
     * in preference to using this function directly.
     */
    def diamond[B,C,D](f: Pipe[F,O,B])(qs: F[Queue[F,Option[Segment[O,Unit]]]], g: Pipe[F,O,C])(combine: Pipe2[F,B,C,D])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,D] = {
      Stream.eval(qs) flatMap { q =>
      Stream.eval(async.semaphore[F](1)) flatMap { enqueueNoneSemaphore =>
      Stream.eval(async.semaphore[F](1)) flatMap { dequeueNoneSemaphore =>
      combine(
        f {
          val enqueueNone: F[Unit] =
            enqueueNoneSemaphore.tryDecrement.flatMap { decremented =>
              if (decremented) q.enqueue1(None)
              else F.pure(())
            }
          self.repeatPull {
            _.uncons.flatMap {
              case Some((o, h)) =>
                Pull.eval(q.enqueue1(Some(o))) >> Pull.output(o).as(Some(h))
              case None =>
                Pull.eval(enqueueNone) >> Pull.pure(None)
            }
          }.onFinalize(enqueueNone)
        },
        {
          val drainQueue: Stream[F,Nothing] =
            Stream.eval(
              dequeueNoneSemaphore.tryDecrement).flatMap { dequeue =>
              if (dequeue) q.dequeue.unNoneTerminate.drain
              else Stream.empty
            }

          (q.dequeue.
            evalMap { c =>
              if (c.isEmpty) dequeueNoneSemaphore.tryDecrement.as(c)
              else F.pure(c)
            }.
            unNoneTerminate.
            flatMap { c => Stream.segment(c) }.
            through(g) ++ drainQueue
          ).onError { t => drainQueue ++ Stream.fail(t) }
        }
      )
    }}}}

    /** Like `[[merge]]`, but tags each output with the branch it came from. */
    def either[O2](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Either[O,O2]] =
      self.map(Left(_)) merge that.map(Right(_))

    /** Alias for `flatMap(o => Stream.eval(f(o)))`. */
    def evalMap[O2](f: O => F[O2]): Stream[F,O2] =
      self.flatMap(o => Stream.eval(f(o)))

    /** Like `[[Stream#scan]]`, but accepts a function returning an `F[_]`. */
    def evalScan[O2](z: O2)(f: (O2, O) => F[O2]): Stream[F,O2] = {
      def go(z: O2, s: Stream[F,O]): Pull[F,O2,Option[Stream[F,O2]]] =
        s.pull.uncons1.flatMap {
          case Some((hd,tl)) => Pull.eval(f(z,hd)).flatMap { o =>
            Pull.output1(o) >> go(o,tl)
          }
          case None => Pull.pure(None)
        }
      self.pull.uncons1.flatMap {
        case Some((hd,tl)) => Pull.eval(f(z,hd)).flatMap { o =>
          Pull.output(Chunk.seq(List(z,o))) >> go(o,tl)
        }
        case None => Pull.output1(z) >> Pull.pure(None)
      }.stream
    }

    /**
     * Creates a stream whose elements are generated by applying `f` to each output of
     * the source stream and concatenated all of the results.
     *
     * @example {{{
     * scala> Stream(1, 2, 3).flatMap { i => Stream.segment(Segment.seq(List.fill(i)(i))) }.toList
     * res0: List[Int] = List(1, 2, 2, 3, 3, 3)
     * }}}
     */
    def flatMap[O2](f: O => Stream[F,O2]): Stream[F,O2] =
      Stream.fromFree(Algebra.uncons(self.get[F,O]).flatMap {
        case None => Stream.empty.covaryAll[F,O2].get
        case Some((hd, tl)) =>
          val tl2 = Stream.fromFree(tl).flatMap(f)
          (hd.map(f).foldRightLazy(tl2)(Stream.append(_,_))).get
      })

    /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
    def >>[O2](s2: => Stream[F,O2]): Stream[F,O2] =
      flatMap { _ => s2 }

    /**
     * Folds this stream with the monoid for `O`.
     *
     * @example {{{
     * scala> import cats.implicits._
     * scala> Stream(1, 2, 3, 4, 5).foldMonoid.toList
     * res0: List[Int] = List(15)
     * }}}
    */
    def foldMonoid(implicit O: Monoid[O]): Stream[F,O] = self.fold(O.empty)(O.combine)

    /**
     * Determinsitically interleaves elements, starting on the left, terminating when the ends of both branches are reached naturally.
     *
     * @example {{{
     * scala> Stream(1, 2, 3).interleaveAll(Stream(4, 5, 6, 7)).toList
     * res0: List[Int] = List(1, 4, 2, 5, 3, 6, 7)
     * }}}
     */
    def interleaveAll(that: Stream[F,O]): Stream[F,O] =
      self.map(Some(_): Option[O]).zipAll(that.map(Some(_): Option[O]))(None, None).flatMap {
        case (o1Opt,o2Opt) => Stream(o1Opt.toSeq:_*) ++ Stream(o2Opt.toSeq:_*)
      }

    /**
     * Determinsitically interleaves elements, starting on the left, terminating when the end of either branch is reached naturally.
     *
     * @example {{{
     * scala> Stream(1, 2, 3).interleave(Stream(4, 5, 6, 7)).toList
     * res0: List[Int] = List(1, 4, 2, 5, 3, 6)
     * }}}
     */
    def interleave(that: Stream[F,O]): Stream[F,O] =
      this.zip(that).flatMap { case (o1,o2) => Stream(o1,o2) }

    /**
     * Let through the `s2` branch as long as the `s1` branch is `false`,
     * listening asynchronously for the left branch to become `true`.
     * This halts as soon as either branch halts.
     */
    def interruptWhen(haltWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      haltWhenTrue.noneTerminate.either(self.noneTerminate).
        takeWhile(_.fold(halt => halt.map(!_).getOrElse(false), o => o.isDefined)).
        collect { case Right(Some(i)) => i }

    /** Alias for `self.interruptWhen(haltWhenTrue.discrete)`. */
    def interruptWhen(haltWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      interruptWhen(haltWhenTrue.discrete)

    /** Alias for `Stream.join(maxOpen)(self)`. */
    def join[O2](maxOpen: Int)(implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] = {
      val _ = ev // Convince scalac that ev is used
      Stream.join(maxOpen)(self.asInstanceOf[Stream[F,Stream[F,O2]]])
    }


    /** Alias for `Stream.joinUnbounded(self)`. */
    def joinUnbounded[O2](implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] = {
      val _ = ev // Convince scalac that ev is used
      Stream.joinUnbounded(self.asInstanceOf[Stream[F,Stream[F,O2]]])
    }

    /**
     * Interleaves the two inputs nondeterministically. The output stream
     * halts after BOTH `s1` and `s2` terminate normally, or in the event
     * of an uncaught failure on either `s1` or `s2`. Has the property that
     * `merge(Stream.empty, s) == s` and `merge(fail(e), s)` will
     * eventually terminate with `fail(e)`, possibly after emitting some
     * elements of `s` first.
     */
    def merge[O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] = {
      def go(l: AsyncPull[F,Option[(Segment[O2,Unit],Stream[F,O2])]],
             r: AsyncPull[F,Option[(Segment[O2,Unit],Stream[F,O2])]]): Pull[F,O2,Unit] = {
        l.race(r).pull.flatMap {
          case Left(l) =>
            l match {
              case None => r.pull.flatMap {
                case None => Pull.done
                case Some((hd, tl)) => Pull.output(hd) >> tl.pull.echo
              }
              case Some((hd, tl)) => Pull.output(hd) >> tl.pull.unconsAsync.flatMap(go(_, r))
            }
          case Right(r) =>
            r match {
              case None => l.pull.flatMap {
                case None => Pull.done
                case Some((hd, tl)) => Pull.output(hd) >> tl.pull.echo
              }
              case Some((hd, tl)) => Pull.output(hd) >> tl.pull.unconsAsync.flatMap(go(l, _))
            }
        }
      }

      self.covaryOutput[O2].pull.unconsAsync.flatMap { s1 =>
        that.pull.unconsAsync.flatMap { s2 =>
          go(s1,s2)
        }
      }.stream
    }

    /**
     * Defined as `self.drain merge that`. Runs `self` and `that` concurrently, ignoring
     * any output of `that`.
     */
    def mergeDrainL[O2](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      self.drain.merge(that)

    /**
     * Defined as `self merge that.drain`. Runs `self` and `that` concurrently, ignoring
     * any output of `that`.
     */
    def mergeDrainR[O2](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      self.merge(that.drain)

    /** Like `merge`, but halts as soon as _either_ branch halts. */
    def mergeHaltBoth[O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      self.noneTerminate.merge(that.noneTerminate).unNoneTerminate

    /** Like `merge`, but halts as soon as the `s1` branch halts. */
    def mergeHaltL[O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      self.noneTerminate.merge(that.map(Some(_))).unNoneTerminate

    /** Like `merge`, but halts as soon as the `s2` branch halts. */
    def mergeHaltR[O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      that.mergeHaltL(self)

    /** Synchronously send values through `sink`. */
    def observe[O2>:O](sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      self.diamond(identity)(async.mutable.Queue.synchronousNoneTerminated, sink andThen (_.drain))(_.merge(_))

    /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
    def observeAsync[O2>:O](maxQueued: Int)(sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      self.diamond(identity)(async.boundedQueue(maxQueued), sink andThen (_.drain))(_.merge(_))

    /** Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
    def onComplete[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      (self onError (e => s2 ++ Stream.fail(e))) ++ s2

    /** If `this` terminates with `Stream.fail(e)`, invoke `h(e)`. */
    def onError[O2>:O](h: Throwable => Stream[F,O2]): Stream[F,O2] =
      Stream.fromFree(self.get[F,O2] onError { e => h(e).get })

    /**
     * Run the supplied effectful action at the end of this stream, regardless of how the stream terminates.
     */
    def onFinalize(f: F[Unit])(implicit F: Applicative[F]): Stream[F,O] =
      Stream.bracket(F.pure(()))(_ => self, _ => f)

    /** Like `interrupt` but resumes the stream when left branch goes to true. */
    def pauseWhen(pauseWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] = {
      def unpaused(
        controlFuture: AsyncPull[F, Option[(Segment[Boolean,Unit], Stream[F,Boolean])]],
        srcFuture: AsyncPull[F, Option[(Segment[O,Unit], Stream[F,O])]]
      ): Pull[F,O,Option[Nothing]] = {
        (controlFuture race srcFuture).pull.flatMap {
          case Left(None) => Pull.pure(None)
          case Right(None) => Pull.pure(None)
          case Left(Some((s, controlStream))) =>
            Pull.segment(s.fold(false)(_ || _)).flatMap { p =>
              if (p) paused(controlStream, srcFuture)
              else controlStream.pull.unconsAsync.flatMap(unpaused(_, srcFuture))
            }
          case Right(Some((c, srcStream))) =>
            Pull.output(c) >> srcStream.pull.unconsAsync.flatMap(unpaused(controlFuture, _))
        }
      }

      def paused(
        controlStream: Stream[F, Boolean],
        srcFuture: AsyncPull[F, Option[(Segment[O,Unit], Stream[F,O])]]
      ): Pull[F,O,Option[Nothing]] = {
        controlStream.pull.unconsChunk.flatMap {
          case None => Pull.pure(None)
          case Some((c, controlStream)) =>
            if (c(c.size - 1)) paused(controlStream, srcFuture)
            else controlStream.pull.unconsAsync.flatMap(unpaused(_, srcFuture))
        }
      }

      pauseWhenTrue.pull.unconsAsync.flatMap { controlFuture =>
        self.pull.unconsAsync.flatMap { srcFuture =>
          unpaused(controlFuture, srcFuture)
        }
      }.stream
    }

    /** Alias for `(pauseWhenTrue.discrete through2 this)(pipe2.pause)`. */
    def pauseWhen(pauseWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      pauseWhen(pauseWhenTrue.discrete)

    /**
     * Behaves like `identity`, but starts fetching the next segment before emitting the current,
     * enabling processing on either side of the `prefetch` to run in parallel.
     */
    def prefetch(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      self repeatPull { _.uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) => tl.pull.prefetch flatMap { p => Pull.output(hd) >> p }
      }}

    /** Gets a projection of this stream that allows converting it to a `Pull` in a number of ways. */
    def pull: Stream.ToPull[F,O] = new Stream.ToPull(self.free)

    def pull2[O2,O3](s2: Stream[F,O2])(using: (Stream.ToPull[F,O], Stream.ToPull[F,O2]) => Pull[F,O3,Any]): Stream[F,O3] =
      using(pull, s2.pull).stream

    /** Reduces this stream with the Semigroup for `O`. */
    def reduceSemigroup(implicit S: Semigroup[O]): Stream[F, O] =
      self.reduce(S.combine(_, _))

    def repeatPull[O2](using: Stream.ToPull[F,O] => Pull[F,O2,Option[Stream[F,O]]]): Stream[F,O2] =
      Pull.loop(using.andThen(_.map(_.map(_.pull))))(self.pull).stream

    def run(implicit F: Sync[F]): F[Unit] =
      runFold(())((u, _) => u)

    def runFold[B](init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
      Algebra.runFold(self.get, init)(f)

    def runFoldMonoid(implicit F: Sync[F], O: Monoid[O]): F[O] =
      runFold(O.empty)(O.combine)

    def runFoldSemigroup(implicit F: Sync[F], O: Semigroup[O]): F[Option[O]] =
      runFold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    def runLog(implicit F: Sync[F]): F[Vector[O]] = {
      import scala.collection.immutable.VectorBuilder
      F.suspend(F.map(runFold(new VectorBuilder[O])(_ += _))(_.result))
    }

    def runLast(implicit F: Sync[F]): F[Option[O]] =
      self.runFold(Option.empty[O])((_, a) => Some(a))

    def scanSegments[S,O2](init: S)(f: (S, Segment[O,Unit]) => Segment[O2,S]): Stream[F,O2] =
      scanSegmentsOpt(init)(s => Some(seg => f(s,seg)))

    def scanSegmentsOpt[S,O2](init: S)(f: S => Option[Segment[O,Unit] => Segment[O2,S]]): Stream[F,O2] =
      self.pull.scanSegmentsOpt(init)(f).stream

    /** Transform this stream using the given `Pipe`. */
    def through[O2](f: Pipe[F,O,O2]): Stream[F,O2] = f(self)

    /** Transform this stream using the given pure `Pipe`. */
    def throughPure[O2](f: Pipe[Pure,O,O2]): Stream[F,O2] = f(self)

    /** Transform this stream using the given `Pipe2`. */
    def through2[O2,O3](s2: Stream[F,O2])(f: Pipe2[F,O,O2,O3]): Stream[F,O3] =
      f(self,s2)

    /** Transform this stream using the given pure `Pipe2`. */
    def through2Pure[O2,O3](s2: Stream[F,O2])(f: Pipe2[Pure,O,O2,O3]): Stream[F,O3] =
      f(self,s2)

    /** Applies the given sink to this stream and drains the output. */
    def to(f: Sink[F,O]): Stream[F,Unit] = f(self)

    def translate[G[_]](u: F ~> G)(implicit G: Effect[G]): Stream[G,O] =
      translate_(u, Some(G))

    def translateSync[G[_]](u: F ~> G): Stream[G,O] =
      translate_(u, None)

    private def translate_[G[_]](u: F ~> G, G: Option[Effect[G]]): Stream[G,O] =
      Stream.fromFree[G,O](Algebra.translate[F,G,O,Unit](self.get, u, G))

    private type ZipWithCont[G[_],I,O2,R] = Either[(Segment[I,Unit], Stream[G,I]), Stream[G,I]] => Pull[G,O2,Option[R]]

    private def zipWith_[O2,O3](that: Stream[F,O2])(k1: ZipWithCont[F,O,O3,Nothing], k2: ZipWithCont[F,O2,O3,Nothing])(f: (O, O2) => O3): Stream[F,O3] = {
      def go(t1: (Segment[O,Unit], Stream[F,O]), t2: (Segment[O2,Unit], Stream[F,O2])): Pull[F,O3,Option[Nothing]] =
        (t1, t2) match {
          case ((hd1, tl1), (hd2, tl2)) => Pull.segment(hd1.zipWith(hd2)(f)).flatMap {
            case Left(((),extra2)) =>
              tl1.pull.uncons.flatMap {
                case None => k2(Left((extra2, tl2)))
                case Some(tl1) => go(tl1, (extra2, tl2))
              }
            case Right(((),extra1)) =>
              tl2.pull.uncons.flatMap {
                case None => k1(Left((extra1, tl1)))
                case Some(tl2) => go((extra1, tl1), tl2)
              }
          }
        }
      self.pull.uncons.flatMap {
        case Some(s1) => that.pull.uncons.flatMap {
          case Some(s2) => go(s1, s2)
          case None => k1(Left(s1))
        }
        case None => k2(Right(that))
      }.stream
    }

    /**
     * Determinsitically zips elements, terminating when the ends of both branches
     * are reached naturally, padding the left branch with `pad1` and padding the right branch
     * with `pad2` as necessary.
     *
     *
     * @example {{{
     * scala> Stream(1,2,3).zipAll(Stream(4,5,6,7))(0,0).toList
     * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6), (0,7))
     * }}}
     */
    def zipAll[O2](that: Stream[F,O2])(pad1: O, pad2: O2): Stream[F,(O,O2)] =
      zipAllWith(that)(pad1,pad2)(Tuple2.apply)

    /**
     * Determinsitically zips elements with the specified function, terminating
     * when the ends of both branches are reached naturally, padding the left
     * branch with `pad1` and padding the right branch with `pad2` as necessary.
     *
     * @example {{{
     * scala> Stream(1,2,3).zipAllWith(Stream(4,5,6,7))(0, 0)(_ + _).toList
     * res0: List[Int] = List(5, 7, 9, 7)
     * }}}
     */
    def zipAllWith[O2,O3](that: Stream[F,O2])(pad1: O, pad2: O2)(f: (O, O2) => O3): Stream[F,O3] = {
      def cont1(z: Either[(Segment[O,Unit], Stream[F,O]), Stream[F,O]]): Pull[F,O3,Option[Nothing]] = {
        def contLeft(s: Stream[F,O]): Pull[F,O3,Option[Nothing]] = s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd,tl)) => Pull.output(hd.map(o => f(o,pad2))) >> contLeft(tl)
        }
        z match {
          case Left((hd,tl)) => Pull.output(hd.map(o => f(o,pad2))) >> contLeft(tl)
          case Right(h) => contLeft(h)
        }
      }
      def cont2(z: Either[(Segment[O2,Unit], Stream[F,O2]), Stream[F,O2]]): Pull[F,O3,Option[Nothing]] = {
        def contRight(s: Stream[F,O2]): Pull[F,O3,Option[Nothing]] = s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd,tl)) => Pull.output(hd.map(o2 => f(pad1,o2))) >> contRight(tl)
        }
        z match {
          case Left((hd,tl)) => Pull.output(hd.map(o2 => f(pad1,o2))) >> contRight(tl)
          case Right(h) => contRight(h)
        }
      }
      zipWith_[O2,O3](that)(cont1, cont2)(f)
    }

    /**
     * Determinsitically zips elements, terminating when the end of either branch is reached naturally.
     *
     * @example {{{
     * scala> Stream(1, 2, 3).zip(Stream(4, 5, 6, 7)).toList
     * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6))
     * }}}
     */
    def zip[O2](that: Stream[F,O2]): Stream[F,(O,O2)] =
      zipWith(that)(Tuple2.apply)

    /**
     * Determinsitically zips elements using the specified function,
     * terminating when the end of either branch is reached naturally.
     *
     * @example {{{
     * scala> Stream(1, 2, 3).zipWith(Stream(4, 5, 6, 7))(_ + _).toList
     * res0: List[Int] = List(5, 7, 9)
     * }}}
     */
    def zipWith[O2,O3](that: Stream[F,O2])(f: (O,O2) => O3): Stream[F,O3] =
      zipWith_[O2,O3](that)(sh => Pull.pure(None), h => Pull.pure(None))(f)
  }

  implicit def EmptyOps(s: Stream[Pure,Nothing]): EmptyOps = new EmptyOps(s.get[Pure,Nothing])
  final class EmptyOps private[Stream] (private val free: Free[Algebra[Pure,Nothing,?],Unit]) extends AnyVal {
    private def self: Stream[Pure,Nothing] = Stream.fromFree[Pure,Nothing](free)

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F,Nothing] = self.asInstanceOf[Stream[F,Nothing]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F[_],O]: Stream[F,O] = self.asInstanceOf[Stream[F,O]]
  }

  implicit def PureOps[O](s: Stream[Pure,O]): PureOps[O] = new PureOps(s.get[Pure,O])
  final class PureOps[O] private[Stream] (private val free: Free[Algebra[Pure,O,?],Unit]) extends AnyVal {
    private def self: Stream[Pure,O] = Stream.fromFree[Pure,O](free)

    def ++[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(covary[F], s2)

    def append[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self.covary[F], s2)

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F,O] = self.asInstanceOf[Stream[F,O]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F[_],O2>:O]: Stream[F,O2] = self.asInstanceOf[Stream[F,O2]]

    def either[F[_],O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Either[O,O2]] =
      covary[F].either(s2)

    def evalMap[F[_],O2](f: O => F[O2]): Stream[F,O2] = covary[F].evalMap(f)

    /** Like `[[Stream#scan]]`, but accepts a function returning an `F[_]`. */
    def evalScan[F[_],O2](z: O2)(f: (O2, O) => F[O2]): Stream[F,O2] = covary[F].evalScan(z)(f)

    def flatMap[F[_],O2](f: O => Stream[F,O2]): Stream[F,O2] =
      covary[F].flatMap(f)

    /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
    def >>[F[_],O2](s2: => Stream[F,O2]): Stream[F,O2] =
      flatMap { _ => s2 }

    def interleave[F[_],O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      covaryAll[F,O2].interleave(s2)

    def interleaveAll[F[_],O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      covaryAll[F,O2].interleaveAll(s2)

    /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
    def interruptWhen[F[_]](haltWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].interruptWhen(haltWhenTrue)

    /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
    def interruptWhen[F[_]](haltWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].interruptWhen(haltWhenTrue)

    /** Alias for `Stream.join(maxOpen)(self)`. */
    def join[F[_],O2](maxOpen: Int)(implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] = {
      val _ = ev // Convince scalac that ev is used
      Stream.join(maxOpen)(self.asInstanceOf[Stream[F,Stream[F,O2]]])
    }

    /** Alias for `Stream.joinUnbounded(self)`. */
    def joinUnbounded[F[_],O2](implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] = {
      val _ = ev // Convince scalac that ev is used
      Stream.joinUnbounded(self.asInstanceOf[Stream[F,Stream[F,O2]]])
    }

    def merge[F[_],O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].merge(that)

    def mergeDrainL[F[_],O2](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].mergeDrainL(that)

    def mergeDrainR[F[_],O2](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].mergeDrainR(that)

    def mergeHaltBoth[F[_],O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].mergeHaltBoth(that)

    def mergeHaltL[F[_],O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].mergeHaltL(that)

    def mergeHaltR[F[_],O2>:O](that: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].mergeHaltR(that)

    def observe[F[_],O2>:O](sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].observe(sink)

    def observeAsync[F[_],O2>:O](maxQueued: Int)(sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].observeAsync(maxQueued)(sink)

    def onComplete[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      covary[F].onComplete(s2)

    /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
    def onError[F[_],O2>:O](h: Throwable => Stream[F,O2]): Stream[F,O2] =
      covary[F].onError(h)

    def onFinalize[F[_]](f: F[Unit])(implicit F: Applicative[F]): Stream[F,O] =
      covary[F].onFinalize(f)

    def pauseWhen[F[_]](pauseWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].pauseWhen(pauseWhenTrue)

    def pauseWhen[F[_]](pauseWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].pauseWhen(pauseWhenTrue)

    def toList: List[O] = covary[IO].runFold(List.empty[O])((b, a) => a :: b).unsafeRunSync.reverse

    def toVector: Vector[O] = covary[IO].runLog.unsafeRunSync

    def zipAll[F[_],O2](that: Stream[F,O2])(pad1: O, pad2: O2): Stream[F,(O,O2)] =
      covary[F].zipAll(that)(pad1,pad2)

    def zipAllWith[F[_],O2,O3](that: Stream[F,O2])(pad1: O, pad2: O2)(f: (O, O2) => O3): Stream[F,O3] =
      covary[F].zipAllWith(that)(pad1,pad2)(f)

    def zip[F[_],O2](s2: Stream[F,O2]): Stream[F,(O,O2)] =
      covary[F].zip(s2)

    def zipWith[F[_],O2,O3](s2: Stream[F,O2])(f: (O,O2) => O3): Stream[F, O3] =
      covary[F].zipWith(s2)(f)
  }

  final class ToPull[F[_],O] private[Stream] (private val free: Free[Algebra[Nothing,Nothing,?],Unit]) extends AnyVal {

    private def self: Stream[F,O] = Stream.fromFree(free.asInstanceOf[Free[Algebra[F,O,?],Unit]])

    /**
     * Waits for a segment of elements to be available in the source stream.
     * The segment of elements along with a new stream are provided as the resource of the returned pull.
     * The new stream can be used for subsequent operations, like awaiting again.
     * A `None` is returned as the resource of the pull upon reaching the end of the stream.
     */
    def uncons: Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] =
      Pull.fromFree(Algebra.uncons(self.get)).map { _.map { case (hd, tl) => (hd, Stream.fromFree(tl)) } }

    /** Like [[uncons]] but waits for a chunk instead of an entire segment. */
    def unconsChunk: Pull[F,Nothing,Option[(Chunk[O],Stream[F,O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd,tl)) => hd.unconsChunk match {
          case Left(()) => tl.pull.unconsChunk
          case Right((c,tl2)) => Pull.pure(Some((c, tl.cons(tl2))))
        }
      }

    /** Like [[uncons]] but waits for a single element instead of an entire segment. */
    def uncons1: Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      uncons flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) => hd.uncons1 match {
          case Left(_) => tl.pull.uncons1
          case Right((hd,tl2)) => Pull.pure(Some(hd -> tl.cons(tl2)))
        }
      }

    def unconsAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Segment[O,Unit], Stream[F,O])]]] =
      Pull.fromFree(Algebra.unconsAsync(self.get)).map(_.map(_.map { case (hd, tl) => (hd, Stream.fromFree(tl)) }))

    /**
     * Like [[uncons]], but returns a segment of no more than `n` elements.
     *
     * The returned segment has a result tuple consisting of the remaining limit
     * (`n` minus the segment size, or 0, whichever is larger) and the remainder
     * of the source stream.
     *
     * `Pull.pure(None)` is returned if the end of the source stream is reached.
     */
    def unconsLimit(n: Long): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
      require(n > 0)
      uncons.flatMap {
        case Some((hd,tl)) =>
          hd.splitAt(n) match {
            case Left((_,segments,rem)) => Pull.pure(Some(Segment.catenated(segments) -> tl))
            case Right((segments,tl2)) => Pull.pure(Some(Segment.catenated(segments) -> tl.cons(tl2)))
          }
        case None => Pull.pure(None)
      }
    }

    def unconsN(n: Long, allowFewer: Boolean = false): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
      def go(acc: Catenable[Segment[O,Unit]], n: Long, s: Stream[F,O]): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
        s.pull.uncons.flatMap {
          case None =>
            if (allowFewer && acc.nonEmpty) Pull.pure(Some((Segment.catenated(acc), Stream.empty)))
            else Pull.pure(None)
          case Some((hd,tl)) =>
            hd.splitAt(n) match {
              case Left((_,segments,rem)) =>
                if (rem > 0) go(acc ++ segments, rem, tl)
                else Pull.pure(Some(Segment.catenated(acc ++ segments) -> tl))
              case Right((segments,tl2)) =>
                Pull.pure(Some(Segment.catenated(acc ++ segments) -> tl.cons(tl2)))
            }
        }
      }
      if (n <= 0) Pull.pure(Some((Segment.empty, self)))
      else go(Catenable.empty, n, self)
    }

    /** Drops the first `n` elements of this `Stream`, and returns the new `Stream`. */
    def drop(n: Long): Pull[F,Nothing,Option[Stream[F,O]]] =
      if (n <= 0) Pull.pure(Some(self))
      else uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd,tl)) =>
          hd.drop(n) match {
            case Left((_,rem)) =>
              if (rem > 0) tl.pull.drop(rem)
              else Pull.pure(Some(tl))
            case Right(tl2) =>
              Pull.pure(Some(tl.cons(tl2)))
          }
      }

    /** Like [[dropWhile]], but drops the first value which tests false. */
    def dropThrough(p: O => Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      dropWhile_(p, true)

    /**
     * Drops elements of the this stream until the predicate `p` fails, and returns the new stream.
     * If defined, the first element of the returned stream will fail `p`.
     */
    def dropWhile(p: O => Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      dropWhile_(p, false)

    def dropWhile_(p: O => Boolean, dropFailure: Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.dropWhile(p, dropFailure) match {
            case Left(_) => tl.pull.dropWhile_(p, dropFailure)
            case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
          }
      }

    /** Writes all inputs to the output of the returned `Pull`. */
    def echo: Pull[F,O,Unit] = Pull.fromFree(self.get)

    /** Reads a single element from the input and emits it to the output. Returns the new `Handle`. */
    def echo1: Pull[F,O,Option[Stream[F,O]]] =
      uncons1.flatMap { case None => Pull.pure(None); case Some((hd, tl)) => Pull.output1(hd).as(Some(tl)) }

    /** Reads the next available segment from the input and emits it to the output. Returns the new `Handle`. */
    def echoSegment: Pull[F,O,Option[Stream[F,O]]] =
      uncons.flatMap { case None => Pull.pure(None); case Some((hd, tl)) => Pull.output(hd).as(Some(tl)) }

    /** Like `[[unconsN]]`, but leaves the buffered input unconsumed. */
    def fetchN(n: Int): Pull[F,Nothing,Option[Stream[F,O]]] =
      unconsN(n).map { _.map { case (hd, tl) => tl.cons(hd) } }

    /** Awaits the next available element where the predicate returns true. */
    def find(f: O => Boolean): Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      unconsChunk.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.indexWhere(f) match {
            case None => tl.pull.find(f)
            case Some(idx) if idx + 1 < hd.size => Pull.pure(Some((hd(idx), hd.drop(idx + 1).fold(_ => tl, hd => tl.cons(hd)))))
            case Some(idx) => Pull.pure(Some((hd(idx), tl)))
          }
      }

    /**
     * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
     * result to the output of the supplied `Pull` when the stream has no more values.
     */
    def fold[O2](z: O2)(f: (O2, O) => O2): Pull[F,Nothing,O2] =
      uncons.flatMap {
        case None => Pull.pure(z)
        case Some((hd,tl)) => Pull.segment(hd.fold(z)(f)).flatMap { z => tl.pull.fold(z)(f) }
      }

    /**
     * Folds all inputs using the supplied binary operator, and writes the final result to the output of
     * the supplied `Pull` when the stream has no more values.
     */
    def fold1[O2 >: O](f: (O2, O2) => O2): Pull[F,Nothing,Option[O2]] =
      uncons1.flatMap {
        case None => Pull.pure(None)
        case Some((hd,tl)) => tl.pull.fold(hd: O2)(f).map(Some(_))
      }

    /** Writes a single `true` value if all input matches the predicate, `false` otherwise. */
    def forall(p: O => Boolean): Pull[F,Nothing,Boolean] = {
      uncons.flatMap {
        case None => Pull.pure(true)
        case Some((hd,tl)) =>
          Pull.segment(hd.takeWhile(p).drain).flatMap {
            case Left(()) => tl.pull.forall(p)
            case Right(_) => Pull.pure(false)
          }
      }
    }

    /** Returns the last element of the input, if non-empty. */
    def last: Pull[F,Nothing,Option[O]] = {
      def go(prev: Option[O], s: Stream[F,O]): Pull[F,Nothing,Option[O]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(prev)
          case Some((hd,tl)) => Pull.segment(hd.fold(prev)((_,o) => Some(o))).flatMap(go(_,tl))
        }
      go(None, self)
    }

    /** Like [[uncons]] but does not consume the segment (i.e., the segment is pushed back). */
    def peek: Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] =
      uncons.flatMap { case None => Pull.pure(None); case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons(hd)))) }

    /** Like [[uncons1]] but does not consume the element (i.e., the element is pushed back). */
    def peek1: Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      uncons1.flatMap { case None => Pull.pure(None); case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons1(hd)))) }

    /**
     * Like [[uncons]], but runs the `uncons` asynchronously. A `flatMap` into
     * inner `Pull` logically blocks until this await completes.
     */
    def prefetch(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,Pull[F,Nothing,Option[Stream[F,O]]]] =
      unconsAsync.map { _.pull.map { _.map { case (hd, h) => h cons hd } } }

    def scanSegments[S,O2](init: S)(f: (S, Segment[O,Unit]) => Segment[O2,S]): Pull[F,O2,S] =
      scanSegmentsOpt(init)(s => Some(seg => f(s,seg)))

    def scanSegmentsOpt[S,O2](init: S)(f: S => Option[Segment[O,Unit] => Segment[O2,S]]): Pull[F,O2,S] = {
      def go(acc: S, s: Stream[F,O]): Pull[F,O2,S] =
        f(acc) match {
          case None => Pull.pure(acc)
          case Some(g) =>
            s.pull.uncons.flatMap {
              case Some((hd,tl)) =>
                Pull.segment(g(hd)).flatMap { acc => go(acc,tl) }
              case None =>
                Pull.pure(acc)
            }
        }
      go(init, self)
    }

    /** Emits the first `n` elements of the input. */
    def take(n: Long): Pull[F,O,Option[Stream[F,O]]] =
      if (n <= 0) Pull.pure(None)
      else uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd,tl)) =>
          Pull.segment(hd.take(n)).flatMap {
            case Left((_,rem)) =>
              if (rem > 0) tl.pull.take(rem) else Pull.pure(None)
            case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
          }
      }

    /** Emits the last `n` elements of the input. */
    def takeRight(n: Long): Pull[F,Nothing,Chunk[O]]  = {
      def go(acc: Vector[O], s: Stream[F,O]): Pull[F,Nothing,Chunk[O]] = {
        s.pull.unconsN(n, true).flatMap {
          case None => Pull.pure(Chunk.vector(acc))
          case Some((hd, tl)) =>
            val vector = hd.toVector
            go(acc.drop(vector.length) ++ vector, tl)
        }
      }
      if (n <= 0) Pull.pure(Chunk.empty)
      else go(Vector.empty, self)
    }

    /** Like [[takeWhile]], but emits the first value which tests false. */
    def takeThrough(p: O => Boolean): Pull[F,O,Option[Stream[F,O]]] = takeWhile_(p, true)

    /**
     * Emits the elements of the stream until the predicate `p` fails,
     * and returns the remaining `Stream`. If non-empty, the returned stream will have
     * a first element `i` for which `p(i)` is `false`.
     */
    def takeWhile(p: O => Boolean): Pull[F,O,Option[Stream[F,O]]] = takeWhile_(p, false)

    def takeWhile_(p: O => Boolean, takeFailure: Boolean): Pull[F,O,Option[Stream[F,O]]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          Pull.segment(hd.takeWhile(p, takeFailure)).flatMap {
            case Left(_) => tl.pull.takeWhile_(p, takeFailure)
            case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
          }
      }
  }

  /** Provides operations on effectful pipes for syntactic convenience. */
  implicit class PipeOps[F[_],I,O](private val self: Pipe[F,I,O]) extends AnyVal {

    /** Transforms the left input of the given `Pipe2` using a `Pipe`. */
    def attachL[I1,O2](p: Pipe2[F,O,I1,O2]): Pipe2[F,I,I1,O2] =
      (l, r) => p(self(l), r)

    /** Transforms the right input of the given `Pipe2` using a `Pipe`. */
    def attachR[I0,O2](p: Pipe2[F,I0,O,O2]): Pipe2[F,I0,I,O2] =
      (l, r) => p(l, self(r))
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit final class PurePipeOps[I,O](private val self: Pipe[Pure,I,O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe[F,I,O] = self.asInstanceOf[Pipe[F,I,O]]
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit final class PurePipe2Ops[I,I2,O](private val self: Pipe2[Pure,I,I2,O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe2[F,I,I2,O] = self.asInstanceOf[Pipe2[F,I,I2,O]]
  }

  implicit def covaryPure[F[_],O,O2>:O](s: Stream[Pure,O]): Stream[F,O2] = s.covaryAll[F,O2]

  implicit def covaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] = p.covary[F]

  implicit def covaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] = p.covary[F]

  // Note: non-implicit so that cats syntax doesn't override FS2 syntax
  def syncInstance[F[_]]: Sync[Stream[F,?]] = new Sync[Stream[F,?]] {
    def pure[A](a: A) = Stream(a)
    def handleErrorWith[A](s: Stream[F,A])(h: Throwable => Stream[F,A]) = s.onError(h)
    def raiseError[A](t: Throwable) = Stream.fail(t)
    def flatMap[A,B](s: Stream[F,A])(f: A => Stream[F,B]) = s.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => Stream[F,Either[A,B]]) = f(a).flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => Stream(b)
    }
    def suspend[R](s: => Stream[F,R]) = Stream.suspend(s)
  }

  implicit def monoidInstance[F[_],O]: Monoid[Stream[F,O]] = new Monoid[Stream[F,O]] {
    def empty = Stream.empty
    def combine(x: Stream[F,O], y: Stream[F,O]) = x ++ y
  }
}
