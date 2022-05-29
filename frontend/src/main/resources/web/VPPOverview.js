"use strict"
const defaultGroupName = "default"
/**
 * renders and manages an overview of the states of all devices in a DeviceGroup
 */
class VPPOverview {
    constructor(deviceSimulatorsToRender, htmlToDisplayDeviceList) {
        /**
         * a list of all DeviceSimulators whose state should be rendered and managed
         */
        this.deviceSimulatorsToRender = deviceSimulatorsToRender

        /**
         * the html element where contents should be displayed
         */
        this.htmlToDisplayDeviceList = htmlToDisplayDeviceList

        /**
         * the index at which new devices should be created
         */
        this.currentIdAppendix = 0
    }

    /**
     * add a DeviceSimulator to be displayed
     */
    addDeviceSimulator() {
        var tmp = this.createUniqueId("device", defaultGroupName)
        var deviceId = tmp[0]
        var groupId = tmp[1]
        var uniqueId = tmp[2]
        this.deviceSimulatorsToRender[uniqueId] = new DeviceUI(deviceId, groupId)
        this.deviceSimulatorsToRender[uniqueId].start()
    }

    /**
     * remove a DeviceSimulator to be displayed
     */
    removeDeviceSimulator() {
        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (var i = 0; i < keys.length; i++) {
            var keyOfDeviceSimulatorToStop = keys[i]
            var deviceSimulatorToStop = this.deviceSimulatorsToRender[keyOfDeviceSimulatorToStop]
            if (deviceSimulatorToStop.getSimulationState() == "active") {
                deviceSimulatorToStop.stop()
                break;
            }
        }
    }

    /**
     * returns [the deviceId, the groupId, a unique identifier of the device across all groups] as an array 
     * @param {*} baseDeviceName a generic name for all devices that is postfixed with a number 
     * @param {*} groupId the group of this device
     * @returns 
     */
    createUniqueId(baseDeviceName, groupId) {
        var uniqueId = DeviceUI.createDeviceIndentifier(groupId, baseDeviceName + this.currentIdAppendix)
        if (uniqueId in this.deviceSimulatorsToRender) {
            this.currentIdAppendix = this.currentIdAppendix + 1;
            uniqueId = this.createUniqueId(baseDeviceName, groupId)
            return uniqueId
        } else {
            return [baseDeviceName + this.currentIdAppendix, groupId, uniqueId]
        }
    }

