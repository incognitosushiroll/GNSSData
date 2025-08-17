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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener; // Interface used to receive sensor updates
import android.hardware.SensorManager; // hooks into hardware sensors
import android.location.GnssClock;
import android.location.GnssMeasurementsEvent; // Raw GNSS callback (API 24+)
import android.location.LocationManager; // System's gnss service
import android.os.Bundle;
import android.os.SystemClock; // Time for stable deltas, not used yet
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity; // base class for holding modern Activities
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap; // Java "collections - hash table map
import java.util.Locale; // for String.format
import java.util.Map;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
// "static final" are compile-time constants (immutable, one per class)
    private static final String TAG = "GPSData";
    private static final int REQ_LOC = 42; // request code for permission dialog above, aka "get the OK"
    private static final double C_MPS = 299_792_458.0; // light speed constant

    // UI references TextView and implements "fields" so all methods in this class can access/update them
    private TextView tvBaro, tvAccel, tvGyro, tvGnss, tvStatus;

    // Sensor hub
    private SensorManager sm; // think of this as the hub for the sensors, anything to do with a sensor will use some aspect of sm to work
    private Sensor sBaro, sAccel, sGyro; //our 4 desired msmts
    private boolean hasBaro, hasAccel, hasGyro; //error checking

    // GNSS hub
    private LocationManager lm; //hub for gnss and location data
    private boolean gnssRegistered = false; //have we registered our callback? good to do ;)

    // Handle our ADR history per each sat's TDCP:
    // Java generics <Integer,Double> means the key is an Integer (SVID) and the value is a double, where SVID is sat vehicle identification
    // Remember, TDCP calculations need two variables to calculate ADR: vel (m) and time between epochs (s)
    private final Map<Integer, Double> lastAdrMeters = new HashMap<>();
    private final Map<Integer, Long> lastAdrEpochNs = new HashMap<>();

    // GNSS raw measurement callback
    // This is an "anonymous inner class instance" that extends the Callback base class of GnssMeasurementsEvent
    // It works by calling "onGnssMeasurementsReceived() when the system has new raw GNSS data
    private final GnssMeasurementsEvent.Callback measCb = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            // 1) Get the receiver time in GPS timescale format
            // clock.timeNanos is on the device (hardware's clock), not GPS time
            // fullBiasNanos and biasNanos convert device timescale to GPS timescale
            GnssClock clock = event.getClock();
            final long tRxNanos = clock.getTimeNanos(); // raw device time (ns)
            final double fullBias = clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : 0.0;
            final double bias     = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
            final double tRxGpsNanos = tRxNanos - (fullBias + bias); // GPS timescale (ns)

            // 2) Here we're just picking the first location measurement in this epoch
            // IRL, we'd iterate all the sat vehicle  (SVs) and show multiple lines of locs
            android.location.GnssMeasurement pick = null;
            for (android.location.GnssMeasurement m : event.getMeasurements()) {
                pick = m;
                break;
            }
            if (pick == null) return;

            // Sat vehicle identification (SV) & their times
            final int svid = pick.getSvid();
            final int constel = pick.getConstellationType(); // GPS/GLO/GAL/BDS/QZSS/IRNSS etc
            final double tTxNanos = pick.getReceivedSvTimeNanos() // Sat transit time @ code epoch
                    + pick.getTimeOffsetNanos(); // hardware/processing correction

            // 3) PSEUDORANGE (meters): ρ = (t_rx(GPS) − t_tx) * c (
            double prMeters = (tRxGpsNanos - tTxNanos) * 1e-9 * C_MPS;

            // 4) TDCP via Accumulated Delta Range differencing
            // ADR is carrier-phase distance in meters "since last reset". We difference it across epochs.
            String tdcpTxt = "TDCP: —";
            if ((pick.getAccumulatedDeltaRangeState()
                    & android.location.GnssMeasurement.ADR_STATE_VALID) != 0) {
                double adrNow = pick.getAccumulatedDeltaRangeMeters();
                Long lastT = lastAdrEpochNs.get(svid);
                Double lastA = lastAdrMeters.get(svid);
                if (lastT != null && lastA != null) {
                    long dtNs = tRxNanos - lastT;
                    if (dtNs > 0) {
                        double dMeters = adrNow - lastA; // TDCP distance (m) over epoch
                        double rateMps = dMeters / (dtNs * 1e-9); // optional range rate (m/s)
                        tdcpTxt = String.format(Locale.US, "TDCP: Δ=%.3f m  rate=%.3f m/s", dMeters, rateMps);
                    }
                }
                // Update the history for this satellite vehicle
                lastAdrEpochNs.put(svid, tRxNanos);
                lastAdrMeters.put(svid, adrNow);
            } else {
                // ADR is not valid so clear history
                lastAdrEpochNs.remove(svid);
                lastAdrMeters.remove(svid);
            }
            // 5) updat the UI (on the main thread!) to show SVID, constellation, pseudorange (PR), TDCP
            final String text = String.format(Locale.US,
                    "SV %d (C=%d)\nPR: %.3f m\n%s",
                    svid, constel, prMeters, tdcpTxt);

            runOnUiThread(() -> tvGnss.setText(text));
        }

        @Override
        public void onStatusChanged(int status) {
            // Optional: could display measurement status
        }
    };

    // Android lifecyle, onCreate is called ONCE when Activity is created
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Java's way of loading  ("inflating") the XML layout into a live view hierarchy
        setContentView(R.layout.activity_main);

        // Find (or "bind") our tv references by their ID as defined in the XML
        tvBaro  = findViewById(R.id.value_baro);
        tvAccel = findViewById(R.id.value_accel);
        tvGyro  = findViewById(R.id.value_gyro);
        tvGnss  = findViewById(R.id.value_gnss);
        tvStatus= findViewById(R.id.value_status);

        // Sensor setup
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            // Barometer
            sBaro  = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
            hasBaro  = (sBaro  != null);

            // Accel
            sAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION); // if gravity removed, we prefer this type here
            if (sAccel == null) sAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            hasAccel = (sAccel != null);

            // Gyro
            sGyro  = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            hasGyro  = (sGyro  != null);
        }

        // UI setup messages for emulators that lack sensors, may remove if only testing on physical devices
        tvBaro.setText(hasBaro ? getString(R.string.waiting_sensor) : getString(R.string.no_baro));
        tvAccel.setText(hasAccel ? getString(R.string.waiting_sensor) : "No accelerometer.");
        tvGyro.setText(hasGyro ? getString(R.string.waiting_sensor) : "No gyroscope.");
        tvGnss.setText(getString(R.string.gnss_waiting));

        // GNSS service handle permissions
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureLocationPermission();
    }
    // onResume is if activity is visible, register it
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    @Override protected void onResume() {
        super.onResume();
        // Register sensor listeners. SENSOR_DELAY_NORMAL = UI-friendly rate
        if (hasBaro)  sm.registerListener(this, sBaro,  SensorManager.SENSOR_DELAY_NORMAL);
        if (hasAccel) sm.registerListener(this, sAccel, SensorManager.SENSOR_DELAY_NORMAL);
        if (hasGyro)  sm.registerListener(this, sGyro,  SensorManager.SENSOR_DELAY_NORMAL);

        // GNSS measurements (only after permission) registered
        maybeRegisterGnss();
    }

    // onPause is if the activity is partially hidden we stop the sensor to save power
    @Override protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
        if (lm != null && gnssRegistered) {
            try {
                lm.unregisterGnssMeasurementsCallback(measCb);
            } catch (Exception ignored) {}
            gnssRegistered = false;
        }
    }

    // --- Sensors ---
    // SensorEventListener will be invoked whenever a registered sensor produces a new sample
    @Override
    public void onSensorChanged(SensorEvent e) {
        // NOTE: SensorEvent.values[] holds the reading; its length and units depend on the sensor type.
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_PRESSURE) {
            float hPa = e.values[0];
            tvBaro.setText(String.format(Locale.US, "%.2f hPa", hPa));
        } else if (type == Sensor.TYPE_LINEAR_ACCELERATION || type == Sensor.TYPE_ACCELEROMETER) {
            float ax = e.values[0], ay = e.values[1], az = e.values[2];
            tvAccel.setText(String.format(Locale.US, "x=%.2f  y=%.2f  z=%.2f", ax, ay, az));
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            float gx = e.values[0], gy = e.values[1], gz = e.values[2];
            tvGyro.setText(String.format(Locale.US, "x=%.3f  y=%.3f  z=%.3f", gx, gy, gz));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* ignore */ }

    // --- Permissions flow & GNSS registration ---

    private void ensureLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!(fine || coarse)) {
            tvStatus.setText(getString(R.string.perm_needed));
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION },
                    REQ_LOC);
        } else {
            tvStatus.setText(""); // clear any warning
        }
    }
    // Register for raw GNSS measurements if permitted and supported on the device

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private void maybeRegisterGnss() {
        if (lm == null) return;
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!(fine || coarse)) return;

        if (!gnssRegistered) {
            try {
                // returns true if registration succeeds
                boolean ok = lm.registerGnssMeasurementsCallback(measCb);
                gnssRegistered = ok;
                if (!ok) tvStatus.setText("GNSS measurements not supported on this device/OS.");
            } catch (SecurityException se) {
                Log.e(TAG, "No permission?", se);
                tvStatus.setText("GNSS registration failed: permission.");
            } catch (Throwable t) {
                Log.e(TAG, "GNSS registration failed", t);
                tvStatus.setText("GNSS registration failed.");
            }
        }
    }
    // Called after the permission dialog. We check whether any location permission was granted.
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            boolean granted = false;
            for (int g : grantResults) granted |= (g == PackageManager.PERMISSION_GRANTED);
            if (granted) {
                tvStatus.setText("");
                maybeRegisterGnss();
            } else {
                tvStatus.setText(getString(R.string.perm_needed));
            }
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
 */