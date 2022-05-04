#!/bin/bash
#call as ./resilience.sh not as sh resilience.sh https://stackoverflow.com/questions/2462317/bash-syntax-error-redirection-unexpected
kubectl config set-context --current --namespace=iot-system-1
export http_port_frontend=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')

numberOfDevices=100
timeAllowedPerRequest=5.0


echo "<<< Starting $numberOfDevices devices >>>"
notAllFound=1
while (( $(echo "$notAllFound") ))
do
    notAllFound=0
    response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
    #echo $response
    for i in `seq 0 $(($numberOfDevices-1))`
    do
        deviceName="device$i"
        if  ! grep -q "$deviceName" <<< "$response"; then
            echo "Response does not contain $deviceName try to start again."
            notAllFound=1
            deviceName="device$i"
            startString='simulator/default/'$deviceName'/start'
            echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/$startString)
        fi
    done
    sleep 15s
done


#for i in `seq 0 $(($numberOfDevices-1))`
#do
#    deviceName="device$i"
#    startString='simulator/default/'$deviceName'/start'
#    echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/$startString)
#done
#echo " "
# wait for devices to be launched
#sleep 5s

#kubectl config set-context --current --namespace=iot-system-1
numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}') #https://www.cyberciti.biz/faq/grep-regular-expressions/
firstPod=$(kubectl get pod | grep -o "twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}" | sed -n '1p')
secondPod=$(kubectl get pod | grep -o 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}' | sed -n '2p')
thirdPod=""
if [[ "$numberTwinPods" -eq 3 ]]; then
    thirdPod=$(kubectl get pod | grep -o 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}' | sed -n '3p')
fi

response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
#echo $response
countDevicesFirstPod="$(echo $response | grep -o -i "$firstPod" | wc -l)" # https://www.tecmint.com/count-word-occurrences-in-linux-text-file/
echo "<<< $firstPod hosts $countDevicesFirstPod Devices. >>> "


# stop the process running the first twin microservice instance
echo "Stopping process in $firstPod"
killJavaProcess=$(kubectl exec $firstPod -- kill 1)

sleep 0.1s
START=$(date +%s.%N)
echo "BEFORE $(date +%s.%N)"
response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
time_starttransfer="$(echo $response | grep -Eo 'time_starttransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_starttransfer="$(echo "$time_starttransfer" | sed 's/,/./;')"

time_pretransfer="$(echo $response | grep -Eo 'time_pretransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_pretransfer="$(echo "$time_pretransfer" | sed 's/,/./;')"

timeAtServerThisRequestBeforeK8sNoticesFailure=`echo|awk -v a1=$time_starttransfer -v a2=$time_pretransfer '{print a1-a2}'`

# check if Kubernetes displays an error for the pod
echo "<<< Waiting for error message >>>"

firstPodStatus=$(kubectl get pod | grep "$firstPod")
checkError="$(echo $firstPodStatus | grep -E 'Error|CrashLoopBackOff')"
errorMessagePresent=false
while [ -z "$checkError" ]
do
    #sleep 0.1s
    firstPodStatus=$(kubectl get pod | grep "$firstPod")
    #echo "$firstPodStatus for $firstPod"
    checkError="$(echo $firstPodStatus | grep -E 'Error|CrashLoopBackOff')"
    END=$(date +%s.%N)    
done
echo "AFTER $(date +%s.%N)"
echo "<<< Continuing after error message >>>"
timeUntilErrorMessage=$(echo "$END - $START" | bc |  sed 's/^\./0./')
errorMessagePresent=true


#sleep 0.2s
# requesting data for all devices
response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
time_starttransfer="$(echo $response | grep -Eo 'time_starttransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_starttransfer="$(echo "$time_starttransfer" | sed 's/,/./;')"

time_pretransfer="$(echo $response | grep -Eo 'time_pretransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
time_pretransfer="$(echo "$time_pretransfer" | sed 's/,/./;')"

timeAtServerThisRequestAfterK8sNoticesFailure=`echo|awk -v a1=$time_starttransfer -v a2=$time_pretransfer '{print a1-a2}'`
# check if response time is within limits
#timeOk=$(echo "$timeAllowedPerRequest > $timeAtServerThisRequestAfterK8sNoticesFailure" |bc -l)
timeOk=false
if (( $(echo "$timeAllowedPerRequest > $timeAtServerThisRequestAfterK8sNoticesFailure" |bc -l) )); then
  timeOk=true
fi


# check if all device twins are moved to other twin instances
if [[ $response != *"$firstPod"* ]];then
    serverResponseContainsStoppedPodAsHost=false
else 
    serverResponseContainsStoppedPodAsHost=true
fi

# check if pod has been restarted and is running again after a while
sleep 1.5m
firstPodStatus=$(kubectl get pod | grep "$firstPod")
checkRunning="$(echo $firstPodStatus | grep -E 'Running')"
if [ -z "$checkRunning" ]; then
    stoppedPodIsRunningAgain=false
