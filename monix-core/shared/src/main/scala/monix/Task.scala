/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix

import monix.Task.{Callback, SafeCallback}
import monix.execution.RunLoop.FrameId
import monix.execution._
import monix.execution.cancelables._
import org.sincron.atomic.Atomic

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{CancellationException, Future, Promise, TimeoutException}
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


/** `Task` represents a specification for an asynchronous computation,
  * which when executed will produce an `T` as a result, along with
  * possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation, as `Task` does not execute
  * anything when working with its builders or operators and it does
  * not submit any work into any thread-pool, the execution eventually
  * taking place after `runAsync` is called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing
  * `runAsync`.
  */
sealed abstract class Task[+T] { self =>
  /** Characteristic function for our [[Task]]. Never use this directly.
    *
    * @param isActive is a
    *        [[monix.execution.cancelables.MultiAssignmentCancelable MultiAssignmentCancelable]]
    *        that can either be used to check if the task is canceled
    *        or can be assigned to something that can eventually
    *        cancel the running computation
    *
    * @param frameId represents the current stack depth
    *
    * @param callback is the pair of `onSuccess` and `onError` methods that will
    *        be called when the execution completes

    * @param s is the [[monix.execution.Scheduler Scheduler]]
    *        under that the `Task` will use to fork threads, schedule
    *        with delay and to report errors
    *
    * @return a [[monix.execution.Cancelable Cancelable]] that can be used to
    *         cancel the running computation
    */
  protected def unsafeRun(
    isActive: MultiAssignmentCancelable,
    frameId: FrameId,
    callback: Callback[T])
    (implicit s: Scheduler): Unit

  /** Triggers the asynchronous execution.
    *
    * @param cb is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(cb: Callback[T])(implicit s: Scheduler): Cancelable = {
    val isActive = MultiAssignmentCancelable()
    val safe = SafeCallback(cb)
    // Schedule stack frame to run, prevents stack-overflows.
    RunLoop.start(frameId => self.unsafeRun(isActive, frameId, safe)(s))(s)
    isActive
  }

  /** Triggers the asynchronous execution.
    *
    * @param f is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(f: Try[T] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[T] {
      def onSuccess(value: T): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })

  /** Triggers the asynchronous execution.
    *
    * @return a [[monix.execution.CancelableFuture CancelableFuture]]
    *         that can be used to extract the result or to cancel
    *         a running task.
    */
  def runAsync(implicit s: Scheduler): CancelableFuture[T] = {
    val p = Promise[T]()
    val cancelable = runAsync(new Callback[T] {
      def onSuccess(value: T): Unit = p.trySuccess(value)
      def onError(ex: Throwable): Unit = p.tryFailure(ex)
    })

    val withTrigger = Cancelable {
      try cancelable.cancel() finally
        p.tryFailure(new CancellationException("CancelableFuture.cancel"))
    }

    CancelableFuture(p.future, withTrigger)
  }

