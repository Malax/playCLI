package cli

import java.io._
import scala.sys.process.{ Process, ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

import concurrent.{ promise, Future, ExecutionContext }

/**
 * CLI provides a link between play iteratee and an UNIX command.
 *
 * Depending on your needs, you can Pipe / Enumerate / Consume with an UNIX command:
 *
 * `CLI.pipe` create an [[play.api.libs.iteratee.Enumeratee]]
 * `CLI.enumerate` create an [[play.api.libs.iteratee.Enumerator]]
 * `CLI.consume` create an [[play.api.libs.iteratee.Iteratee]]
 */
object CLI {


  /**
   * Get an [[play.api.libs.iteratee.Enumerator]] from a CLI output.
   * (nothing is sent to the CLI input)
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumerator[Array[Byte]] = Enumerator.flatten[Array[Byte]] {
    val (process, stdin, stdout) = runProcess(cmd)

    stdout map { stdout =>
      Enumerator.fromStream(stdout, chunkSize).
      onDoneEnumerating { () =>
        // stdin map { _.close() }
        // process.destroy()
      }
    }
  }

  /**
   * Get an [[play.api.libs.iteratee.Enumeratee]] from the CLI piping.
   *
   * @example {{{
     // Add an echo to an ogg audio stream.
     oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumeratee[Array[Byte], Array[Byte]] = {
    val (process, stdin, stdout) = runProcess(cmd)
    
    val promiseCmdin = stdin.map { cmdin =>
      Iteratee.foreach[Array[Byte]] { bytes =>
        cmdin.write(bytes)
      }
    }
    val promiseCmdout = stdout.map { cmdout =>
      Enumerator.fromStream(cmdout, chunkSize)
    }

    import Enumeratee.CheckDone

    var promiseOfEnumeratee = (promiseCmdin zip promiseCmdout).map { case (cmdin, cmdout) =>

      // FIXME, I'm trying something, this is not working yet...
      /**
       * Create an Enumeratee where:
       * - all input from this Enumeratee are plugged to the cmdin (cmdin: Iteratee)
       * - all input coming from cmdout (cmdout: Enumerator) are plugged to the output of this Enumeratee
       */
      new CheckDone[Array[Byte], Array[Byte]] {

        def step[A](k: K[Array[Byte], A]): K[Array[Byte], Iteratee[Array[Byte], A]] = {
          case in @ Input.EOF => {
            cmdin.feed(in)
            Done(Cont(k), Input.EOF)
          }
          case in => {
            cmdin.feed(in)
            val r = Iteratee.flatten(cmdout |>> k(in))
            new CheckDone[Array[Byte], Array[Byte]] { 
              def continue[A](k: K[Array[Byte], A]) = Cont(step(k)) 
            } &> r
          }

        }
        def continue[A](k: K[Array[Byte], A]) = Cont(step(k))
      } ><> 
        Enumeratee.onIterateeDone { () =>
          //process.destroy() // FIXME
        }
    }
    concurrent.Await.result(promiseOfEnumeratee, concurrent.duration.Duration("1 second")) // FIXME need a Enumeratee.flatten
  }

  /**
   * Get an [[play.api.libs.iteratee.Iteratee]] consuming data 
   * to push in the CLI (regardless of the CLI output).
   *
   * @example {{{
     val consumer = CLI.consume("aSideEffectCommand")
     anEnumerator(consumer)
   * }}}
   */
  def consume (cmd: ProcessBuilder)(implicit ex: ExecutionContext): Iteratee[Array[Byte], Unit] = Iteratee.flatten[Array[Byte], Unit] {
    val (process, stdin, stdout) = runProcess(cmd)

    stdin map { cmdin =>
      Iteratee.foreach[Array[Byte]] { bytes =>
        cmdin.write(bytes)
      } mapDone { _ =>
        cmdin.close()
        // stdout map { _.close() }
        // process.destroy() // FIXME maybe the cmd is maybe processing slowly (but what to do?)
      }
    }
  }


  private val logger = play.api.Logger("CLI")

  private def logstderr (stderr: InputStream) {
    val br = new java.io.BufferedReader(new InputStreamReader(stderr))
    var read = br.readLine()
    while(read != null) {
      logger.warn(read)
      read = br.readLine()
    }
    stderr.close()
  }

  /**
   * Run a process from a command and return a (process, future of stdin, future of stdout)
   */
  private def runProcess (command: ProcessBuilder)(implicit ex: ExecutionContext): (Process, Future[OutputStream], Future[InputStream]) = {
    val promiseStdin = promise[OutputStream]()
    val promiseStdout = promise[InputStream]()

    val process = command run new ProcessIO(
      (stdin: OutputStream) => promiseStdin.success(stdin),
      (stdout: InputStream) => promiseStdout.success(stdout),
      logstderr(_)
    )
    (process, promiseStdin.future, promiseStdout.future)
  }
}
