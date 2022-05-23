#!/bin/bash
#call as ./performance.sh not as sh performance.sh https://stackoverflow.com/questions/2462317/bash-syntax-error-redirection-unexpected
kubectl config set-context --current --namespace=iot-system-1
export http_port_frontend=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')

numberOfDevices=10



# check number of replicas and continue if number is 2
echo "<<< Wait until only two twin pod replicas are running >>>"
numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
while [ "$numberTwinPods" -eq 3 ]
do
  echo "Current number of twin pods is 3. Wait until only two are running."
  sleep 30s
  numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
done
echo "Current number of twin pod replicas is $numberTwinPods"

currentCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')
targetCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '2p')
echo "Current CPU load $currentCPULoad%. Target CPU load $targetCPULoad%"

experimentTargetValue=$(expr $targetCPULoad*1.2 | bc)
echo "<<< Increase number of device twins to the point that current CPU load is $experimentTargetValue% >>>"
k=0
while (( $(echo "$experimentTargetValue > $currentCPULoad" |bc -l) )) 
do
    for j in `seq $(($k*$numberOfDevices)) $((($k+1)*$numberOfDevices-1))`
    do
        deviceName="device$j"
        startString='simulator/default/'$deviceName'/start'
        echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/$startString)
    done
    k=$(($k+1))
    sleep 10s
    currentCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')
    echo "Current CPU load $currentCPULoad%. Experiment target CPU load $experimentTargetValue%"
done
currentCPULoadStart=$currentCPULoad

sleep 30s

# check if new replica has been created and devices have been moved to all replicas
numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded=false
if [[ "$numberTwinPods" -eq 3 ]]; then
    numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded=true
fi
echo $numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded
echo "<<< Number of twin replicas is now $numberTwinPods. Expected 3. >>>"

firstPod=$(kubectl get pod | grep -o "twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}" | sed -n '1p')
secondPod=$(kubectl get pod | grep -o 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}' | sed -n '2p')
thirdPod=$(kubectl get pod | grep -o 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}' | sed -n '3p')

# requesting data for all devices
response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
time_starttransfer="$(echo $response | grep -Eo 'time_starttransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_starttransfer="$(echo "$time_starttransfer" | sed 's/,/./;')"

time_pretransfer="$(echo $response | grep -Eo 'time_pretransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_pretransfer="$(echo "$time_pretransfer" | sed 's/,/./;')"

timeAtServerThisRequest=`echo|awk -v a1=$time_starttransfer -v a2=$time_pretransfer '{print a1-a2}'`

firstPodHasDevices=false
if  grep -q "$firstPod" <<< "$response"; then
    firstPodHasDevices=true
    echo "First pod has devices: $firstPodHasDevices"
fi

secondPodHasDevices=false
if  grep -q "$secondPod" <<< "$response"; then
    secondPodHasDevices=true
    echo "Second pod has devices: $secondPodHasDevices"
fi

thirdPodHasDevices=false
if  grep -q "$thirdPod" <<< "$response"; then
    thirdPodHasDevices=true
    echo "Third pod has devices: $thirdPodHasDevices"
fi


echo "<<< Stopping devices >>>"
for i in `seq 0 $(($numberOfDevices*$k))`
do
    deviceName="device$i"
    stopString='vpp/device/default/'$deviceName
    echo $(curl -s -XDELETE http://192.168.49.2:$http_port_frontend/$stopString) 
done

#check if one replica has been stopped after decreased below target value
currentCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')
echo "<<< Checking if replica is stopped after CPU load has been decreased below target CPU load.>>>"
while (( $(echo "$targetCPULoad < $currentCPULoad" |bc -l) )) 
do
    currentCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')
    echo "Current CPU load $currentCPULoad%. Target CPU load $targetCPULoad%"
    sleep 10s
done
sleep 6m

afterCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')
#afterCPULoad=$(kubectl get hpa | grep  "twin-horizontal-scaler" | grep -o '[[:digit:]]*%/[[:digit:]]*%' | grep -o '[[:digit:]]*' | sed -n '1p')

numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
numberTwinPodsIsTwoAfterDecreasedLoad=false
if [[ "$numberTwinPods" -eq 2 ]]; then
    #echo $numberTwinPods
    numberTwinPodsIsTwoAfterDecreasedLoad=true
fi
echo "Number of twin pods after decreased load is $numberTwinPods. Expected 2."

echo "<<< Checking Elasticity >>>"
total=true
echo "Number of devices until additional pod is started: $((($k+1)*$numberOfDevices))."
echo " "
echo "CPU Load at which no further devices are created and test is started: $currentCPULoadStart%"
echo " "
loadStartOk=false
if (( $(echo "$currentCPULoad > $targetCPULoad" |bc -l) )); then
  loadStartOk=true
fi
if [[ "$total" == "true" ]] && [[ "$loadStartOk" == "true" ]]; then
    total=true
else
    total=false
fi
echo "Number of twin replicas is increased after target CPU load is exceeded: $numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded. Expected true."
echo " "
if [[ "$total" == "true" ]] && [[ "$numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "First pod has devices scaling up: $firstPodHasDevices. Expected true"
echo " "
if [[ "$total" == "true" ]] && [[ "$firstPodHasDevices" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Second pod has devices scaling up: $secondPodHasDevices. Expected true"
echo " "
if [[ "$total" == "true" ]] && [[ "$secondPodHasDevices" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Third pod has devices after scaling up: $thirdPodHasDevices. Expected true"
echo " "
if [[ "$total" == "true" ]] && [[ "$thirdPodHasDevices" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Number of twin replicas is decreased after current CPU load has fallen below target CPU: $numberTwinPodsIsTwoAfterDecreasedLoad. Expected true."
echo " "
if [[ "$total" == "true" ]] && [[ "$numberTwinPodsIsTwoAfterDecreasedLoad" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "CPU Load after all devices are stopped and 6 minutes waiting period has passed: $afterCPULoad %"
echo " "
loadAfterOk=false
if (( $(echo "$targetCPULoad > $afterCPULoad" |bc -l) )); then
  loadAfterOk=true
fi
if [[ $total && $loadAfterOk ]]; then
    total=true
else
    total=false
fi

#total=$numberTwinPodsIsThreeAfterTargetCPULoadIsExceeded && $firstPodHasDevices && $secondPodHasDevices && $thirdPodHasDevices && $numberTwinPodsIsTwoAfterDecreasedLoad
if  $total; then
    echo "<<< TEST PASSED >>>"
else
    echo "<<< TEST FAILED >>>"
fi;
