$(document).ready(function () {
        'use strict';
        const form = document.forms.namedItem("desiredChargeStatus");

        form.addEventListener('submit', function(ev) {
            const desiredChargeStatus = this[0].value / 100.0
            const deviceId = this.getAttribute("deviceId")
            const groupId = this.getAttribute("groupId")
            var headers = {"Content-Type" : "application/json"}
            //var data = JSON.stringify({"deviceId": deviceId,"groupId": groupId, "desiredChargeStatus" : desiredChargeStatus })
            var data = JSON.stringify({"desiredChargeStatus" : desiredChargeStatus })
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId + "/charge-status","POST",data,headers)
            //ev.currentTarget.attachedTotalPowerBoard.updateDesiredPower(this[0].value)
            //console.log("Desired power set to: "+this[0].value)
            ev.preventDefault()
        },false);

        var releaseManualControlButton = document.getElementById('release-manual-button')

        releaseManualControlButton.onclick = function (event) {
            const deviceId = event.currentTarget.getAttribute("deviceId")
            const groupId = event.currentTarget.getAttribute("groupId")

            var headers = {"Content-Type" : "application/json"}
            //var data = JSON.stringify({"deviceId": deviceId,"groupId": groupId, "desiredChargeStatus" : desiredChargeStatus })
            //var data = JSON.stringify({})
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId + "/charge-status","DELETE",null,headers)
            
        }
    }
)