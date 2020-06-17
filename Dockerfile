FROM adoptopenjdk:14.0.1_7-jdk-hotspot

WORKDIR /opt/strumbot

COPY build/install/strumbot/strumbot.jar .

CMD [ "java", "-Xmx256m", "-XX:+ShowCodeDetailsInExceptionMessages", "-XX:+CrashOnOutOfMemoryError", "-jar", "strumbot.jar" ]
