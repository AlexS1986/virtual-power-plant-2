#!/bin/bash
kubectl config set-context --current --namespace=iot-system-1
#kubectl delete service iot-system-service
sed -i 's/replicas: 2/replicas: 0/g' kubernetes/akka-cluster.yml
kubectl apply -f kubernetes/akka-cluster.yml
sbt -Ddocker.username=alexs1986 docker:publish 
sed -i 's/replicas: 0/replicas: 2/g' kubernetes/akka-cluster.yml
kubectl apply -f kubernetes/akka-cluster.yml
sleep 1m
#kubectl expose deployment iot-system --type=LoadBalancer --name=iot-system-service
sleep 1s
export http_port=$(kubectl get service | grep "iot-system-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}') #https://www.cyberciti.biz/faq/grep-regular-expressions/
echo $http_port
#test
#echo $(curl -XGET http://192.168.49.2:$http_port/hostname -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')


#record and retrieve temperature
#test
echo $(curl -XGET http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')

#test
echo $(curl -XPOST http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2", "capacity" : 100, "chargeStatus" : 80, "deliveredEnergy" : 0, "deliveredEnergyDate":"2022-02-22 16:02:15"}')

echo $(curl -v -XGET http://192.168.49.2:$http_port/twin/data -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')


echo $(curl -v -XPOST http://192.168.49.2:$http_port/twin/track-device -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')
echo $(curl -v -XGET http://192.168.49.2:$http_port/twin/data-all -H "Content-Type: application/json" --data '{"groupId":"group2"}')

#echo $(curl -XPOST http://192.168.49.2:$http_port/twin/charge-status -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device1", "desiredChargeStatus" : 1.0}')

#echo $(curl -XGET http://10.111.136.207:30080/temperature -H "Content-Type: application/json" --data '{"groupId":"group2","deviceId": "device2"}')

#echo $(curl -v -XPOST http://192.168.49.2:$http_port/twin/untrack-device -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device1"}')