package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/22
Github: https://github.com/incognitosushiroll/GNSSData.git

This file within the GNDSSData project uses an interface to handle callbacks used to pass the data back to MainActivity.java.
 */

/* QUICK GNSS MATH
        *  - Rx time on GPS scale: t_rx(GPS) = clock.timeNanos - (fullBiasNanos + biasNanos)
        *  - Tx time (per-sat):    t_tx (constellation's own scale) ≈ receivedSvTimeNanos + timeOffsetNanos
        *  - Align Tx → GPS timescale (BDT +14 s; GLONASS + leap seconds)
        *  - Put both Rx and Tx into same modulo window: week for most; day for GLONASS
        *  - Pseudorange: ρ ≈ ( (t_rx mod M) - (t_tx mod M) ) (wrapped into nearest image) * c
        *  - TDCP: difference of Accumulated Delta Range (ADR) between epochs (meters),
        *          and an optional rate = ΔADR / Δt */
import android.Manifest;
import android.content.Context;
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

public class SensorGnssListener implements SensorEventListener {
    // Constants, constrained at compiletime, immutable
    private static final double C_MPS = 299_792_458.0;
    private static final double WEEK_NS = 604800e9;   // 604,800 s in ns
    private static final double DAY_NS = 86400e9;    // 86,400 s in ns

    // Android services to handle hubs mentioned in MainActivity
    private final Context appContext; // keep an application Context that's safe beyond Activity
    private final SensorManager sm; // the sensor hub, anything sensor-related will be handled by the sm
    private final LocationManager lm; // GNSS hub, similar to sm but for raw GNSS data

    // 4 Desired Measurements
    private Sensor sBaro, sAccel, sGyro;
    private boolean hasBaro, hasAccel, hasGyro; // "hasX" flags to Activity can show friendly messages on emulators or limited devices
    private boolean running = false;


    // ADR state for TDCP per SVID
    private final Map<Integer, Double> lastAdrMeters = new HashMap<>();
    private final Map<Integer, Long> lastAdrEpochNs = new HashMap<>();

    // "Sink" is our output callback to the Activity. Keep UI/CSV/LCM out of the listener .
    public interface Sink {
        void onBarometer(float hPa, long tElapsedNs);

        void onAccel(float ax, float ay, float az, long tElapsedNs);

        void onGyro(float gx, float gy, float gz, long tElapsedNs);

        //per epoch callback
        void onGnssEpoch(String multiLineText, long tElapsedNs);

        // optional status msg for dedug
        void onStatus(String statusText);

        // per satellite callback: one row worth of GNSS data
        void onGnssPrTdcp(int constellation, int svid, double prMeters,
                          Double tdcpDeltaMeters, Double tdcpRateMps, long tElapsedNs);
        // Note: that when ADR isn't available yet, we pass null (Doubles are nullable)
    }

    private final Sink sink;

    // Constructor that's called by Activity to handle what we're listening to
    public SensorGnssListener(Context ctx, Sink sink) {
        // capture the application context so we don't get one leaked in Activity
        this.appContext = ctx.getApplicationContext();
        this.sink = sink;

        //get SystemService is how you acquire Android's system services (SensorManager, LocationManager)
        this.sm = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);

        // Pick sensors (may be null on emulator)
//        Sensor tmpAccel = null;
//        Sensor tmpBaro = null;
//        Sensor tmpGyro = null;
        if (sm != null) {
            sBaro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
            //prefer gravity-removed linear acceleration; if absent fallback to accelerometer
            sAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (sAccel == null) sAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        hasBaro = (sBaro != null);
        hasAccel = (sAccel != null);
        hasGyro = (sGyro != null);

//        this.hasBaro = (sBaro != null);
//        this.hasAccel = (sAccel != null);
//        this.hasGyro = (sGyro != null);

    }
    // Simple feature queries for the Activity, nice for when we first run the program
    public boolean hasBarometer() { return hasBaro; }
    public boolean hasAccelerometer() { return hasAccel; }
    public boolean hasGyroscope() { return hasGyro; }

