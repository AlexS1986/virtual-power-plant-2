"use strict"
const defaultGroupName = "default"

class VPPOverview {
    constructor(paper,deviceSimulatorsToRender,htmlToDisplayDeviceList) {
        this.paper = paper
        this.deviceSimulatorsToRender = deviceSimulatorsToRender // associative array
        this.htmlToDisplayDeviceList = htmlToDisplayDeviceList
        this.currentIdAppendix = 0
    }

    addDeviceSimulator() {
        var tmp = this.createUniqueId("device",defaultGroupName)
        var deviceId = tmp[0]
        var groupId = tmp[1]
        var uniqueId = tmp[2]
        this.deviceSimulatorsToRender[uniqueId] = new DeviceUI(deviceId,groupId)
        this.deviceSimulatorsToRender[uniqueId].start()
    }

    removeDeviceSimulator() {
        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (var i = 0; i< keys.length;i++) {
            var keyOfDeviceSimulatorToStop = keys[i]
            var deviceSimulatorToStop = this.deviceSimulatorsToRender[keyOfDeviceSimulatorToStop]
            if (deviceSimulatorToStop.getSimulationState() == "active") {
                deviceSimulatorToStop.stop()
                break;
            }
        }           
    }

    createUniqueId(baseDeviceName,groupId) {
        var uniqueId = DeviceUI.createDeviceIndentifier(groupId,baseDeviceName+this.currentIdAppendix)
        if (uniqueId in this.deviceSimulatorsToRender) {
            this.currentIdAppendix=this.currentIdAppendix+1;
            uniqueId = this.createUniqueId(baseDeviceName,groupId)
            return uniqueId
        } else {
            return [baseDeviceName+this.currentIdAppendix,groupId, uniqueId]
        }
    }

    getDataFromServer() {
        function dataFromServerHandler() { // TODO function definitions outside of loop?
            if (this.readyState == 4) {
                if (this.status == 200) {
                    if (this.responseText != null) {
                        const o = JSON.parse(this.response)
                        const deviceDataFromServer = o
                        const vppOverview = this.myParameters[0]
                        vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer,this.myParameters[1])
                    } else alert("Communication error: No data received")
                } else alert("Communication error: " + this.statusText)
            }
        }
        var headers = {"Content-Type" : "application/json"}
        Util.sendRequestToServer("/vpp/default","GET",null,headers,dataFromServerHandler,[this,"default"])
    }

    postUpdateOfDeviceDataFromServer(deviceDataFromServer, groupId) {
        var keys = Object.keys(deviceDataFromServer)
        for (let i = 0; i<keys.length; i++) {
            var deviceId = keys[i]
            var device = deviceDataFromServer[deviceId]
            var uniqueId = DeviceUI.createDeviceIndentifier(groupId,deviceId)

            const isInServerDataAndIsInLocalDataAndRequestedToStop = (uniqueId in this.deviceSimulatorsToRender) && (this.deviceSimulatorsToRender[uniqueId].getSimulationState()=="passive")
            if (isInServerDataAndIsInLocalDataAndRequestedToStop) {
                this.deviceSimulatorsToRender[uniqueId].stop()
            }

            const isInServerDataButIsNotInLocalData = !(uniqueId in this.deviceSimulatorsToRender)
            if(isInServerDataButIsNotInLocalData) {
                this.deviceSimulatorsToRender[uniqueId] = new DeviceUI(deviceId,groupId)
            }

            var dataDescription = device.description
            if(dataDescription == "temperature") {// data is available
                var dataValue = device.value.value
                
                this.deviceSimulatorsToRender[uniqueId].setTemperature(dataValue)

                var currentHost = device.value.currentHost
                this.deviceSimulatorsToRender[uniqueId].setCurrentHost(currentHost)
            }
        }

        // passivate those DeviceUI that are stopped at Server
        for (const key of Object.keys(this.deviceSimulatorsToRender)) {
            var parsedDeviceIdentifier = (DeviceUI.parseDeviceIdentifier(key))

            const isInLocalDataButNotInServerDataAndGroupMatchesAndStopClicked = !(deviceDataFromServer.hasOwnProperty(parsedDeviceIdentifier.deviceId)) && (parsedDeviceIdentifier.groupId == groupId) && (this.deviceSimulatorsToRender[key].simulationState == "passive")
            if (isInLocalDataButNotInServerDataAndGroupMatchesAndStopClicked) { 
                this.deviceSimulatorsToRender[key].simulationState = "noPlot"
            }
        }
    }

    plot() {
        this.plotDeviceList()
    }

    plotDeviceList() { 
        this.tableCreate();
    
    }

    tableCreate() {

        this.htmlToDisplayDeviceList.innerHTML = "";
        const tbl = document.createElement('table');
        this.htmlToDisplayDeviceList.appendChild(tbl);
        tbl.setAttribute('id','deviceTable')
          
        tbl.style.width = '100px';
        tbl.style.border = '1px solid black';
      
        const tr = tbl.insertRow();
        var myHeaders = ["id","chargeStatus", "capacity", "host","", ""]
        for (let i = 0; i< myHeaders.length; i++) {
            const th = document.createElement('th')
            th.appendChild(document.createTextNode(myHeaders[i]));
            tr.appendChild(th)
        }
        
        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (let i = 0; i < keys.length; i++) {
            var deviceSimulatorToRender = this.deviceSimulatorsToRender[keys[i]]
            const tr = tbl.insertRow();
            tr.setAttribute("id","tr:"+deviceSimulatorToRender.deviceId)
            tr.setAttribute("class","deviceRow")
            if (deviceSimulatorToRender.getSimulationState() != "noPlot")  {
                const tdId = tr.insertCell();
                tdId.appendChild(document.createTextNode(deviceSimulatorToRender.deviceId))
                
                const tdChargeStatus = tr.insertCell();
                const divChargeStatus = document.createElement('div')
                divChargeStatus.setAttribute('id',deviceSimulatorToRender.deviceId)
                divChargeStatus.setAttribute('class',"bh-batteryWidget")
                
                const batteryWidget = new BatteryWidget(divChargeStatus)
                batteryWidget.setBatteryValue(deviceSimulatorToRender.temperature*100)
                tdChargeStatus.appendChild(divChargeStatus)
                tr.appendChild(tdChargeStatus)

                const tdCapacity = tr.insertCell();
                tdCapacity.appendChild(document.createTextNode(deviceSimulatorToRender.capacity))

                const tdHost = tr.insertCell();
                tdHost.appendChild(document.createTextNode(deviceSimulatorToRender.currentHost))
                
                const tdDetails = tr.insertCell();
                const detailsLink = document.createElement("a");
                detailsLink.innerHTML = "details"
                detailsLink.setAttribute("href","vpp/device/"+deviceSimulatorToRender.groupId+"/"+deviceSimulatorToRender.deviceId+"/details")
                tdDetails.appendChild(detailsLink)

                const tdStop = tr.insertCell();
                const stopButton = document.createElement("button");
                stopButton.innerHTML = "stop"
                stopButton.setAttribute("id","stop_"+deviceSimulatorToRender)
                stopButton.setAttribute("class","stopButton")
                
                stopButton.device = deviceSimulatorToRender
                stopButton.onclick = function(event) {
                    event.currentTarget.device.stop()
                }
                tdStop.appendChild(stopButton)
            } else { // noPlot
            }  
        }
      }
}