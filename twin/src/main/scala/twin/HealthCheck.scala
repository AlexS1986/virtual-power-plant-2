package twin

import scala.concurrent.Future

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

// enabled in application.conf
class HealthCheck(system: ActorSystem) extends (() => Future[Boolean]) {
  private val log = LoggerFactory.getLogger(getClass)

  override def apply(): Future[Boolean] = {
    log.info("HealthCheck called")
    Future.successful(true)
  }
}
