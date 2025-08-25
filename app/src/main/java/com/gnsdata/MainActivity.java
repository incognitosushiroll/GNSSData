package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/25
Github: https://github.com/incognitosushiroll/GNSSData.git

This java file is part of the GNSSData project. ChatGPT was used to help design the files in "res", "androidmanifest.xml", and any files that helped paint style.
This project is ladden with comments to 1) perform GPS Data gleaning and logging methods, and 2) teach the programmer further java skills!

Tools used for project:
1. Github repo
2. Android Studio
3. Google pixel phone + USB cable and Pixel 9 emulator
4. Used a .java file instead of a Kotlin file (deleted)

Files included:
- SensorGnssListener.java    (collects sensors + GNSS and calls back into this Activity)
- LcmApsnBridge.java         (turns our data into ASPN messages and publishes over LCM)
- MainActivity.java          (this file)

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

import java.util.Locale;

import aspn23_lcm.measurement_IMU;
import aspn23_lcm.measurement_barometer;
import aspn23_lcm.measurement_satnav_with_sv_data;

public class MainActivity extends AppCompatActivity {
    // "static final" are compile-time constants (immutable, one per class)
    private static final String TAG = "GNSData-Main";
    private static final int REQ_LOC = 42; // request code for permission dialog above, aka "get the OK"

    // UI references TextView and implements "fields" so all methods in this class can access/update them
    private TextView tvBaro, tvAccel, tvGyro, tvGnss, tvStatus;

    // Our listener created from SensorGnssListener.java (collects sensors + GNSS)
    private SensorGnssListener listener;

    //Time to log stuff
    private SheetLogger sheetLogger;

    // Our bridge to ASPN/LCM (publishes messages + gives us human-readable summaries)
    private LcmAspnBridge aspn;

    // We’ll build one SATNAV epoch per GNSS callback burst:
    private LcmAspnBridge.EpochBuilder currentEpoch = null;

    // last-known values so we can publish IMU anytime (the bridge does S/H throttling internally)
    private Float lastBaro = null;
    private Float lastAx = null, lastAy = null, lastAz = null;
    private Float lastGx = null, lastGy = null, lastGz = null;

    // === Activity lifecycle: called once when the Activity is created
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Java's way of loading ("inflating") the XML layout into a live view hierarchy
        setContentView(R.layout.activity_main);

        // Find (or "bind") our tv references by their ID as defined in the XML
        tvBaro   = findViewById(R.id.value_baro);
        tvAccel  = findViewById(R.id.value_accel);
        tvGyro   = findViewById(R.id.value_gyro);
        tvGnss   = findViewById(R.id.value_gnss);
        tvStatus = findViewById(R.id.value_status);

        // Init CSV logger (Excel-friendly delimiter + BOM for UTF-8)
        char delim = SheetLogger.defaultExcelDelimiterForLocale();
        sheetLogger = SheetLogger.atExternal(getApplicationContext(), delim, true);
        sheetLogger.ensureHeaders();
        tvStatus.setText(String.format(Locale.US,
                "Sensors: %s\nGNSS: %s\nASPN IMU: %s\nASPN Baro: %s\nASPN Satnav: %s",
                sheetLogger.sensorsPath(), sheetLogger.gnssPath(),
                sheetLogger.aspnImuPath(), sheetLogger.aspnBaroPath(), sheetLogger.aspnSatnavPath()));


        // Create the ASPN/LCM bridge. We pass "this" (the Activity) as Context — it’s valid here.
        aspn = new LcmAspnBridge(this, new LcmAspnBridge.Listener() {
            @Override public void onAspnImuPublished(measurement_IMU msg, String line) {
                // Show a compact IMU summary line in the status area
                runOnUiThread(() -> tvStatus.setText(line));
                if (sheetLogger != null) sheetLogger.logAspnImu(msg);

            }
            @Override public void onAspnBarometerPublished(measurement_barometer msg, String line) {
                // Replace status line with baro summary (you can also append if you prefer)
                runOnUiThread(() -> tvStatus.setText(line));
                if (sheetLogger != null) sheetLogger.logAspnBarometer(msg);
            }
            @Override public void onAspnSatnavPublished(measurement_satnav_with_sv_data msg, String block) {
                // Show SATNAV epoch summary (count + a few SV rows)
                runOnUiThread(() -> tvStatus.setText(block));
                if (sheetLogger != null) sheetLogger.logAspnSatnav(msg);
            }
        });

