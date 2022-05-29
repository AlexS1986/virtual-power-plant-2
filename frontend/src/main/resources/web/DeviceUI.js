"use strict"

/**
 * represents a battery device at the user interface
 */
class DeviceUI {
    constructor(deviceId, groupId) {
        /**
         * an Id for the device
         */
        this.deviceId = deviceId;

        /**
         * the group that this device is a member of
         */
        this.groupId = groupId;

        /**
         * the simulation state : "passive" -> stop request at UI
         */
        this.simulationState = "active";

        /**
         * the chargeStatus of this battery
         */
        this.chargeStatus = 0 

        /**
         * the current host of this battery
         */
        this.currentHost = ""

        /**
         * the last ten delivered energy readings
         */
        this.lastTenDeliveredEnergyReadings = []

        /**
         * the total capacity of this device to store electrical energy
         */
        this.capacity = 100
        console.log("Device created! " + this.createDeviceIndentifier())
    }

    getSimulationState(){
        return this.simulationState
    }

    /**
     * request to stop the simulation of this battery
     */
    stop() {
        this.sendStopNotificationToServer()
        this.simulationState = "passive"
    }

    /**
     * requests to start the simulation of this battery
     */
    start() {
        this.sendStartNotificationToServer()
    }

    setChargeStatus (chargeStatus) {
        this.chargeStatus = chargeStatus
    }

    getChargeStatus() {
        return this.chargeStatus
    }

    setCurrentHost (currentHost) {
        this.currentHost= currentHost
    }

    getCurrentHost(){
        return this.currentHost
    }

    getCapacity() {
        return this.capacity
    }

    getDeviceId() {
        return this.deviceId
    }

    getGroupId() {
        return this.groupId
    }

    setLastTenDeliveredEnergyReadings(lastTenDeliveredEnergyReadings) {
        this.lastTenDeliveredEnergyReadings = lastTenDeliveredEnergyReadings
    }

    getLastTenDeliveredEnergyReadings() {
        return this.lastTenDeliveredEnergyReadings
    }

    /**
     * extracts the deviceId and groupId from a unique device indentifies of the form Device|deviceId&&&groupId
     * @param {*} deviceIdentifier 
     * @param {*} defaultGroupName 
     * @returns 
     */
    static parseDeviceIdentifier(deviceIdentifier, defaultGroupName = "default") {
        var groupIdDeviceId = [defaultGroupName, deviceIdentifier];
        if (deviceIdentifier.indexOf("|") > -1 && deviceIdentifier.indexOf("&&&") > -1) {
            groupIdDeviceId = deviceIdentifier.split("|")[1].split("&&&")
        }

        return {
            "groupId": groupIdDeviceId[0],
            "deviceId": groupIdDeviceId[1]
        }
    }

    /**
     * creates a unique device identifier of the form Device|deviceId&&&groupId
     * @returns 
     */
    createDeviceIndentifier() {
        return DeviceUI.createDeviceIndentifier(this.groupId,this.deviceId)
    }

    static createDeviceIndentifier(groupId,deviceId) {
        return "Device|"+groupId+"&&&"+deviceId
    } 

    /**
     * sends a request to start the simulation of a battery to the simulator application
     */
    sendStartNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"deviceId": this.deviceId,"groupId": this.groupId })
        Util.sendRequestToServer("simulator/"+this.groupId+"/"+this.deviceId + "/start","POST",data,headers)
    }

    /**
     * sends a request to stop the simulation of a battery to the IoT prototype
     */
    sendStopNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        Util.sendRequestToServer("vpp/device/"+this.groupId+"/"+this.deviceId,"DELETE",null,headers)
    }

}