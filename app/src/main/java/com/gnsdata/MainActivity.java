package com.gnsdata;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssClock;
import android.location.GnssMeasurementsEvent;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "GNSData";
    private static final int REQ_LOC = 42;
    private static final double C_MPS = 299_792_458.0;

    // UI
    private TextView tvBaro, tvAccel, tvGyro, tvGnss, tvStatus;

    // Sensors
    private SensorManager sm;
    private Sensor sBaro, sAccel, sGyro;
    private boolean hasBaro, hasAccel, hasGyro;

    // GNSS
    private LocationManager lm;
    private boolean gnssRegistered = false;

    // TDCP state (per SVID)
    private final Map<Integer, Double> lastAdrMeters = new HashMap<>();
    private final Map<Integer, Long> lastAdrEpochNs = new HashMap<>();

    private final GnssMeasurementsEvent.Callback measCb = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            // Receiver time on GPS timescale
            GnssClock clock = event.getClock();
            final long tRxNanos = clock.getTimeNanos();
            final double fullBias = clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : 0.0;
            final double bias     = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
            final double tRxGpsNanos = tRxNanos - (fullBias + bias);

            // Show just the first SV in this epoch for simplicity
            android.location.GnssMeasurement pick = null;
            for (android.location.GnssMeasurement m : event.getMeasurements()) {
                pick = m;
                break;
            }
            if (pick == null) return;

            final int svid = pick.getSvid();
            final int constel = pick.getConstellationType();
            final double tTxNanos = pick.getReceivedSvTimeNanos() + pick.getTimeOffsetNanos();

            // Pseudorange (meters)
            double prMeters = (tRxGpsNanos - tTxNanos) * 1e-9 * C_MPS;

            // TDCP via Accumulated Delta Range differencing
            String tdcpTxt = "TDCP: —";
            if ((pick.getAccumulatedDeltaRangeState()
                    & android.location.GnssMeasurement.ADR_STATE_VALID) != 0) {
                double adrNow = pick.getAccumulatedDeltaRangeMeters();
                Long lastT = lastAdrEpochNs.get(svid);
                Double lastA = lastAdrMeters.get(svid);
                if (lastT != null && lastA != null) {
                    long dtNs = tRxNanos - lastT;
                    if (dtNs > 0) {
                        double dMeters = adrNow - lastA;
                        double rateMps = dMeters / (dtNs * 1e-9);
                        tdcpTxt = String.format(Locale.US, "TDCP: Δ=%.3f m  rate=%.3f m/s", dMeters, rateMps);
                    }
                }
                lastAdrEpochNs.put(svid, tRxNanos);
                lastAdrMeters.put(svid, adrNow);
            } else {
                lastAdrEpochNs.remove(svid);
                lastAdrMeters.remove(svid);
            }

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBaro  = findViewById(R.id.value_baro);
        tvAccel = findViewById(R.id.value_accel);
        tvGyro  = findViewById(R.id.value_gyro);
        tvGnss  = findViewById(R.id.value_gnss);
        tvStatus= findViewById(R.id.value_status);

        // Sensors
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            sBaro  = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
            sAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (sAccel == null) sAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sGyro  = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

            hasBaro  = (sBaro  != null);
            hasAccel = (sAccel != null);
            hasGyro  = (sGyro  != null);
        }

        tvBaro.setText(hasBaro ? getString(R.string.waiting_sensor) : getString(R.string.no_baro));
        tvAccel.setText(hasAccel ? getString(R.string.waiting_sensor) : "No accelerometer.");
        tvGyro.setText(hasGyro ? getString(R.string.waiting_sensor) : "No gyroscope.");
        tvGnss.setText(getString(R.string.gnss_waiting));

        // GNSS
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureLocationPermission();
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    @Override protected void onResume() {
        super.onResume();
        // Sensors
        if (hasBaro)  sm.registerListener(this, sBaro,  SensorManager.SENSOR_DELAY_NORMAL);
        if (hasAccel) sm.registerListener(this, sAccel, SensorManager.SENSOR_DELAY_NORMAL);
        if (hasGyro)  sm.registerListener(this, sGyro,  SensorManager.SENSOR_DELAY_NORMAL);

        // GNSS measurements (only after permission)
        maybeRegisterGnss();
    }

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

    @Override
    public void onSensorChanged(SensorEvent e) {
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

    // --- Permissions & GNSS registration ---

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
            tvStatus.setText("");
        }
    }

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