else
    stoppedPodIsRunningAgain=true
fi

# check if device twins have been moved to new instance
responseAfter=$(curl -s -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
#echo $responseAfter
if [[ $responseAfter != *"$firstPod"* ]];then
    serverResponseContainsStoppedPodAsHostAfterRestart=false
else 
    serverResponseContainsStoppedPodAsHostAfterRestart=true
fi


#################################################
#       Soft failure: stopping instances        #
#################################################

deleteResponseFirst=$(kubectl delete pod $firstPod)
deleteResponseSecond=$(kubectl delete pod $secondPod)

sleep 1.5m
responseAfterAfter=$(curl -s -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
#echo $responseAfterAfter
if [[ $responseAfterAfter != *"$firstPod"* ]];then
    serverResponseContainsDeletedFirstPod=false
else 
    serverResponseContainsDeletedFirstPod=true
fi
if [[ $responseAfterAfter != *"$secondPod"* ]];then
    serverResponseContainsDeletedSecondPod=false
else 
    serverResponseContainsDeletedSecondPod=true
fi
numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
if [[ "$numberTwinPods" -ge 2 ]]; then
    numberTwinPodsGE2=true
else
    numberTwinPodsGE2=false
fi

responseAfterSoftDelete=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
allDevicesHostedAfterSoftDelete=true
for i in `seq 0 $(($numberOfDevices-1))`
do
    deviceName="device$i"
    if  ! grep -q "$deviceName" <<< "$responseAfterSoftDelete"; then
        allFoundAfterSoftDelete=false
        break
    fi
done


echo " "
echo "<<< Stopping devices >>>"
for i in `seq 0 $(($numberOfDevices-1))`
do
    deviceName="device$i"
    stopString='vpp/device/default/'$deviceName
    echo $(curl -s -XDELETE http://192.168.49.2:$http_port_frontend/$stopString) 
done
echo " "

echo "<<< Checking Resilience >>>"
echo " "
echo "<<< Pod $firstPod hosts $countDevicesFirstPod Devices before java process being stopped >>>"
total=true
echo "Time required to compute server response directly after twin instance stopped "$timeAtServerThisRequestBeforeK8sNoticesFailure"s"
echo " "
echo "Time required to compute server response after K8s notices failure "$timeAtServerThisRequestAfterK8sNoticesFailure"s allowed" $timeAllowedPerRequest"s. Test passed: $( (( timeOk )) ) "
echo " "
if (( timeOk )); then
  timeOk=true
fi
if [[ $total ]] && [[ $timeOk ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Error message displayed for stopped pod: $errorMessagePresent after $timeUntilErrorMessage s. Expected: true"
echo " "
if [[ "$total" == "true" ]] && [[ "$errorMessagePresent" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Server response contains stopped pod as host of devices: $serverResponseContainsStoppedPodAsHost. Expected: false"
echo " "
if [[ "$total" == "true" ]] && [[ "$serverResponseContainsStoppedPodAsHost" == "false" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Microservice has been restarted : $stoppedPodIsRunningAgain. Expected: true"
echo " "
if [[ "$total" == "true" ]] && [[ "$stoppedPodIsRunningAgain" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "After restart device twins are moved to restarted instance of twin microservice: $serverResponseContainsStoppedPodAsHostAfterRestart. Expected: true"
echo " "
if [[ "$total" == "true" ]] && [[ "$serverResponseContainsStoppedPodAsHostAfterRestart" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "<<< Checking after soft delete >>"
echo " "
echo "First deleted pod hosts Devices: $serverResponseContainsDeletedFirstPod. Expected false. "
echo " "
if [[ "$total" == "true" ]] && [[ "$serverResponseContainsDeletedFirstPod" == "false" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Second deleted pod hosts Devices: $serverResponseContainsDeletedSecondPod. Expected false. "
echo " "
if [[ "$total" == "true" ]] && [[ "$serverResponseContainsDeletedSecondPod" == "false" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "Number of pods after soft delete is at least two: $numberTwinPodsGE2. Expected true"
if [[ "$total" == "true" ]] && [[ "$numberTwinPodsGE2" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
echo "All Devices are hosted again after soft delete: $allDevicesHostedAfterSoftDelete. Expected true."
if [[ "$total" == "true" ]] && [[ "$allDevicesHostedAfterSoftDelete" == "true" ]]; then
    total=true
else
    total=false
fi
echo $total
#$total=$serverResponseContainsStoppedPodAsHostAfterRestart && [[ !$serverResponseContainsStoppedPodAsHost ]] &&  $errorMessagePresent  && (( timeOk )) &&  $stoppedPodIsRunningAgain && [[ !$serverResponseContainsDeletedFirstPod ]] && [[ !$serverResponseContainsDeletedSecondPod ]] && $numberTwinPodsGE2

if  $total; then
    echo "<<< TEST PASSED >>>"
else
    echo "<<< TEST FAILED >>>"
fi;





