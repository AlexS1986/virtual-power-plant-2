package twin.repository

import scalikejdbc._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Provides write access to a readside datastore that aggregates information on Devices
  * 
  */
trait DeviceRepository {
    /** Records an energy deposit in the readside datastore
      * 
      *
      * @param session session for the readside database
      * @param groupId the ID of the DeviceGroup
      * @param deviceId the ID of the device
      * @param energyDeposit the amount of energy deposited
      * @param timestamp the time of the energy deposit
      */
    def recordEnergyDeposit(session: ScalikeJdbcSession, groupId: String, deviceId: String, energyDeposit: Double, timestamp: LocalDateTime) : Unit
}

class DeviceRepositoryImpl() extends DeviceRepository {
  override def recordEnergyDeposit(session: ScalikeJdbcSession, groupId: String, deviceId: String,  energyDeposit: Double, timestamp: LocalDateTime): Unit = {
      session.db.withinTx { implicit dbSession =>
        sql"""
            INSERT INTO energy_deposit VALUES($groupId,$deviceId,$timestamp,$energyDeposit)
            ON CONFLICT (vpp_id,device_id,time_stamp) DO UPDATE SET energy_deposited = $energyDeposit
          """.executeUpdate().apply()
      } 
    }
}