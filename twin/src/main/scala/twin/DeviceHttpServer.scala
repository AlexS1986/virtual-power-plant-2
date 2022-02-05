package twin

import scala.util.{Failure, Success}
import scala.concurrent.duration._

import akka.actor.typed.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.{Done, actor => classic}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

private[twin] object DeviceHttpServer {
    def start(routes: Route, port: Int, system: ActorSystem[_]) : Unit = {

        import akka.actor.typed.scaladsl.adapter._
        implicit val classicSystem: classic.ActorSystem = system.toClassic
        val shutdown = CoordinatedShutdown(classicSystem)

        import system.executionContext
        Http().bindAndHandle(routes, "0.0.0.0", port).onComplete { // IP changed from local host
            case Success(binding) => 
                val address = binding.localAddress
                system.log.info(
                    "DeviceServer online at http://{}:{}", address.getHostString, address.getPort
                )
                shutdown.addTask(
                    CoordinatedShutdown.PhaseServiceRequestsDone,
                    "http-graceful-terminate"
                ) { () =>
                    binding.terminate(10.seconds).map { _ =>
                        system.log.info(
                            "DeviceServer http://{}:{}/ graceful shutdown completed",
                            address.getHostString,
                            address.getPort
                        )
                        Done
                    }
                }
            case Failure(exception) => 
                system.log.error("Failed to bind HTTP endpoint, terminating system", exception)
                system.terminate()
        }
    }
}