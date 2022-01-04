FROM alpine

WORKDIR /opt/strumbot

COPY build/release/strumbot/strumbot.jar .

RUN apk --no-cache add openjdk17-jre-headless
RUN apk --no-cache add ca-certificates

CMD [ "java", "-Xmx256m", "-XX:+ShowCodeDetailsInExceptionMessages", "-XX:+CrashOnOutOfMemoryError", "-jar", "strumbot.jar" ]
