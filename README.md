# Fire Truck GPS Bridge

Two small Android apps:

- **GPS Transmitter** runs on the Pixel phone, reads the hardware GPS, and broadcasts JSON over UDP port `42424`.
- **GPS Receiver** runs on the Fire tablet, receives those packets, and supplies them through Android's mock-location system.

## Fire tablet setup

1. Install `fire-receiver-debug.apk`.
2. Enable Developer Options.
3. Open **Select mock location app**.
4. Select **GPS Receiver**.
5. Connect the tablet to the Pixel hotspot.
6. Start GPS Receiver.

The receiver declares `android.permission.ACCESS_MOCK_LOCATION`, which is what makes it eligible for the mock-location picker on compatible Android/Fire OS versions.

## Pixel setup

1. Install `pixel-transmitter-debug.apk`.
2. Grant precise location and notification permissions.
3. Turn on the Pixel hotspot.
4. Open GPS Transmitter and press Start.

## Build

Open the root folder in Android Studio, or push it to GitHub. The included GitHub Actions workflow builds both debug APKs.

## Important

Mock locations are device-wide. Test Google Maps and IAmResponding before operational use, and disable battery optimization for both apps.