        // Create our sensor+gnss listener and implement the Sink inline (anonymous class).
        // This is where *phone hardware* measurements get turned into both UI text and ASPN messages.
        listener = new SensorGnssListener(getApplicationContext(), new SensorGnssListener.Sink() {
            @Override
            public void onBarometer(float hPa, long tElapsedNs) {
                // UI (always push UI updates from the main thread):
                runOnUiThread(() -> tvBaro.setText(String.format(Locale.US, "%.2f hPa", hPa)));
                // Remember our last value (not strictly required here since LcmApsnBridge publishes directly)
                lastBaro = hPa;
                // ASPN publish (bridge converts hPa -> Pa internally and publishes):
                if (aspn != null) aspn.publishBarometer(hPa, 0.0 /* variance Pa^2, unknown so 0 */);

                // Put RAW data into wide sensor row in csv
                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    sheetLogger.logSensorsWide(now, tElapsedNs,
                            lastBaro,
                            lastAx, lastAy, lastAz,
                            lastGx, lastGy, lastGz);
                }
            }
            @Override
            public void onAspnBarometerPublished(measurement_barometer msg, String line) {
                if (sheetLogger != null) sheetLogger.logAspnBarometer(msg);
            }

            @Override
            public void onAccel(float ax, float ay, float az, long tElapsedNs) {
                runOnUiThread(() ->
                        tvAccel.setText(String.format(Locale.US, "x=%.2f  y=%.2f  z=%.2f m/s²", ax, ay, az)));
                lastAx = ax; lastAy = ay; lastAz = az;
                if (aspn != null) aspn.onAccel(ax, ay, az); // Bridge sample-and-hold will publish at ~50 Hz
                // RAW wide sensor row
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
                runOnUiThread(() ->
                        tvGyro.setText(String.format(Locale.US, "x=%.3f  y=%.3f  z=%.3f rad/s", gx, gy, gz)));
                lastGx = gx; lastGy = gy; lastGz = gz;
                if (aspn != null) aspn.onGyro(gx, gy, gz); // Bridge handles rate limiting + publish
                // RAW wide sensor row
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
                // This is invoked once per SV in the current epoch.
                // We lazily create an EpochBuilder the first time we see an SV for this epoch.
                if (aspn != null) {
                    if (currentEpoch == null) currentEpoch = aspn.newSatnavEpoch();
                    currentEpoch.addSv(constellation, svid, prMeters, tdcpDeltaMeters, tdcpRateMps);
                }
                // Note: UI per-SV lines are built inside SensorGnssListener and delivered via onGnssEpoch(..) below.
                // put RAW per-SV GNSS data into csv
                long now = System.currentTimeMillis();
                if (sheetLogger != null) {
                    sheetLogger.logGnssPerSv(now, tElapsedNs, constellation, svid,
                            prMeters, tdcpDeltaMeters, tdcpRateMps);
                }
            }

            @Override
            public void onGnssEpoch(String multiLineText, long tElapsedNs) {
                // Show the per-SV multi-line text the phone just collected for this epoch
                runOnUiThread(() -> tvGnss.setText(multiLineText));

                // Publish the full ASPN SATNAV message once per epoch (then clear builder)
                if (currentEpoch != null) {
                    currentEpoch.publish();
                    currentEpoch = null;
                }
            }

            @Override
            public void onStatus(String statusText) {
                runOnUiThread(() -> tvStatus.setText(statusText));
            }
        });

        // First-run helpful text based on hardware availability (emulators often lack sensors)
        tvBaro.setText(listener.hasBarometer()     ? getString(R.string.waiting_sensor) : getString(R.string.no_baro));
        tvAccel.setText(listener.hasAccelerometer()? getString(R.string.waiting_sensor) : "No accelerometer.");
        tvGyro.setText(listener.hasGyroscope()     ? getString(R.string.waiting_sensor) : "No gyroscope.");
        tvGnss.setText(getString(R.string.gnss_waiting));

        // Kick off runtime permission flow for GNSS
        ensureLocationPermission();
    }

    // === Activity lifecycle: visible → start listening / publishing
    @RequiresPermission(anyOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @Override protected void onResume() {
        super.onResume();
        if (aspn != null) aspn.start();   // Acquire multicast lock + init LCM (if available)
        if (listener != null) listener.start(); // Start sensors + GNSS callbacks
    }

    // === Activity lifecycle: going background → stop listening / publishing
    @Override protected void onPause() {
        super.onPause();
        if (listener != null) listener.stop();
        if (aspn != null) aspn.stop();    // Release multicast lock
    }

    // === Permissions helper: requests at runtime on Android 6+ so GNSS raw can be registered
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