    // Start listening (we call this from Activity.onResume AFTER permissions are gathered)
    @RequiresPermission(anyOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void start() {
        if (running) return;
        running = true;
        // Register sensors at a digestible rate for human eye
        // CHANGE 4: SENSOR_DELAY_GAME gives a decent rate for IMU logging/LCM (vs. UI-only NORMAL).
        if (hasBaro) sm.registerListener(this, sBaro, SensorManager.SENSOR_DELAY_GAME);
        if (hasAccel) sm.registerListener(this, sAccel, SensorManager.SENSOR_DELAY_GAME);
        if (hasGyro) sm.registerListener(this, sGyro, SensorManager.SENSOR_DELAY_GAME);

        // GNSS raw measurements
        try {
            lm.registerGnssMeasurementsCallback(gnssCb);
        } catch (SecurityException se) {
            if (sink != null) sink.onStatus("No location permission for GNSS measurements.");
        } catch (Throwable t) {
            if (sink != null) sink.onStatus("GNSS registration failed: " + t.getMessage());
        }/* old code, keep for my sanity
        if (sm != null) {
            if (hasBaro) sm.registerListener(this, sBaro, SensorManager.SENSOR_DELAY_NORMAL);
            if (hasAccel) sm.registerListener(this, sAccel, SensorManager.SENSOR_DELAY_NORMAL);
            if (hasGyro) sm.registerListener(this, sGyro, SensorManager.SENSOR_DELAY_NORMAL);
        }

        //Register GNSS raw measurements callback (guard with the permissions)
        if (lm != null) {
            boolean allowed =
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            || ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (allowed) {
                try {
                    boolean ok = lm.registerGnssMeasurementsCallback(measCb);
                    if (!ok) sink.onStatus("GNSS raw measurements not supported on this device/OS.");
                } catch (SecurityException se) {
                    sink.onStatus("GNSS registration failed: missing location permission.");
                    Log.e(TAG, "Permission error", se);
                } catch (Throwable t) {
                    sink.onStatus("GNSS registration failed.");
                    Log.e(TAG, "GNSS callback registration failed", t);
                }
            } else {
                sink.onStatus("Location permission required for GNSS.");
            }
        }*/
    }

    // Stop listening which is called from Activity.onPause
    public void stop() {
        if (!running) return;
        running = false;
        //for aspn and lcm
        try {
            sm.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        try {
            lm.unregisterGnssMeasurementsCallback(gnssCb);
        } catch (Throwable ignored) {
        }
        lastAdrEpochNs.clear();
        lastAdrMeters.clear();
        /* old
        if (sm != null) sm.unregisterListener(this);
        if (lm != null) {
            try { lm.unregisterGnssMeasurementsCallback(measCb); } catch (Exception ignored) {}
        } */
    }

    // SensorEventListener which pushes the 4 desired msmts readings to the Sink
    @Override
    public void onSensorChanged(SensorEvent e) {
        final long tElapsedNs = SystemClock.elapsedRealtimeNanos(); // monotonic time aligned across sensors

        switch (e.sensor.getType()) { // depending on the sensor type, get the measurement
            case Sensor.TYPE_PRESSURE: {
                float hPa = e.values[0];
                if (sink != null) sink.onBarometer(hPa, tElapsedNs);
                break;
            }
            case Sensor.TYPE_LINEAR_ACCELERATION:
            case Sensor.TYPE_ACCELEROMETER: {
                float ax = e.values[0], ay = e.values[1], az = e.values[2];
                if (sink != null) sink.onAccel(ax, ay, az, tElapsedNs);
                break;
            }
            case Sensor.TYPE_GYROSCOPE: {
                float gx = e.values[0], gy = e.values[1], gz = e.values[2];
                if (sink != null) sink.onGyro(gx, gy, gz, tElapsedNs);
                break;
            }
            default:
                // ignore
        }
        /* old
        final int type = e.sensor.getType();
        final long tElapsedNs = SystemClock.elapsedRealtimeNanos(); // monotonic timestamp for alignment

        if (type == Sensor.TYPE_PRESSURE){
            sink.onBarometer(e.values[0], tElapsedNs);
            return;
        }
        if (type == Sensor.TYPE_LINEAR_ACCELERATION || type == Sensor.TYPE_ACCELEROMETER){
            sink.onAccel(e.values[0], e.values[1], e.values[2], tElapsedNs);
            return;
        }
        if (type == Sensor.TYPE_GYROSCOPE) {
            sink.onGyro(e.values[0], e.values[1], e.values[2], tElapsedNs);
            return;
        } */
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {/* TBD */}

    //GNS raw measurement callback (computes PR + TDCP and emits a multi-line summary)
    private final GnssMeasurementsEvent.Callback gnssCb = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            // Receiver time on the device (hardware) converted to the GPS timescale
            final GnssClock clock = event.getClock();
            final long tRxNanos = clock.getTimeNanos();
            final double fullBiasNs = clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : 0.0;
            final double biasNs = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
            final double tRxGpsNanos = tRxNanos - (fullBiasNs + biasNs); // continuous GPST in ns

            // Build a multi-line UI string for this epoch
//                StringBuilder ui = new StringBuilder();
//                final long tElapsedNs = SystemClock.elapsedRealtimeNanos(); // monotonic for logging alignment
            final long tElapsedNs = SystemClock.elapsedRealtimeNanos();

            // Build a friendly multi-line text for UI; structured per-SV goes via Sink
            StringBuilder ui = new StringBuilder();
            for (android.location.GnssMeasurement m : event.getMeasurements()) {
                final int svid = m.getSvid();
                final int constel = m.getConstellationType();

                // Satellite transmit time (Tx) at code epoch in ns (will be a large number in the .csv) in the constellation’s own time scale
                double tTxNs = m.getReceivedSvTimeNanos() + m.getTimeOffsetNanos();

                // Convert tramsit time (Tx) to GPS time scale (very important for valid pseudorange)
                // Reference: https://web.gps.caltech.edu/classes/ge111/Docs/GPSbasics.pdf
                        /*
                            Tx time (t_tx) = the time the satellite transmitted the code epoch you’re measuring.

                            Rx time (t_rx) = the time your receiver received that same code epoch (expressed on the GPS time scale after clock-bias correction).
                        */
                // GPS/QZSS/SBAS: ~0 offset → leave as-is.
                // Galileo (GST): typically small ns-level offset → ignore here.
                // BeiDou (BDT): GPST = BDT + 14 s  → add +14 s.
                // GLONASS (UTC(SU) TOD): GPST = UTC + leapSeconds → add leap seconds.
                    /* Reference: https://developer.android.com/reference/android/location/GnssStatus
                        GPS = 1
                        Sbas = 2
                        Glonass = 3
                        Qzss = 4
                        Beidou = 5
                        Galileo = 6
                        IRNSS = 7
                    */
                /* Basicially,  BeiDou (BDT → GPST): +14 s; GLONASS (UTC(SU) TOD → GPST): + leap seconds.*/
                double offsetToGpsNs = 0.0;
                if (constel == android.location.GnssStatus.CONSTELLATION_BEIDOU) {
                    offsetToGpsNs = 14.0e9;  // 14 seconds
                } else if (constel == android.location.GnssStatus.CONSTELLATION_GLONASS) {
                    // If the hardware provides leap seconds, use it; otherwise fall back to 18 s (current).
                    int leap = clock.hasLeapSecond() ? clock.getLeapSecond() : 18;
                    offsetToGpsNs = leap * 1e9;
                }
                double tTxGpsNs = tTxNs + offsetToGpsNs;

                // Choose the modulo window: week for most, day for GLONASS
                double moduloNs = (constel == android.location.GnssStatus.CONSTELLATION_GLONASS) ? DAY_NS : WEEK_NS;

                // Fold both receiver and (re-calc'd) transmit times into the same modulo window
                double tRxTow = tRxGpsNanos % moduloNs;
                if (tRxTow < 0) tRxTow += moduloNs;
                double tTxTow = tTxGpsNs % moduloNs;
                if (tTxTow < 0) tTxTow += moduloNs;

                // Raw difference and wrap to nearest number to handle rollover between week and day mark
                double dtNs = tRxTow - tTxTow;
                if (dtNs > 0.5 * moduloNs) dtNs -= moduloNs;
                if (dtNs < -0.5 * moduloNs) dtNs += moduloNs;

                // Pseudorange in meters
                double prMeters = dtNs * 1e-9 * C_MPS;

                // Sanity gate for security, keep ~1,000–70,000 km
                if (prMeters < 1.0e6 || prMeters > 7.0e7) {
                    // We could also sink.onStatus("Dropped PR SV " + svid + " C=" + constel + " pr=" + (long)prMeters); if the range of the PR doesn't fit beauty standards
                    continue;
                }

                // TDCP via ADR differencing (if ADR valid) ---
                String tdcpTxt = "—";
                Double tdcpDelta = null;
                Double tdcpRate = null;

                // Getting all the state for this SV
                if ((m.getAccumulatedDeltaRangeState()
                        & android.location.GnssMeasurement.ADR_STATE_VALID) != 0) {
                    final double adrNow = m.getAccumulatedDeltaRangeMeters();
                    final Long lastT = lastAdrEpochNs.get(svid); // time
                    final Double lastA = lastAdrMeters.get(svid); //ADR

                    //Use a different variable name for TDCP Δt to avoid shadowing PR dtNs.
                    if (lastT != null && lastA != null) {
                        long adrDtNs = tRxNanos - lastT;
                        if (adrDtNs > 0) {
                            double dMeters = adrNow - lastA;               // Δ range (m) across epochs
                            double rateMps = dMeters / (adrDtNs * 1e-9);   // optional range rate
                            tdcpDelta = dMeters;
                            tdcpRate = rateMps;
                            tdcpTxt = String.format(Locale.US, "Δ=%.3f m  rate=%.3f m/s", dMeters, rateMps);
                        }
                    }
                    // Update ADR history for this SV
                    lastAdrEpochNs.put(svid, tRxNanos);
                    lastAdrMeters.put(svid, adrNow);
                } else {
                    // ADR invalid / reset (cycle slip, loss of lock, etc.) → clear history
                    lastAdrEpochNs.remove(svid);
                    lastAdrMeters.remove(svid);
                }

                // Human-readable one-liner for the UI page (multi-line block per epoch)
                ui.append(String.format(Locale.US,
                        "SV %d (C=%d)  PR=%.3f m  TDCP=%s\n", svid, constel, prMeters, tdcpTxt));

                // Structured per-SV callback (your Activity can log/LCM this cleanly)
                if (sink != null)
                    sink.onGnssPrTdcp(constel, svid, prMeters, tdcpDelta, tdcpRate, tElapsedNs);
            }

            // Per-epoch callback (multi-line text). If nothing passed filters, say so.
            if (sink != null) {
                final String uiText = (ui.length() == 0) ? "No raw GNSS this epoch" : ui.toString();
                sink.onGnssEpoch(uiText, tElapsedNs);
            }
        }
    };
}
//    @Override
//    public void onStatusChanged(int status) {
//        // (Deprecated in API 30; kept for completeness—most status you want comes via sink/onStatus if you emit it.)
//    }
//}


