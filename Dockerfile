FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip git ruby ruby-dev build-essential libyaml-dev zlib1g-dev \
      libvips42 libvips-dev \
    && rm -rf /var/lib/apt/lists/*

# Android cmdline-tools + platform 35 / build-tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && cd /tmp \
 && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o cmdtools.zip \
 && unzip -q cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools \
 && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm cmdtools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

WORKDIR /app
COPY Gemfile ./
RUN gem install bundler -N && bundle install --jobs 4
COPY . .

RUN chmod +x bin/build-apk android_template/gradlew

ENTRYPOINT ["ruby", "bin/build-apk"]
CMD ["--help"]
