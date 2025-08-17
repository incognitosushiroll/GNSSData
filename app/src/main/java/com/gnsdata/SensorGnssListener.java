package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/22
Github: https://github.com/incognitosushiroll/GNSSData.git

This file within the GNDSSData project uses an interface to handle callbacks used to pass the data back to MainActivity.java.
 */

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
    private static final String TAG = "GNSData-Listener";
    private static final double C_MPS = 299_792_458.0; // light speed constant

    // Android services to handle hubs mentioned in MainActivity
    private final Context appContext; // keep an application Context that's safe beyond Activity
    private final SensorManager sm; // the sensor hub, anything sensor-related will be handled by the sm
    private final LocationManager lm; // GNSS hub, similar to sm but for raw GNSS data

    // 4 Desired Measurements
    private final Sensor sBaro;
    private final Sensor sAccel;
    private final Sensor sGyro;

    // "hasX" flags to Activity can show friendly messages on emulators or limited devices
    private final boolean hasBaro;
    private final boolean hasAccel;
    private final boolean hasGyro;

    // ADR state for TDCP per SVID
    private final Map<Integer, Double> lastAdrMeters = new HashMap<>();
    private final Map<Integer, Long> lastAdrEpochNs = new HashMap<>();

    // "Sink" is our output callback to the Activity
    public interface Sink {
        void onBarometer(float hPa, long tElapsedNs);
        void onAccel(float ax, float ay, float az, long tElapsedNs);
        void onGyro(float gx, float gy, float gz, long tElapsedNs);
        void onGnssEpoch(String multiLineText, long tElapsedNs);
        void onStatus(String statusText);

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
        Sensor tmpAccel = null;
        Sensor tmpBaro = null;
        Sensor tmpGyro = null;
        if (sm != null) {
            tmpBaro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
            //prefer gravity-removed linear acceleration; if absent fallback to accelerometer
            tmpAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (tmpAccel == null) tmpAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            tmpGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        this.sBaro = tmpBaro;
        this.sAccel = tmpAccel;
        this.sGyro = tmpGyro;

        this.hasBaro = (sBaro != null);
        this.hasAccel = (sAccel != null);
        this.hasGyro = (sGyro != null);

    }
    // Simple feature queries for the Activity, nice for when we first run the program
    public boolean hasBarometer() { return hasBaro; }
    public boolean hasAccelerometer() { return hasAccel; }
    public boolean hasGyroscope() { return hasGyro; }

    // Start listening (we call this from Activity.onResume AFTER permissions are gathered)
    @RequiresPermission(anyOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void start(){
        // Register sensors at a digestible rate for human eye
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
        }
    }
    // Stop listening which is called from Activity.onPause
    public void stop() {
        if (sm != null) sm.unregisterListener(this);
        if (lm != null) {
            try { lm.unregisterGnssMeasurementsCallback(measCb); } catch (Exception ignored) {}
        }
    }

    // SensorEventListener which pushes the 4 desired msmts readings to the Sink
    @Override
    public void onSensorChanged(SensorEvent e) {
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
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {/* TBD */}

    //GNS raw measurement callback (computes PR + TDCP and emits a multi-line summary)
    private final GnssMeasurementsEvent.Callback measCb = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            // Reciever time on GPS timescale: t_rx(GPS = timeNanos - (fullBaisNanos + biasNanos)
            //clock.timeNanos is on the device (hardware's clock), not GPS time
            // fullBiasNanos and biasNanos convert device timescale to GPS timescale
            final GnssClock clock = event.getClock();
            final long tRxNanos = clock.getTimeNanos();
            final double fullBias = clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : 0.0;
            final double bias = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
            final double tRxGpsNanos = tRxNanos - (fullBias + bias);

            //Single multi-line stirng that lists each SV's PR and TDCP for this epoch
            //ADR is carrier-phase distance in meters "since last reset". We difference it across epochs.
            StringBuilder ui = new StringBuilder();
            final long tElapsedNs = SystemClock.elapsedRealtimeNanos(); // align with sensors

            for (android.location.GnssMeasurement m : event.getMeasurements()) {
                final int svid        = m.getSvid();
                final int constel     = m.getConstellationType();               // GPS/GLO/GAL/BDS/…
                final double tTxNanos = m.getReceivedSvTimeNanos()
                        + m.getTimeOffsetNanos();                // transmit time + correction

                // Pseudorange (meters): ρ = (t_rx(GPS) − t_tx) * c
                final double prMeters = (tRxGpsNanos - tTxNanos) * 1e-9 * C_MPS;

                // TDCP using ADR differencing (only when ADR_STATE_VALID)
                String tdcpTxt = "—";
                Double tdcpDelta = null, tdcpRate = null;
                if ((m.getAccumulatedDeltaRangeState()
                        & android.location.GnssMeasurement.ADR_STATE_VALID) != 0) {
                    final double adrNow = m.getAccumulatedDeltaRangeMeters();
                    final Long   lastT  = lastAdrEpochNs.get(svid);
                    final Double lastA  = lastAdrMeters.get(svid);
                    if (lastT != null && lastA != null) {
                        final long dtNs = tRxNanos - lastT;
                        if (dtNs > 0) {
                            final double dMeters = adrNow - lastA;             // Δrange over epoch (m)
                            final double rateMps = dMeters / (dtNs * 1e-9);    // optional rate (m/s)
                            tdcpTxt = String.format(Locale.US, "Δ=%.3f m  rate=%.3f m/s", dMeters, rateMps);
                            // Set tdcpDelta and tdcpRate equal to dMeters and rateMps for logging
                            tdcpDelta = dMeters;
                            tdcpRate = rateMps;
                        }
                    }
                    // Update state for this SV
                    lastAdrEpochNs.put(svid, tRxNanos);
                    lastAdrMeters.put(svid, adrNow);

                } else {
                    // ADR invalid/reset so clear state
                    lastAdrEpochNs.remove(svid);
                    lastAdrMeters.remove(svid);
                }

                ui.append(String.format(Locale.US,
                        "SV %d (C=%d)  PR=%.3f m  TDCP=%s\n", svid, constel, prMeters, tdcpTxt));
                // For testing:
                    // ui.append(String.format(Locale.US,
                           // "TDCP=%s\n", tdcpTxt));
                sink.onGnssPrTdcp(constel, svid, prMeters, tdcpDelta, tdcpRate, tElapsedNs);
            }

            final String uiText = ui.length() == 0 ? "No raw GNSS this epoch" : ui.toString();


            sink.onGnssEpoch(uiText, tElapsedNs);

        }

        @Override public void onStatusChanged(int status) { /* optional, tbd */ }
    };
}

