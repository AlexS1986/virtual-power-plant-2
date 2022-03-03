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

        function refresh() {
            function dataFromServerHandler() { // TODO function definitions outside of loop?
                if (this.readyState == 4) {
                    if (this.status == 200) {
                        if (this.responseText != null) {
                            var deviceDataFromServerJson = JSON.parse(this.response)
                            
                            //set charge status in percent
                            const data = deviceDataFromServerJson.data
                            const dataDiv = document.getElementById("charge-status-div")
                            dataDiv.innerHTML = data*100.0
                            console.log("Hi")
                        } else alert("Communication error: No data received")
                    } else alert("Communication error: " + this.statusText)
                }
            }

            var headers = {}
            const deviceId = document.getElementById("release-manual-button").getAttribute("deviceId")
            const groupId = document.getElementById("release-manual-button").getAttribute("groupId")

            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId,"GET",null,headers,dataFromServerHandler)
        }

        const intervalId = setInterval(refresh, 1 * 2000)
    }
)