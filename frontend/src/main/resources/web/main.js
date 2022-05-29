/**
 * starts the dynamic GUI
 */
$(document).ready(function () {
    'use strict';
    
    /**
     * the DeviceGroup thats beeing displayed in the GUI
     */
    const defaultGroupName = "default"

    
    /**
     * set up the TotalEnergyOutputBoard
     */
    var depositCounter = [-10, -9, -8, -7, -6, -5, -4, -3, -2, -1]; //[1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    var desiredTotalEnergyDeposits = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    var form = document.forms.namedItem("desiredTotalEnergyOutput");
    var totalEnergyHtmlElement = document.getElementById('totalEnergyOutputPlot')
    var htmlElementThatHoldsDesiredTotalEnergyOutput = document.getElementById('desiredTotalEnergyOutputDiv')
    var htmlElementThatHoldsCurrentTotalEnergyOutput = document.getElementById('currentTotalEnergyOutputDiv')
    var totalEnergyOutputBoard = new TotalEnergyOutputBoard(depositCounter,desiredTotalEnergyDeposits,
        totalEnergyHtmlElement,form,htmlElementThatHoldsDesiredTotalEnergyOutput,
        htmlElementThatHoldsCurrentTotalEnergyOutput, defaultGroupName)

    /**
     * set up the VPPOverview
     */
    var addButton = document.getElementById('addButton')
    var removeButton = document.getElementById('removeButton')
    addButton.onclick = function (event) {
        vppOverview.addDeviceSimulator()
    }

    removeButton.onclick = function(event) {
        vppOverview.removeDeviceSimulator()
    }
    const initialDeviceSimulators = [];
    const vppOverview = new VPPOverview(initialDeviceSimulators,document.getElementById('deviceList'))
    

    /**
     * auxiliary function that allows to obtain the current time as a string
     * @param {*} offset 
     * @returns 
     */
    function getTimeString(offset) {
        let date = new Date();
        date.setSeconds(date.getSeconds()+offset)
        date.setHours(date.getHours())

        let dateString = date.toISOString().split("T")[0]
        let timeString = date.toISOString().split("T")[1].substring(0,8)
        return dateString+" "+timeString;
    }

   
    /**
     * define the periodic refresh of the GUI
     */
    var deleteCounter = 1
    function refresh() {

        // set up a trailing ten second time window in which energy output is aggregated for the GUI 
        let after = getTimeString(-12)
        let before = getTimeString(-2);
        totalEnergyOutputBoard.getDataFromServer(defaultGroupName,before,after)
        totalEnergyOutputBoard.plot()
        
        vppOverview.getDataFromServer()
        vppOverview.plot() 
        
        // clean up database
        if(deleteCounter % 5 == 0 ) {
            Util.sendRequestToServer("/vpp/"+defaultGroupName+"/energies/delete","POST",JSON.stringify({"before": after}),{"Content-Type" : "application/json"})
        }
        deleteCounter = deleteCounter +1;

    }
    refresh()
    const intervalId = setInterval(refresh, 1 * 2000) // interval in which the main view is refreshed
});