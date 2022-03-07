$(document).ready(function () {
    'use strict';
    
    const defaultGroupName = "default"
    const deviceSimulators = [];

    var depositCounter = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    var desiredTotalEnergyDeposits = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    var form = document.forms.namedItem("desiredTotalEnergyOutput");
    var totalEnergyHtmlElement = document.getElementById('totalEnergyOutputPlot')
    var totalEnergyOutputBoard = new TotalEnergyOutputBoard(depositCounter,desiredTotalEnergyDeposits,totalEnergyHtmlElement,form, defaultGroupName)

    var addButton = document.getElementById('addButton')
    var removeButton = document.getElementById('removeButton')
    addButton.onclick = function (event) {
        vppOverview.addDeviceSimulator()
    }

    removeButton.onclick = function(event) {
        vppOverview.removeDeviceSimulator()
    }

    const vppOverview = new VPPOverview(paper,deviceSimulators,document.getElementById('deviceList'))
    
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

        let vppId = "default";
        let after = getTimeString(-12)
        let before = getTimeString(-2);
        totalEnergyOutputBoard.getDataFromServer(vppId,before,after)
        totalEnergyOutputBoard.plot()
        
        vppOverview.getDataFromServer()
        vppOverview.plot() 
        
        // clean up database, TODO do this serverside?
        if(deleteCounter % 5 == 0 ) {
            Util.sendRequestToServer("/vpp/"+defaultGroupName+"/energies/delete","POST",JSON.stringify({"before": after}),{"Content-Type" : "application/json"})
        }
        deleteCounter = deleteCounter +1;

    }
    const intervalId = setInterval(refresh, 1 * 2000) // interval in which the main view is refreshed
});