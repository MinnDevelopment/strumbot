FROM openjdk:17.0.1-slim

WORKDIR /opt/strumbot

COPY build/release/strumbot/strumbot.jar .

CMD [ "java", "-Xmx256m", "-XX:+ShowCodeDetailsInExceptionMessages", "-XX:+CrashOnOutOfMemoryError", "-jar", "strumbot.jar" ]
