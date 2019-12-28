FROM azul/zulu-openjdk-alpine:11

WORKDIR /opt/strumbot

VOLUME /etc/strumbot

COPY build/install/strumbot/strumbot.jar .

CMD [ "java", "-jar", "strumbot.jar" ]