  /** Returns a new Task that applies the mapping function to
    * the element emitted by the source.
    */
  def map[U](f: T => U): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          self.unsafeRun(active, frameId,
            new Callback[T] {
              def onError(ex: Throwable): Unit =
                cb.onError(ex)

              def onSuccess(value: T): Unit = {
                var streamError = true
                try {
                  val u = f(value)
                  streamError = false
                  cb.onSuccess(u)
                } catch {
                  case NonFatal(ex) if streamError =>
                    cb.onError(ex)
                }
              }
            }))
      }
    }


  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  def flatten[U](implicit ev: T <:< Task[U]): Task[U] =
    flatMap(t => t)

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  def flatMap[U](f: T => Task[U]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          self.unsafeRun(active, frameId, new Callback[T] {
            def onError(ex: Throwable): Unit =
              cb.onError(ex)

            def onSuccess(value: T): Unit = {
              var streamError = true
              try {
                val taskU = f(value)
                streamError = false
                RunLoop.stepInterruptibly(active, frameId)(frameId =>
                  taskU.unsafeRun(active, frameId, cb))
              }
              catch {
                case NonFatal(ex) if streamError =>
                  cb.onError(ex)
              }
            }
          }))
      }
    }

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    */
  def delayExecution(timespan: FiniteDuration): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        active := s.scheduleOnce(timespan.length, timespan.unit,
          new Runnable {
            def run(): Unit = {
              // At this point it's OK to restart the runLoop.
              RunLoop.startNow(frameId => self.unsafeRun(active, frameId, cb))
            }
          })
      }
    }

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    */
  def delayResult(timespan: FiniteDuration): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          self.unsafeRun(active, frameId,
            new Callback[T] {
              def onSuccess(value: T): Unit =
                active := s.scheduleOnce(timespan.length, timespan.unit,
                  new Runnable {
                    override def run(): Unit = {
                      cb.onSuccess(value)
                    }
                  })

              def onError(ex: Throwable): Unit =
                cb.onError(ex)
            }))
      }
    }

  /** Returns a failed projection of this task.
    *
    * The failed projection is a future holding a value of type
    * `Throwable`, emitting a value which is the throwable of the
    * original task in case the original task fails, otherwise if the
    * source succeeds, then it fails with a `NoSuchElementException`.
    */
  def failed: Task[Throwable] =
    new Task[Throwable] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[Throwable])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          // RunLoop step, prevents stack-overflows
          self.unsafeRun(active, frameId, new Callback[T] {
            def onError(ex: Throwable): Unit =
              cb.onSuccess(ex)
            def onSuccess(value: T): Unit =
              cb.onError(new NoSuchElementException("Task.failed"))
          }))
      }
    }

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    */
  def onErrorRecover[U >: T](pf: PartialFunction[Throwable, U]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          self.unsafeRun(active, frameId, new Callback[T] {
            def onSuccess(v: T): Unit =
              cb.onSuccess(v)

            def onError(ex: Throwable): Unit = {
              var streamError = true
              try {
                if (pf.isDefinedAt(ex)) {
                  val r = pf(ex)
                  streamError = false
                  cb.onSuccess(r)
                } else {
                  streamError = false
                  cb.onError(ex)
                }
              } catch {
                case NonFatal(err) if streamError =>
                  s.reportFailure(ex)
                  cb.onError(err)
              }
            }
          }))
      }
    }

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    */
  def onErrorRecoverWith[U >: T](pf: PartialFunction[Throwable, Task[U]]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          // RunLoop step, prevents stack-overflows
          self.unsafeRun(active, frameId, new Callback[T] {
            def onSuccess(v: T): Unit =
              cb.onSuccess(v)

            def onError(ex: Throwable): Unit = {
              var streamError = true
              try {
                if (pf.isDefinedAt(ex)) {
                  val newTask = pf(ex)
                  streamError = false
                  // Fallback to produced task
                  RunLoop.stepInterruptibly(active, frameId)(frameId =>
                    newTask.unsafeRun(active, frameId, cb))
                }
                else {
                  streamError = false
                  cb.onError(ex)
                }
              } catch {
                case NonFatal(err) if streamError =>
                  s.reportFailure(ex)
                  cb.onError(err)
              }
            }
          }))
      }
    }

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  def onErrorFallbackTo[U >: T](that: => Task[U]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          self.unsafeRun(active, frameId, new Callback[T] {
            def onSuccess(v: T): Unit =
              cb.onSuccess(v)

            def onError(ex: Throwable): Unit = {
              // RunLoop step, prevents stack-overflows
              var streamError = true
              try {
                val newTask = that
                streamError = false
                RunLoop.stepInterruptibly(active, frameId)(frameId =>
                  newTask.unsafeRun(active, frameId, cb))
              } catch {
                case NonFatal(err) if streamError =>
                  s.reportFailure(ex)
                  cb.onError(err)
              }
            }
          }))
      }
    }

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRetry[U >: T](maxRetries: Int): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        def loop(frameId: FrameId, runIdx: Int, ex: Throwable): Unit = {
          if (runIdx <= maxRetries || ex == null)
            RunLoop.stepInterruptibly(active, frameId)(frameId =>
              self.unsafeRun(active, frameId, new Callback[T] {
                def onSuccess(v: T) =
                  cb.onSuccess(v)
                def onError(ex: Throwable): Unit =
                  loop(frameId, runIdx+1, ex)
              }))
          else
            cb.onError(ex)
        }

        loop(frameId, runIdx=0, ex=null)
      }
    }

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRetryIf(p: Throwable => Boolean): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        def loop(frameId: FrameId, ex: Throwable): Unit =
          RunLoop.stepInterruptibly(active, frameId) { frameId =>
            self.unsafeRun(active, frameId, new Callback[T] {
              def onSuccess(v: T): Unit = cb.onSuccess(v)

              def onError(ex: Throwable): Unit = {
                var toReport = ex
                val shouldContinue = (ex == null) || (
                  try p(ex) catch {
                    case NonFatal(err) =>
                      toReport = err
                      s.reportFailure(ex)
                      false
                  })

                if (shouldContinue)
                  loop(frameId, ex)
                else
                  cb.onError(toReport)
              }
            })
          }

        loop(frameId, ex=null)
      }
    }

  /** Returns a Task that mirrors the source Task but that triggers a
    * `TimeoutException` in case the given duration passes without the
    * task emitting any item.
    */
  def timeout(after: FiniteDuration): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        val activeGate = Atomic(true)
        val activeGateTask = Cancelable(activeGate.set(false))

        val timeoutTask = s.scheduleOnce(after.length, after.unit,
          new Runnable {
            def run(): Unit =
              if (activeGate.getAndSet(false)) {
                active.cancel()
                val ex = new TimeoutException(s"Task timed-out after $after of inactivity")
                cb.onError(ex)
              }
          })

        val mainTask = MultiAssignmentCancelable()
        active := CompositeCancelable(timeoutTask, mainTask, activeGateTask)

        RunLoop.stepInterruptibly(active, frameId) { frameId =>
          // RunLoop step, prevents stack-overflows
          self.unsafeRun(mainTask, frameId, new Callback[T] {
            def onSuccess(v: T): Unit =
              if (activeGate.getAndSet(false)) {
                timeoutTask.cancel()
                active := mainTask
                cb.onSuccess(v)
              }

            def onError(ex: Throwable): Unit =
              if (activeGate.getAndSet(false)) {
                timeoutTask.cancel()
                active := mainTask
                cb.onError(ex)
              }
          })
        }
      }
    }

  /** Returns a Task that mirrors the source Task but switches to the
    * given backup Task in case the given duration passes without the
    * source emitting any item.
    */
  def timeout[U >: T](after: FiniteDuration, backup: => Task[U]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        val mainTask = MultiAssignmentCancelable()
        val gate = Atomic(true)
        val gateTask = Cancelable(gate.set(false))

        val timeoutTask = s.scheduleOnce(after.length, after.unit,
          new Runnable {
            def run(): Unit =
              if (gate.getAndSet(false)) {
                mainTask.cancel()
                RunLoop.startNow(frameId =>
                  backup.unsafeRun(active, frameId, cb))
              }
          })

        active := CompositeCancelable(gateTask, timeoutTask, mainTask)

        RunLoop.stepInterruptibly(active, frameId) { frameId =>
          // RunLoop step, prevents stack-overflows
          self.unsafeRun(mainTask, frameId, new Callback[T] {
            def onSuccess(v: T): Unit =
              if (gate.getAndSet(false)) {
                timeoutTask.cancel()
                active := mainTask
                cb.onSuccess(v)
              }

            def onError(ex: Throwable): Unit =
              if (gate.getAndSet(false)) {
                timeoutTask.cancel()
                active := mainTask
                cb.onError(ex)
              }
          })
        }
      }
    }

  /** Creates a new task that upon execution will return the result of
    * the first task that completes, while canceling the other.
    */
  def ambWith[U >: T](other: Task[U]): Task[U] =
    new Task[U] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[U])
        (implicit s: Scheduler): Unit = {

        val isActive = Atomic(true)
        val firstTask = MultiAssignmentCancelable()
        val secondTask = MultiAssignmentCancelable()
        active := CompositeCancelable(firstTask, secondTask, Cancelable(isActive.set(false)))

        RunLoop.stepInterruptibly(firstTask, frameId)(frameId =>
          self.unsafeRun(firstTask, frameId, new Callback[T] {
            def onSuccess(value: T): Unit =
              if (isActive.getAndSet(false)) {
                secondTask.cancel()
                active := firstTask // GC purposes
                cb.onSuccess(value)
              }

            def onError(ex: Throwable): Unit =
              if (isActive.getAndSet(false)) {
                secondTask.cancel()
                active := firstTask // GC purposes
                cb.onError(ex)
              }
          }))

        RunLoop.stepInterruptibly(secondTask, frameId)(frameId =>
          other.unsafeRun(secondTask, frameId, new Callback[U] {
            def onSuccess(value: U): Unit =
              if (isActive.getAndSet(false)) {
                firstTask.cancel()
                active := secondTask // GC purposes
                cb.onSuccess(value)
              }

            def onError(ex: Throwable): Unit =
              if (isActive.getAndSet(false)) {
                firstTask.cancel()
                active := secondTask // GC purposes
                cb.onError(ex)
              }
          }))
      }
    }


  /** Zips the values of `this` and `that` task, and creates a new task
    * that will emit the tuple of their results.
    */
  def zip[U](that: Task[U]): Task[(T, U)] =
    Task.map2(this, that)((a,b) => (a,b))
}

