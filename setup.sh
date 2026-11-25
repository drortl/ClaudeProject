#!/usr/bin/env bash
#
# Sets up this environment for Android development:
#   - JDK 17
#   - Android SDK (command-line tools, platform-tools, a build-tools version,
#     and a platform)
#   - Gradle
#
# Target: a Debian/Ubuntu-based Linux environment (CI runner, cloud sandbox,
# container, or WSL) with apt, curl and unzip available.
#
# Usage:
#   ./setup.sh
#
# Re-running is safe: each step is skipped if its target already exists.

set -euo pipefail

# ---- versions (bump here when needed) --------------------------------
ANDROID_CMDLINE_TOOLS_VERSION="11076708"   # commandlinetools-linux-*_latest.zip build id
ANDROID_PLATFORM_VERSION="34"              # android-34
ANDROID_BUILD_TOOLS_VERSION="34.0.0"
GRADLE_VERSION="8.9"

# ---- install locations -------------------------------------------------
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/android-dev}"
ANDROID_SDK_ROOT="$INSTALL_ROOT/sdk"
GRADLE_HOME="$INSTALL_ROOT/gradle-${GRADLE_VERSION}"
ENV_FILE="$INSTALL_ROOT/env.sh"

log() { printf '\n==> %s\n' "$1"; }

mkdir -p "$INSTALL_ROOT"

# ---- JDK 17 --------------------------------------------------------------
log "Checking JDK 17"
if command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -q '17\.'; then
    echo "JDK 17 already installed: $(javac -version 2>&1)"
else
    log "Installing JDK 17 (apt)"
    sudo apt-get update -y
    sudo apt-get install -y openjdk-17-jdk
fi

JAVA_HOME_DETECTED="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"

# ---- Android SDK command-line tools --------------------------------------
log "Checking Android SDK command-line tools"
CMDLINE_TOOLS_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
    echo "Android command-line tools already installed at $CMDLINE_TOOLS_DIR"
else
    log "Downloading Android command-line tools"
    TMP_ZIP="$(mktemp --suffix=.zip)"
    curl -fsSL -o "$TMP_ZIP" \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"

    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    TMP_EXTRACT="$(mktemp -d)"
    unzip -q "$TMP_ZIP" -d "$TMP_EXTRACT"
    rm -f "$TMP_ZIP"

    # The zip extracts to a top-level "cmdline-tools" folder; the sdkmanager
    # expects it at cmdline-tools/latest/.
    rm -rf "$CMDLINE_TOOLS_DIR"
    mv "$TMP_EXTRACT/cmdline-tools" "$CMDLINE_TOOLS_DIR"
    rm -rf "$TMP_EXTRACT"
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$CMDLINE_TOOLS_DIR/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# ---- Android SDK packages -------------------------------------------------
log "Accepting Android SDK licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

log "Installing Android SDK packages (platform-tools, platform ${ANDROID_PLATFORM_VERSION}, build-tools ${ANDROID_BUILD_TOOLS_VERSION})"
sdkmanager --install \
    "platform-tools" \
    "platforms;android-${ANDROID_PLATFORM_VERSION}" \
    "build-tools;${ANDROID_BUILD_TOOLS_VERSION}"

# ---- Gradle ----------------------------------------------------------------
log "Checking Gradle ${GRADLE_VERSION}"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "Gradle ${GRADLE_VERSION} already installed at $GRADLE_HOME"
else
    log "Downloading Gradle ${GRADLE_VERSION}"
    TMP_ZIP="$(mktemp --suffix=.zip)"
    curl -fsSL -o "$TMP_ZIP" \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    unzip -q "$TMP_ZIP" -d "$INSTALL_ROOT"
    rm -f "$TMP_ZIP"
fi

export PATH="$GRADLE_HOME/bin:$PATH"

# ---- persist environment for future shells --------------------------------
log "Writing environment file: $ENV_FILE"
cat > "$ENV_FILE" <<EOF
# Source this file to use the JDK/Android SDK/Gradle installed by setup.sh
export JAVA_HOME="$JAVA_HOME_DETECTED"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$CMDLINE_TOOLS_DIR/bin:\$ANDROID_SDK_ROOT/platform-tools:$GRADLE_HOME/bin:\$PATH"
EOF

log "Done"
echo "JAVA_HOME:        $JAVA_HOME_DETECTED"
echo "ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"
echo "Gradle:           $GRADLE_HOME/bin/gradle"
echo
echo "Add this to your shell profile, or run it in each new shell:"
echo "  source \"$ENV_FILE\""
