#!/bin/bash
## This script deploys the twin processes of the twin microservice
kubectl config set-context --current --namespace=iot-system-1
sed -i 's/replicas: 2/replicas: 0/g' kubernetes/twin.yml
kubectl apply -f kubernetes/twin.yml
sbt -Ddocker.username=alexs1986 docker:publish 
sed -i 's/replicas: 0/replicas: 2/g' kubernetes/twin.yml
kubectl apply -f kubernetes/twin.yml
#expose service manually if desired
#kubectl delete service iot-system-service
#kubectl expose deployment iot-system --type=LoadBalancer --name=iot-system-service
sleep 1m

## Run tests
export http_port=$(kubectl get service | grep "iot-system-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}') #https://www.cyberciti.biz/faq/grep-regular-expressions/
#echo $http_port

# test tracking, record and retrieve data
echo "<<< Track Device >>>"
echo $(curl -v -XPOST http://192.168.49.2:$http_port/twin/track-device -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')
echo "<<< Get State >>>"
echo $(curl -XGET http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')
echo "<<< Post and Get New State >>>"
echo $(curl -XPOST http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2", "capacity" : 100, "chargeStatus" : 0.8, "deliveredEnergy" : 0, "deliveredEnergyDate":"2022-02-22 16:02:15"}')
echo $(curl -v -XGET http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')
# for group
echo "<<< Get Group Data >>>"
echo $(curl -v -XGET http://192.168.49.2:$http_port/twin/data-all -H "Content-Type: application/json" --data '{"groupId":"group2"}')
# untrack
echo $(curl -v -XPOST http://192.168.49.2:$http_port/twin/untrack-device -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device2"}')

#echo $(curl -XPOST http://192.168.49.2:$http_port/twin/charge-status -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device1", "desiredChargeStatus" : 1.0}')
#echo $(curl -v -XPOST http://192.168.49.2:$http_port/twin/untrack-device -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device1"}')