    /**
     * sends a request for the state of all Device's in group "default" to the IoT prototype
     * and updates the state of this VPPOverview accordingly by using postUpdateDeviceDataFromServer
     */
    getDataFromServer() {
        function dataFromServerHandler() {
            if (this.readyState == 4) {
                if (this.status == 200) {
                    if (this.responseText != null) {
                        const o = JSON.parse(this.response)
                        const deviceDataFromServer = o
                        const vppOverview = this.myParameters[0]
                        vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer, this.myParameters[1])
                    } else alert("Communication error: No data received")
                } else alert("Communication error: " + this.statusText)
            }
        }
        var headers = { "Content-Type": "application/json" }
        Util.sendRequestToServer("/vpp/default", "GET", null, headers, dataFromServerHandler, [this, "default"])
    }

    /**
     * updates the state of this VPPOverview accordingly by using postUpdateDeviceDataFromServer
     * @param {*} deviceDataFromServer 
     * @param {*} groupId 
     */
    postUpdateOfDeviceDataFromServer(deviceDataFromServer, groupId) {
        var keys = Object.keys(deviceDataFromServer)
        for (let i = 0; i < keys.length; i++) {
            var deviceId = keys[i]
            var device = deviceDataFromServer[deviceId]
            var uniqueId = DeviceUI.createDeviceIndentifier(groupId, deviceId)

            const isInServerDataAndIsInLocalDataAndRequestedToStop = (uniqueId in this.deviceSimulatorsToRender) && (this.deviceSimulatorsToRender[uniqueId].getSimulationState() == "passive")
            if (isInServerDataAndIsInLocalDataAndRequestedToStop) {
                this.deviceSimulatorsToRender[uniqueId].stop()
            }

            const isInServerDataButIsNotInLocalData = !(uniqueId in this.deviceSimulatorsToRender)
            if (isInServerDataButIsNotInLocalData) {
                this.deviceSimulatorsToRender[uniqueId] = new DeviceUI(deviceId, groupId)
            }

            var dataDescription = device.description
            if (dataDescription == "chargeStatus") {// data is available
                var dataValue = device.value.value

                this.deviceSimulatorsToRender[uniqueId].setChargeStatus(dataValue)

                var currentHost = device.value.currentHost
                this.deviceSimulatorsToRender[uniqueId].setCurrentHost(currentHost)
            }
        }

        // delete DeviceUI that are stopped at Server, but are still present locally 
        for (const key of Object.keys(this.deviceSimulatorsToRender)) {
            var parsedDeviceIdentifier = (DeviceUI.parseDeviceIdentifier(key))

            const isInLocalDataButNotInServerDataAndGroupMatchesAndStopClicked = !(deviceDataFromServer.hasOwnProperty(parsedDeviceIdentifier.deviceId)) && (parsedDeviceIdentifier.groupId == groupId) && (this.deviceSimulatorsToRender[key].simulationState == "passive")
            if (isInLocalDataButNotInServerDataAndGroupMatchesAndStopClicked) {
                delete this.deviceSimulatorsToRender[key]
            }
        }
    }

    /**
     * display the state of all devices
     */
    plot() {
        this.plotDeviceList()
    }

    /**
     * display the state of all devices as a list
     */
    plotDeviceList() {
        this.tableCreate();

    }

    /**
     * plot a table of all devices
     */
    tableCreate() {
        this.htmlToDisplayDeviceList.innerHTML = "";
        const tbl = document.createElement('table');
        this.htmlToDisplayDeviceList.appendChild(tbl);
        tbl.setAttribute('id', 'deviceTable')

        tbl.style.width = '100px';
        tbl.style.border = '1px solid black';

        const tr = tbl.insertRow();
        var myHeaders = ["id", "chargeStatus", "capacity", "host", "", ""]
        for (let i = 0; i < myHeaders.length; i++) {
            const th = document.createElement('th')
            th.appendChild(document.createTextNode(myHeaders[i]));
            tr.appendChild(th)
        }

        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (let i = 0; i < keys.length; i++) {
            var deviceSimulatorToRender = this.deviceSimulatorsToRender[keys[i]]
            const tr = tbl.insertRow();
            tr.setAttribute("id", "tr:" + deviceSimulatorToRender.getDeviceId())
            tr.setAttribute("class", "deviceRow")

            const tdId = tr.insertCell();
            tdId.appendChild(document.createTextNode(deviceSimulatorToRender.getDeviceId()))

            const tdChargeStatus = tr.insertCell();
            const divChargeStatus = document.createElement('div')
            divChargeStatus.setAttribute('id', deviceSimulatorToRender.getDeviceId())
            divChargeStatus.setAttribute('class', 'bh-batteryWidget')

            const batteryWidget = new BatteryWidget(divChargeStatus)
            batteryWidget.setBatteryValue(deviceSimulatorToRender.getChargeStatus() * 100)
            tdChargeStatus.appendChild(divChargeStatus)
            tr.appendChild(tdChargeStatus)

            const tdCapacity = tr.insertCell();
            tdCapacity.appendChild(document.createTextNode(deviceSimulatorToRender.getCapacity()))

            const tdHost = tr.insertCell();
            tdHost.appendChild(document.createTextNode(deviceSimulatorToRender.getCurrentHost()))

            const tdDetails = tr.insertCell();
            const detailsLink = document.createElement("a");
            detailsLink.innerHTML = "details"
            detailsLink.setAttribute("href", "vpp/device/" + deviceSimulatorToRender.getGroupId() + "/" + deviceSimulatorToRender.getDeviceId() + "/details")
            tdDetails.appendChild(detailsLink)

            const tdStop = tr.insertCell();
            const stopButton = document.createElement("button");
            stopButton.innerHTML = "stop"
            stopButton.setAttribute("id", "stop_" + deviceSimulatorToRender.getDeviceId())
            stopButton.setAttribute("class", "stopButton")

            stopButton.device = deviceSimulatorToRender
            stopButton.onclick = function (event) {
                event.currentTarget.device.stop()
            }
            tdStop.appendChild(stopButton)

        }
    }
}