object Task {
  /** Represents a callback that should be called asynchronously
    * with the result of a computation. Used by [[Task]] to signal
    * the completion of asynchronous computations on `runAsync`.
    *
    * The `onSuccess` method should be called only once, with the successful
    * result, whereas `onError` should be called if the result is an error.
    */
  abstract class Callback[-T] {
    def onSuccess(value: T): Unit
    def onError(ex: Throwable): Unit
  }

  /** Returns a new task that, when executed, will emit the result of
    * the given function executed asynchronously.
    */
  def apply[T](f: => T): Task[T] =
    Task.fork(Task.eval(f))

  /** Returns a `Task` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[T](elem: T): Task[T] =
    new Now(elem)

  /** Promote a non-strict value to a Task, catching exceptions in the
    * process.
    *
    * Note that since `Task` is not memoized, this will recompute the
    * value each time the `Task` is executed.
    */
  def eval[T](f: => T): Task[T] =
    new Task[T] {
      def unsafeRun(c: MultiAssignmentCancelable, fid: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {
        // protecting against user code errors
        var streamErrors = true
        try {
          val result = f
          streamErrors = false
          cb.onSuccess(result)
        } catch {
          case NonFatal(ex) if streamErrors =>
            cb.onError(ex)
        }
      }
    }

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[T](task: => Task[T]): Task[T] =
    Task.eval(task).flatten

  /** Mirrors the given source `Task`, but upon execution it forks its
    * evaluation off into a separate (logical) thread.
    */
  def fork[T](task: Task[T]): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        if (RunLoop.isAlwaysAsync)
          RunLoop.startNow(frameId => task.unsafeRun(active, frameId, cb))
        else
          RunLoop.startAsync(frameId => task.unsafeRun(active, frameId, cb))
      }
    }

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def error(ex: Throwable): Task[Nothing] =
    new Error(ex)

  /** Builder for [[Task]] instances. For usage on implementing
    * operators or builders. Internal to Monix.
    */
  private[monix] def unsafeCreate[T](f: (Scheduler, MultiAssignmentCancelable, FrameId, Callback[T]) => Unit): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = f(s, active, frameId, cb)
    }

  /** Create a `Task` from an asynchronous computation, which takes the
    * form of a function with which we can register a callback. This
    * can be used to translate from a callback-based API to a
    * straightforward monadic version.
    *
    * @param register is a function that will be called when this `Task`
    *        is executed, receiving a callback as a parameter, a
    *        callback that the user is supposed to call in order to
    *        signal the desired outcome of this `Task`.
    */
  def create[T](register: (Callback[T], Scheduler) => Cancelable): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        try {
          val callback = new Callback[T] {
            def onSuccess(value: T) =
              cb.onSuccess(value)
            def onError(ex: Throwable): Unit =
              cb.onError(ex)
          }

          active := register(callback, s)
        } catch {
          case NonFatal(ex) =>
            cb.onError(ex)
        }
      }
    }

  /** Converts the given Scala `Future` into a `Task` */
  def fromFuture[T](f: => Future[T]): Task[T] =
    new Task[T] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
        (implicit s: Scheduler): Unit = {

        f.onComplete {
          case Success(value) =>
            if (!active.isCanceled)
              cb.onSuccess(value)
          case Failure(ex) =>
            if (!active.isCanceled)
              cb.onError(ex)
        }(s)
      }
    }

  /** Creates a `Task` that upon execution will return the result of the
    * first completed task in the given list and then cancel the rest.
    */
  def amb[T](tasks: Task[T]*): Task[T] = new Task[T] {
    require(tasks.nonEmpty, "tasks.nonEmpty")

    def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[T])
      (implicit s: Scheduler): Unit = {

      val isActive = Atomic(true)
      val composite = CompositeCancelable()
      active := composite

      for (task <- tasks) {
        val taskCancelable = MultiAssignmentCancelable()
        composite += taskCancelable

        RunLoop.stepInterruptibly(taskCancelable, frameId)(frameId =>
          task.unsafeRun(taskCancelable, frameId, new Callback[T] {
            def onSuccess(value: T): Unit =
              if (isActive.compareAndSet(expect=true, update=false)) {
                composite -= taskCancelable
                composite.cancel()
                active := taskCancelable
                cb.onSuccess(value)
              }

            def onError(ex: Throwable): Unit =
              if (isActive.compareAndSet(expect=true, update=false)) {
                composite -= taskCancelable
                composite.cancel()
                active := taskCancelable
                cb.onError(ex)
              }
          }))
      }
    }
  }

  /** Given two tasks and a mapping function, returns a new Task that will
    * be the result of the mapping function applied to their results.
    */
  def map2[A,B,R](taskA: Task[A], taskB: Task[B])(f: (A,B) => R): Task[R] = {
    def sendSignal(cb: Callback[R], a: A, b: B): Unit = {
      var streamErrors = true
      try {
        val r = f(a,b)
        streamErrors = false
        cb.onSuccess(r)
      } catch {
        case NonFatal(ex) if streamErrors =>
          cb.onError(ex)
      }
    }

    new Task[R] {
      def unsafeRun(active: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[R])
        (implicit scheduler: Scheduler): Unit = {

        val state = Atomic(null : Either[A,B])
        val thisTask = MultiAssignmentCancelable()
        val thatTask = MultiAssignmentCancelable()

        val gate = Atomic(true)
        val gateTask = Cancelable(gate.getAndSet(false))
        active := CompositeCancelable(thisTask, thatTask, gateTask)

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          taskA.unsafeRun(thisTask, frameId, new Callback[A] {
            def onError(ex: Throwable): Unit =
              if (gate.getAndSet(false)) {
                active.cancel()
                cb.onError(ex)
              }

            @tailrec def onSuccess(a: A): Unit =
              state.get match {
                case null =>
                  if (!state.compareAndSet(null, Left(a))) onSuccess(a)
                case Right(b) =>
                  sendSignal(cb, a, b)
                case s @ Left(_) =>
                  onError(new IllegalStateException(s.toString))
              }
          }))

        RunLoop.stepInterruptibly(active, frameId)(frameId =>
          // RunLoop step, prevents stack-overflows
          taskB.unsafeRun(thatTask, frameId, new Callback[B] {
            def onError(ex: Throwable): Unit =
              if (gate.getAndSet(false)) {
                active.cancel()
                cb.onError(ex)
              }

            @tailrec def onSuccess(b: B): Unit =
              state.get match {
                case null =>
                  if (!state.compareAndSet(null, Right(b))) onSuccess(b)
                case Left(a) =>
                  sendSignal(cb, a, b)
                case s @ Right(_) =>
                  onError(new IllegalStateException(s.toString))
              }
          }))
      }
    }
  }

  /** Creates a `Task` that upon execution will return the result of the
    * first completed task in the given list and then cancel the rest.
    *
    * Alias for [[Task.amb]].
    */
  def firstCompletedOf[T](tasks: Task[T]*): Task[T] =
    amb(tasks:_*)

  /** Transforms a `TraversableOnce[Task[A]]` into a
    * `Task[TraversableOnce[A]]`.  Useful for reducing many `Task`s
    * into a single `Task`.
    */
  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]], scheduler: Scheduler): Task[M[A]] = {

    new Task[M[A]] {
      def unsafeRun(cancelable: MultiAssignmentCancelable, frameId: FrameId, cb: Callback[M[A]])
        (implicit scheduler: Scheduler): Unit = {

        val cancelables = ArrayBuffer.empty[Cancelable]
        var builder = Future.successful(cbf(in))

        for (task <- in) {
          val p = Promise[A]()
          val cancelable = task.runAsync(new Callback[A] {
            def onSuccess(value: A): Unit = p.trySuccess(value)
            def onError(ex: Throwable): Unit = p.tryFailure(ex)
          })

          cancelables += cancelable
          builder = for (r <- builder; a <- p.future) yield r += a
        }

        cancelable := CompositeCancelable(cancelables:_*)
        builder.onComplete {
          case Success(r) =>
            cb.onSuccess(r.result())
          case Failure(ex) =>
            cb.onError(ex)
        }
      }
    }
  }

  /** A `SafeCallback` is a callback that ensures it can only be called
    * once, with a simple check.
    */
  private class SafeCallback[-T]
    (underlying: Callback[T])(implicit r: UncaughtExceptionReporter)
    extends Callback[T] {

    private[this] var isActive = true

    /** To be called only once, on successful completion of a [[Task]] */
    def onSuccess(value: T): Unit =
      if (isActive) {
        isActive = false
        try underlying.onSuccess(value) catch {
          case NonFatal(ex) =>
            r.reportFailure(ex)
        }
      }

    /** To be called only once, on failure of a [[Task]] */
    def onError(ex: Throwable): Unit =
      if (isActive) {
        isActive = false
        try underlying.onError(ex) catch {
          case NonFatal(err) =>
            r.reportFailure(ex)
            r.reportFailure(err)
        }
      }
  }

  private object SafeCallback {
    /** Wraps any [[Callback]] into a [[SafeCallback]]. For usage in
      * `runAsync`.
      */
    def apply[T](cb: Callback[T])(implicit r: UncaughtExceptionReporter): Callback[T] =
      cb match {
        case _: SafeCallback[_] => cb
        case _ => new SafeCallback[T](cb)
      }
  }

  /** Optimized task for already known strict values. Internal to Monix,
    * not for public consumption.
    *
    * See [[Task.now]] instead.
    */
  private final class Now[+T](value: T) extends Task[T] {
    def unsafeRun(c: MultiAssignmentCancelable, fid: FrameId, cb: Callback[T])
      (implicit s: Scheduler): Unit =
      cb.onSuccess(value)

    override def runAsync(implicit s: Scheduler): CancelableFuture[T] =
      CancelableFuture(Future.successful(value), Cancelable.empty)
  }

  /** Optimized task for failed outcomes. Internal to Monix, not for
    * public consumption.
    *
    * See [[Task.error]] instead.
    */
  private final class Error(ex: Throwable) extends Task[Nothing] {
    def unsafeRun(c: MultiAssignmentCancelable, fid: FrameId, cb: Callback[Nothing])
      (implicit s: Scheduler): Unit =
      cb.onError(ex)

    override def runAsync(implicit s: Scheduler): CancelableFuture[Nothing] =
      CancelableFuture(Future.failed(ex), Cancelable.empty)
  }
}
