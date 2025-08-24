package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/22
Github: https://github.com/incognitosushiroll/GNSSData.git

This java file is part of the GNSSData project. ChatGPT was used to help design the files in "res", "androidmanifest.xml", and any files that helped paint style.
This project is ladden with comments to 1) perform GPS Data gleaning and logging methods, and 2) teach the programmer further java skills!

Tools used for project:
1. Github repo
2. Android Studio
3. Google pixel phone + USB cable and Pixel 9 emulator
4. Used a .java file instead of a Kotlin file (deleted)

Files included:
- SensorListener.java
- EventLogger.java
- MainActivity.java

FOUR Desired GPS Measurements:
1. Barometer pressure
    -> units: hPa (hectopascals)
    -> Sensor.TYPE_PRESSURE
    -> measures ambient air pressure in hPA (1013 is sea-level)
2. Intertial (accelerometer or linear acceleration)
    -> units: x (right), y (up), z (out of screen)  m/s^s (device-centric)
    -> Include gravity with TYPE_LINEAR_ACCELERATION, removes gravity we use TYPE_ACCELEROMETER
3. Intertial (gyroscope)
    -> units: x (right), y (up), z (out of screen)  m/s^s (device-centric)
    -> Sensor.TYPE_GYROSCOPE
    -> Measures angular velocity around the X/Y/Z in rad/s (right-hand rule)
4. GNSS derived from raw (android.location * raw measurements where raw measurements are exposed per-satellite thanks to Android API 24+).
    - Pseudorange per satellite
        -> units: meters
        -> "The pseudorange is an approximation of the distance between a satellite and a GNSS receiver" (spirent)
        -> Provided with: p = (t_rx(GPS) - t_tx) * c
            where:
                   - t_rx(GPS) = receiver clock in GPS timescale = timeNanos - (fullBias + bias)
                   - t_tx = sate transmit time at code epoch = receivedSvTimeNanos + timeOffsetNanos
                   - c = speed of light (299, 792, 458 m/s) which is hard-coded below
    - Time-Differenced Carrier Phase (TDCP)
        -> units: delta range in meters, and optional rate in m/s
        -> Velocity estimation using displacement over a short time interval (mdps)
        -> Provided with:
            - Android's provided accumulated delta range (ADR) in meters since last reset for Sat vehicle (SV)
            - TCDP over an epoch is ΔADR = ADR_now − ADR_prev (meters)
            - Optional rate: ΔADR / Δt (m/s), where Δt is time between epochs (seconds)
            - Only valid if ADR_STATE_VALID is set, resets on cycle slips/loss of lock
     - For the constellation:
        Reference: https://developer.android.com/reference/android/location/GnssStatus
                        GPS = 1
                        Sbas = 2
                        Glonass = 3
                        Qzss = 4
                        Beidou = 5
                        Galileo = 6
                        IRNSS = 7

Java concepts - for new programmer:
- A java "class" (this file) extends AppCompatActivity so it can be used as a screen
- "Fields" are member variables that hold state across different methods (e.g. Sensor manager or "sm")
- Android "lifecycle methods" (e.g. onCreate/onPause) are callbacks the OS can make to get fresh msrmts.
- "Listeners/callbacks" (e.g. SensorEventListener, GnssMeasurementsEvent.Callback) are what lets the system push new data to the phone.
- Java "generics" (e.g. Map<Integer, Double>) capture the type parameters for safety.
- Runtime permissions are used on any Android 6+ in order for us to access user location before GNSS capture.
 */
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.core.content.FileProvider;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;


public class MainActivity extends AppCompatActivity {
// "static final" are compile-time constants (immutable, one per class)
    private static final String TAG = "GNSData-Main";
    private static final int REQ_LOC = 42; // request code for permission dialog above, aka "get the OK"


    // UI references TextView and implements "fields" so all methods in this class can access/update them
    private TextView tvBaro, tvAccel, tvGyro, tvGnss, tvStatus;

    //Our listener created from SensorGnssListener.java
    private SensorGnssListener listener;
    // Our logger created from SheetLogger.java
    private SheetLogger sheetLogger;

    // last-known values so we can write a *wide*, fully populated row each time
    private Float lastBaro = null;
    private Float lastAx = null, lastAy = null, lastAz = null;
    private Float lastGx = null, lastGy = null, lastGz = null;

    // For aspn and lcm
    private LcmAspnBridge aspn;
    private AspnCsvLogger aspnCsv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // inflate (or "bind") XML to live views
        //Wire the views by ID (must match the IDs in activity_main.xml
        tvBaro   = findViewById(R.id.value_baro);
        tvAccel  = findViewById(R.id.value_accel);
        tvGyro   = findViewById(R.id.value_gyro);
        tvGnss   = findViewById(R.id.value_gnss);
        tvStatus = findViewById(R.id.value_status);
        // For aspn and lcm
        aspnCsv = new AspnCsvLogger(getApplicationContext());
        aspn    = new LcmAspnBridge(getApplicationContext(), aspnCsv);


