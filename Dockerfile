FROM azul/zulu-openjdk-alpine:11

WORKDIR /opt/strumbot

COPY build/install/strumbot/strumbot.jar .

CMD [ "java", "-jar", "strumbot.jar" ]
