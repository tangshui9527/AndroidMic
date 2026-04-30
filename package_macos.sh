#!/bin/bash
set -e

APP_NAME="AndroidMic"
BINARY_NAME="android-mic"
BUILD_DIR="RustApp/target/release"
BUNDLE_DIR="${APP_NAME}.app"

echo "Building ${APP_NAME} in release mode..."
cd RustApp
cargo build --release
cd ..

echo "Creating ${BUNDLE_DIR}..."
rm -rf "${BUNDLE_DIR}"
mkdir -p "${BUNDLE_DIR}/Contents/MacOS"
mkdir -p "${BUNDLE_DIR}/Contents/Resources"

echo "Copying binary and Info.plist..."
cp "${BUILD_DIR}/${BINARY_NAME}" "${BUNDLE_DIR}/Contents/MacOS/"
cp "RustApp/res/macos/Info.plist" "${BUNDLE_DIR}/Contents/"

echo "Copying resources..."
cp -r "RustApp/res" "${BUNDLE_DIR}/Contents/Resources/"

echo "Setting permissions..."
chmod +x "${BUNDLE_DIR}/Contents/MacOS/${BINARY_NAME}"

echo "Done! ${BUNDLE_DIR} created successfully."
