package signals

import cats.effect.std.{Dispatcher, Queue, Random}
import cats.effect.{Deferred, IO, IOApp, Ref, Resource}
import sun.misc.Signal

import scala.concurrent.duration.DurationInt
import fs2._

object Main extends IOApp.Simple {
  private def resource(name: String) =
    Resource.make(IO.println(s"Acquiring $name"))(_ => IO.println(s"Releasing $name"))

  private def installSignalHandler(dispatcher: Dispatcher[IO], shouldTerminate: Deferred[IO, Unit]): IO[Unit] =
    IO(
      Signal.handle(
        new Signal("TERM"),
        signal => {
          println(s"Received signal ${signal.getName}")
          dispatcher.unsafeRunAndForget(shouldTerminate.complete(()))
        }))

  val runWithStream: Stream[IO, Unit] = {
    for {
      dispatcher <- Stream.resource(Dispatcher[IO])
      messageRef <- Stream.eval(Ref.of[IO, String]("Hello."))
      jobs <- Stream.eval(Queue.unbounded[IO, Int])
      shutdownComplete <- Stream.eval(Deferred[IO, Either[Throwable, Unit]])
      startShutdown <- Stream.eval(Deferred[IO, Unit])
      _ <- Stream.eval(installSignalHandler(dispatcher, startShutdown))
      random = Random.javaUtilConcurrentThreadLocalRandom[IO]
      helloStream = Stream
        .repeatEval {
          for {
            message <- messageRef.get
            jobId <- random.nextIntBounded(100)
            _ <- startShutdown.tryGet.flatMap {
              case Some(_) => IO.println("Shutdown in progress, not starting new jobs.")
              case None => IO.println(s"Starting job $jobId...") *> jobs.offer(jobId)
            }
            jobsInProgress <- jobs.size
          } yield println(s"$jobsInProgress jobs in progress. $message")
        }
        .metered(1.second)
      jobRunnerStream = Stream.sleep[IO](3.seconds) ++ Stream
        .repeatEval(jobs.take.flatMap(jobId => IO.println(s"Finished job $jobId.")))
        .metered(1.second)
      gracefulShutdownStream = Stream.repeatEval {
        startShutdown.get.flatMap { _ =>
          val gracefulShutdown = for {
            _ <- messageRef.update(_ => "Bye...")
            nJobsInProgress <- jobs.size
            _ <-
              if (nJobsInProgress > 0) {
                IO.println(s"Shutting down, waiting for $nJobsInProgress jobs...")
              } else {
                shutdownComplete.complete(Right(())) *> IO.println("Shutdown complete!")
              }
          } yield ()
          gracefulShutdown
            .onError(throwable => shutdownComplete.complete(Left(throwable)).void)
            .onCancel(shutdownComplete.complete(Left(new InterruptedException("Interrupted"))).void)
        }
      }.metered(500.millis)
      stream <- helloStream
        .concurrently(jobRunnerStream)
        .concurrently(gracefulShutdownStream)
        .interruptWhen(shutdownComplete)
    } yield stream
  }

  override def run: IO[Unit] =
    runWithStream.compile.drain

  val runWithoutStream: IO[Unit] = {
    val outer = resource("outer")
    val inner = resource("inner")
    Dispatcher[IO].use { dispatcher =>
      Deferred[IO, Unit].flatMap { shouldTerminate =>
        installSignalHandler(dispatcher, shouldTerminate) *> outer.use { _ =>
          IO.println("Outer") *> inner.use { _ =>
            IO.println("Inner") *> shouldTerminate.get.flatMap { result =>
              for {
                _ <- IO.println(s"Inner done with ${result.toString}")
                _ <- IO.sleep(3.seconds)
                _ <- IO.println("Graceful shutdown completed")
              } yield ()
            }
          }
        }
      }
    }
  }
}
