FROM maven:3.6-adoptopenjdk-11

COPY . /root/

WORKDIR /root

RUN mvn install