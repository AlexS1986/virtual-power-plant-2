package twinreadside.repository

import scalikejdbc._
import java.time.LocalDateTime

/**
  * provides readside access to datastore in CQRS pattern
  */
trait DeviceEnergyDepositsRepository {
  
  /**
    * get all energy deposits in a time interval
    *
    * @param session
    * @param vppId
    * @param before
    * @param after
    * @return
    */
    def queryEnergyDeposits(session: ScalikeJdbcSession, vppId: String, before: LocalDateTime, after: LocalDateTime) : Option[Double]

    /**
      * delete all energy deposits before a certain time
      *
      * @param session
      * @param before
      */
    def deleteEnergyDeposits(session: ScalikeJdbcSession, before : LocalDateTime) : Unit
}

class DeviceEnergyDepositsRepositoryImpl() extends DeviceEnergyDepositsRepository {

  override def deleteEnergyDeposits(session: ScalikeJdbcSession, before : LocalDateTime): Unit =  {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx {
        implicit dbSession =>
          deleteEnergyDeposits(before)
      }
    } else {
      session.db.localTx{
        implicit dbSession =>
          deleteEnergyDeposits(before)
      }
    }
  }

  /**
    * helper function that deletes in database
    *
    * @param before
    * @param dbSession
    */
  private def deleteEnergyDeposits(before : LocalDateTime)(implicit dbSession:DBSession) : Unit = {
          sql"""DELETE FROM energy_deposit WHERE time_stamp < $before""".execute().apply()    
  }

  override def queryEnergyDeposits(session: ScalikeJdbcSession, vppId: String, before: LocalDateTime, after: LocalDateTime): Option[Double] = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        queryDbForEnergyDeposits(vppId,before,after)
      }
    } else {
      session.db.readOnly { implicit dbSession =>
         queryDbForEnergyDeposits(vppId,before,after)
      }
    }
  }

  /**
    * helper function that queries database
    *
    * @param vppId
    * @param before
    * @param after
    * @param dbSession
    * @return
    */
  private def queryDbForEnergyDeposits(vppId: String, before: LocalDateTime, after: LocalDateTime)(implicit dbSession: DBSession) : Option[Double] = {
    sql""" SELECT SUM(energy_deposited) FROM energy_deposit WHERE vpp_id = $vppId AND time_stamp BETWEEN $after AND $before   
       """
       .map(wrappedResultSet => wrappedResultSet.doubleOpt("sum"))
       .single
       .apply.map {
         case Some(energyDeposited) => energyDeposited
         case None => 0
       }
  }

  private def select(deviceId: String)(implicit dbSession: DBSession) : Option[Double] = {
    sql"SELECT temperature FROM device_temperature WHERE device_id = $deviceId"
      .map(_.double("temperature"))
      .toOption()
      .apply()
  }
}