package twin.projections

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId}
import twin.Device

import twin.repository.DeviceRepository
import twin.repository.ScalikeJdbcSession
//import akka.actor.typed.scaladsl.ActorContext

/** Defines an Akka Projection that captures device events from an Event Log and stores the processed results in a readside database
  * 
  */
object DeviceProjection {
  
  /** initializes the Akka Projection to be executed by a ShardedDaemonProcess
    * 
    *
    * @param system
    * @param repository a repository that enables access to a readside database
    */
  def init(system: ActorSystem[_], repository: DeviceRepository): Unit = {
    ShardedDaemonProcess(system).init(
      name = "DeviceProjection",
      Device.tags.size,
      index => ProjectionBehavior(createProjectionFor(system, repository, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  /** Returns a Projection Implementation. 
   * 
   *  First defines a SourceProvider that provides events as a stream which reads from the Event-Log
    *
    * @param system
    * @param repository provides access to the readside database
    * @param index indicates the share of Device.Events that this Projection should handle
    */
  private def createProjectionFor(
      system: ActorSystem[_],
      repository: DeviceRepository,
      index: Int,
  ): ExactlyOnceProjection[Offset, EventEnvelope[Device.Event]] = {
    val tag = Device.tags(index) 
    val sourceProvider: SourceProvider[Offset, EventEnvelope[Device.Event]] =
      EventSourcedProvider.eventsByTag[Device.Event](
        system = system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("DeviceProjection", tag),
      sourceProvider,
      handler = () => new DeviceProjectionHandler(tag, system, repository),
      sessionFactory = () => new ScalikeJdbcSession()
    )(system)
  }

}
