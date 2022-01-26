kubectl config set-context --current --namespace=iot-system-1
#kubectl delete service readside-service
sed -i 's/replicas: 1/replicas: 0/g' kubernetes/readside.yml
kubectl apply -f kubernetes/readside.yml
sbt -Ddocker.username=alexs1986 docker:publish
sed -i 's/replicas: 0/replicas: 1/g' kubernetes/readside.yml
kubectl apply -f kubernetes/readside.yml
sleep 10s
#kubectl expose deployment readside --type=LoadBalancer --name=readside-service
sleep 1s