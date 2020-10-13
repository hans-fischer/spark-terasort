docker run -it \
    -v $PWD:/root:rw \
    -w /root \
    maven:3.6-adoptopenjdk-11 \
    mvn install