    /*
                        if (lastT != null && lastA != null) {
                            long adrDtNs = tRxNanos - lastT;     // NOTE: new variable name → avoid shadowing dtNs
                            if (adrDtNs > 0) {
                                double dMeters = adrNow - lastA; // Δrange (m) over this epoch
                                double rateMps = dMeters / (adrDtNs * 1e-9);
                                tdcpTxt   = String.format(Locale.US, "Δ=%.3f m  rate=%.3f m/s", dMeters, rateMps);
                                tdcpDelta = dMeters;
                                tdcpRate  = rateMps;
                            }
                        }

                        // Update state for this SV
                        lastAdrEpochNs.put(svid, tRxNanos);
                        lastAdrMeters.put(svid, adrNow);
                    } else {
                        // If the ADR rings up invalid, then reset/clear state so next valid epoch starts fresh
                        lastAdrEpochNs.remove(svid);
                        lastAdrMeters.remove(svid);
                    }

                    // UI line for this SV
                    ui.append(String.format(Locale.US,
                            "SV %d (C=%d)  PR=%.3f m  TDCP=%s\n", svid, constel, prMeters, tdcpTxt));

                    // Structured callback for logging (per-SV)
                    sink.onGnssPrTdcp(constel, svid, prMeters, tdcpDelta, tdcpRate, tElapsedNs);
                }
                // This line of uiText may appear when running the app at first, give it time to load the GNSS data
                final String uiText = (ui.length() == 0) ? "No raw GNSS this epoch" : ui.toString();
                sink.onGnssEpoch(uiText, tElapsedNs);
            }
            @Override public void onStatusChanged(int status) { } */
