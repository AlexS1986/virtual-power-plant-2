kubectl config set-context --current --namespace=simulator
#kubectl delete service readside-service
sed -i 's/replicas: 1/replicas: 0/g' kubernetes/simulator.yml
kubectl apply -f kubernetes/simulator.yml
sbt -Ddocker.username=alexs1986 docker:publish
sed -i 's/replicas: 0/replicas: 1/g' kubernetes/simulator.yml
kubectl apply -f kubernetes/simulator.yml
sleep 10s
#kubectl expose deployment readside --type=LoadBalancer --name=readside-service
sleep 1s