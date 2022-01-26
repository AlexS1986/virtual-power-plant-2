$(document).ready(function () {
    'use strict';
    //paper.install(window);
    //paper.setup(document.getElementById('vppOverviewCanvas'));
    //paper.view.draw();

    const defaultGroupName = "default"
    const deviceSimulators = [];
    const vppOverview = new VPPOverview(paper,deviceSimulators,document.getElementById('deviceList'))
    
    //new BatteryWidget($('.bh-batteryWidget'))
    //new BatteryWidget($('#'+'test'))

    var addButton = document.getElementById('addButton')
    var removeButton = document.getElementById('removeButton')

    addButton.onclick = function (event) {
        vppOverview.addDeviceSimulator()
    }

    removeButton.onclick = function(event) {
        vppOverview.removeDeviceSimulator()
    }

    /*var tool = new Tool();
    tool.onMouseDown = function (event) {
        switch (event.event.which) {
            case 1:
                console.log("Left Mouse button clicked.");
                break;
            case 2: 
                vppOverview.addDeviceSimulator()
                console.log("Middle Mouse button clicked.")
                break
            case 3: // stop first deviceSimulator with status active
                vppOverview.removeDeviceSimulator()
                console.log("Right Mouse button clicked.")
                break
            default:
                console.log("Strange mouse.")
        }
    } */

    var time = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    var desiredPowers = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    var form = document.forms.namedItem("desiredPower");
    var totalPowerHtmlElement = document.getElementById('totalPower')
    var totalPowerBoard = new TotalPowerBoard(time,desiredPowers,totalPowerHtmlElement,form)
    
    function getTimeString(offset) {
        let date = new Date();
        date.setSeconds(date.getSeconds()+offset)
        date.setHours(date.getHours())

        let dateString = date.toISOString().split("T")[0]
        let timeString = date.toISOString().split("T")[1].substring(0,8)
        return dateString+" "+timeString;
    }

    

    var deleteCounter = 1
    function refresh() {
        // update and plot vpp overview
        function dataFromServerHandler() { // TODO function definitions outside of loop?
            if (this.readyState == 4) {
                if (this.status == 200) {
                    if (this.responseText != null) {
                        var o = JSON.parse(this.response)
                        if(Object.keys(o).length === 0 && o.constructor === Object) { // test if response is an empty object       
                        } else {
                            var deviceDataFromServer = o
                            vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer,"default")
                            // handle results from readside
                            /*var deviceDataFromServer = o.devicesAndTemperatures
                            vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer)
                            vppOverview.plot()*/
                        }
                        vppOverview.plot()
                        /*var deviceDataFromServer = o.devicesAndTemperatures
                        vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer)
                        vppOverview.plot() */
                    } else alert("Communication error: No data received")
                } else alert("Communication error: " + this.statusText)
            }
        }
        var headers = {"Content-Type" : "application/json"}
        //Util.sendRequestToServer("/devicesAndTemperatures","GET",null,headers,dataFromServerHandler) // from DB
        Util.sendRequestToServer("/group/default","GET",null,headers,dataFromServerHandler,"default")

        
        let vppId = "default";
        let after = getTimeString(-12)
        let before = getTimeString(-2);
        //const deviceSimulator = new DeviceSimulator(" "," ").sendRandomMessageToServer()
        totalPowerBoard.getDataFromServer(vppId,before,after)
        //after = before
        //totalPowerBoard.sendRandomMessageToServer()
        //totalPowerBoard.getDataFromServer()
        totalPowerBoard.plotTotalPowerBoard()

        // clean up database, TODO do this serverside?
        if(deleteCounter % 5 == 0 ) {
            Util.sendRequestToServer("/deleteEnergyDeposits","POST",JSON.stringify({"before": after}),{"Content-Type" : "application/json"});
        }
        deleteCounter = deleteCounter +1;


    }
    const intervalId = setInterval(refresh, 1 * 2000) // interval in which the main view is refreshed

});