        // Create our listener and implement the Sink inline.
        // This is an “anonymous class” — a common Java pattern where we implement an interface on the fly.
        listener = new SensorGnssListener(getApplicationContext(), new SensorGnssListener.Sink() {
            @Override
            public void onBarometer(float hPa, long tElapsedNs) {
                tvBaro.setText(String.format(Locale.US, "%.2f hPa", hPa));
                lastBaro = hPa;

                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    // Write one WIDE row every time a sensor updates (sample-and-hold for others)
                    sheetLogger.logSensorsWide(now, tElapsedNs,
                            lastBaro,
                            lastAx, lastAy, lastAz,
                            lastGx, lastGy, lastGz);
                }
            }

            @Override
            public void onAccel(float ax, float ay, float az, long tElapsedNs) {
                tvAccel.setText(String.format(Locale.US, "x=%.2f  y=%.2f  z=%.2f m/s²", ax, ay, az));
                lastAx = ax; lastAy = ay; lastAz = az;

                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    sheetLogger.logSensorsWide(now, tElapsedNs,
                            lastBaro,
                            lastAx, lastAy, lastAz,
                            lastGx, lastGy, lastGz);
                }
            }

            @Override
            public void onGyro(float gx, float gy, float gz, long tElapsedNs) {
                tvGyro.setText(String.format(Locale.US, "x=%.3f  y=%.3f  z=%.3f rad/s", gx, gy, gz));
                lastGx = gx; lastGy = gy; lastGz = gz;

                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    sheetLogger.logSensorsWide(now, tElapsedNs,
                            lastBaro,
                            lastAx, lastAy, lastAz,
                            lastGx, lastGy, lastGz);
                }
            }

            @Override
            public void onGnssPrTdcp(int constellation, int svid, double prMeters,
                                     Double tdcpDeltaMeters, Double tdcpRateMps, long tElapsedNs) {
                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    sheetLogger.logGnssPerSv(now, tElapsedNs, constellation, svid,
                            prMeters, tdcpDeltaMeters, tdcpRateMps);
                }
            }

            @Override
            public void onGnssEpoch(String multiLineText, long tElapsedNs) {
                tvGnss.setText(multiLineText); // UI only; logging is per-SV above
            }

            @Override
            public void onStatus(String statusText) {
                tvStatus.setText(statusText);
            }


        });
        //First-run helpful text based on hardware availability (emulators often lack sensors)
        tvBaro.setText(listener.hasBarometer() ? getString(R.string.waiting_sensor) : getString(R.string.no_baro));
        tvAccel.setText(listener.hasAccelerometer() ? getString(R.string.waiting_sensor) : "No accelerometer.");
        tvGyro.setText(listener.hasGyroscope()    ? getString(R.string.waiting_sensor) : "No gyroscope.");
        tvGnss.setText(getString(R.string.gnss_waiting));

        //creating our logger and headers to write to
        char delim = SheetLogger.defaultExcelDelimiterForLocale();
        sheetLogger = SheetLogger.atExternal(getApplicationContext(), delim, true /*BOM*/);
        tvStatus.setText(String.format("Sensors: %s\nGNSS: %s", sheetLogger.sensorsPath(), sheetLogger.gnssPath()));
        sheetLogger.ensureHeaders(); // <- safe no-op if already present
        // Kick off runtime permission flow for GNSS
        ensureLocationPermission();
    }

    // onResume makes the Activity visible and starts listening for sensors
    @RequiresPermission(anyOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @Override protected void onResume() {
        super.onResume();
        if (aspn != null) aspn.start();
        listener.start();
    }

    // onPause stops listening for sensors
    @Override protected void onPause() {
        super.onPause();
        if (listener != null) listener.stop();
        if (aspn != null) aspn.stop();
    }
    // Close the logger
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sheetLogger != null) sheetLogger.close();
    }

    // This permission helper requests at runtime on Android 6+ if need to get permissions
    private void ensureLocationPermission() {
        boolean fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (!(fineGranted || coarseGranted)) {
            tvStatus.setText(getString(R.string.perm_needed));
            ActivityCompat.requestPermissions(
                    this,
                    new String[] {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOC
            );
        } else {
            tvStatus.setText(""); // clear any warning
        }
    }

    // Try and start the listener if we have permission ===
    private void maybeStartListener() {
        boolean fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            if (listener != null) listener.start();
        }
    }


    // === Callback from permission dialog ===
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOC) return;

        boolean granted = false;
        for (int g : grantResults) granted |= (g == PackageManager.PERMISSION_GRANTED);

        if (granted) {
            tvStatus.setText("");
            maybeStartListener();
        } else {
            tvStatus.setText(getString(R.string.perm_needed));
            Log.w(TAG, "Location permission denied; GNSS raw data will not be available.");
        }
    }
}

/*
References used for this project:
- GNSLogger repo: https://github.com/google/gps-measurement-tools
- Android Studio docs: https://developer.android.com/studio
- Java sensor logging docs: https://developer.android.com/develop/sensors-and-location
- GPS Data/measurements crash course:
    - https://www.spirent.com/blogs/2011-01-25_what_is_pseudorange
    -https://www.mdpi.com/1999-4893/17/1/2 (good to look at using Doppler or TDCP-based velocity determination)
    -helpful link for understanding pseudorange: https://stackoverflow.com/questions/54904681/how-to-compute-pseudorange-from-the-parameters-fetched-via-google-gnsslogger)
- Pseudoranges and clock-bias correction:
    -https://web.gps.caltech.edu/classes/ge111/Docs/GPSbasics.pdf (chunky but I had ChatGPT sum up how to fix GPS constellation block bias errors in my PR calc)
- Basic Java to csv: https://www.baeldung.com/java-csv
 */