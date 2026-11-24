# Android release builder for the AVNIVO Translator APK.
# Pinned to the toolchain the project already uses: JDK 17, SDK 35, build-tools 35.0.0,
# NDK 27.0.12077973 (see android-app/app/build.gradle.kts), Gradle 8.10.2.
FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip
ARG GRADLE_VERSION=8.10.2

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    PATH=/opt/gradle-8.10.2/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.0:$PATH

RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl unzip zip jq ninja-build ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/android-sdk/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/${ANDROID_CMDLINE_TOOLS}" \
 && unzip -q /tmp/tools.zip -d /tmp/tools \
 && mv /tmp/tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm -rf /tmp/tools /tmp/tools.zip \
 && yes | sdkmanager --sdk_root=/opt/android-sdk --licenses > /dev/null \
 && sdkmanager --sdk_root=/opt/android-sdk \
      "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
      "ndk;27.0.12077973" "cmake;3.22.1"

RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && unzip -q /tmp/gradle.zip -d /opt \
 && rm /tmp/gradle.zip \
 && gradle --version

WORKDIR /workspace
