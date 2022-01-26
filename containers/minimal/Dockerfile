# Preprocessing a minimal JRE distribution
FROM alpine as packager

ENV JAVA_MINIMAL="/opt/java-minimal"

RUN apk add openjdk17-jdk
RUN apk add openjdk17-jmods
RUN apk add binutils

RUN jlink \
    --verbose \
    --add-modules java.base,java.logging,java.xml,jdk.crypto.ec \
    --compress 2 \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --output "$JAVA_MINIMAL"

# Build our image with the minimal JRE
FROM alpine

WORKDIR /opt/strumbot

ENV JAVA_HOME=/opt/java-minimal
ENV PATH="$PATH:$JAVA_HOME/bin"

COPY build/release/strumbot/strumbot.jar .

COPY --from=packager "$JAVA_HOME" "$JAVA_HOME"

RUN apk --no-cache add ca-certificates

CMD [ \
 "java", \
 "-Xmx256m", \
 "-XX:+ShowCodeDetailsInExceptionMessages", \
 "-XX:+CrashOnOutOfMemoryError", \
 "-jar", \
 "strumbot.jar" \
]
