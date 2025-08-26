package com.gnsdata;

/*
Created by: Katki
Date: 08/14 - 08/25
Github: https://github.com/incognitosushiroll/GNSSData.git

This LcmApsnBridge.java will:
   - Convert phone sensor/GNSS data into ASPN message objects
   - Publish them on LCM channels
   - Provide human-readable summaries via a Listener so UI can display them

 Notes:
 - Uses aspn_messages.jar generated classes (aspn23_lcm.*) --> this you need creds to the aspn git lab in order to download.
  - Uses lcm.jar (LCM transport) --> this was downloaded on a Linux system. If you are running this on Windows, quit a bit of set-up is needed to install liblcm-dev package.
  - Timestamps use elapsedRealtimeNanos() to match sensor/GNSS monotonic time - math involved here, references in MainActivity.java should help

 Extra notes (why the design looks like this):
  - batches are  per-satellite measurements for a single GNSS "epoch" using an inner builder class (EpochBuilder).
    call addSv(...) for every satellite observed within that epoch, then call publish() ONCE to emit one ASPN message
    that contains arrays for all satellites (obs[] and sv_data[]).
  - optionally mirror every published message into an LCM *.lcmlog* via ApsnEventLogger so you can replay with LCM tools. This was ChatGPT's idea, i take no credit.
*/

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ASPN auto-generated types (from aspn_messages.jar)
import aspn23_lcm.measurement_IMU;
import aspn23_lcm.measurement_barometer;
import aspn23_lcm.measurement_satnav_with_sv_data;
import aspn23_lcm.type_header;
import aspn23_lcm.type_integrity;
import aspn23_lcm.type_satnav_obs;
import aspn23_lcm.type_satnav_satellite_system;
import aspn23_lcm.type_satnav_sv_data;
import aspn23_lcm.type_satnav_time;
import aspn23_lcm.type_timestamp;

// LCM transport
import lcm.lcm.LCM;
import lcm.lcm.LCMEncodable;

public class LcmAspnBridge {

    // === OPTIONAL .lcmlog writer (mirrors what we publish on LCM into a file)
    //     You can omit this; the bridge will still publish on LCM even if eventLogger is null.
    private AspnEventLogger eventLogger; // added for lcm.logging, optional since we are publishing to the lcm
    public void setEventLogger(AspnEventLogger logger) {
        this.eventLogger = logger;
    }

    // LCM channels
    private static final String CHN_IMU   = "ASPN/IMU";
    private static final String CHN_BARO  = "ASPN/BAROMETER";
    private static final String CHN_SATNV = "ASPN/SATNAV";

    // UI callback so MainActivity can show human-readable lines/blocks.
    public interface Listener {
        void onAspnImuPublished(measurement_IMU msg, String line);
        void onAspnBarometerPublished(measurement_barometer msg, String line);
        void onAspnSatnavPublished(measurement_satnav_with_sv_data msg, String block);
    }

    private final Context app;  // app context (safe to hold)
    private final Listener ui;  // UI sink (MainActivity implements these to draw text on screen)

    private LCM lcm;                               // the multicast bus
    private WifiManager.MulticastLock mlock;       // Android needs this lock for reliable multicast

    // Sample-and-hold IMU buffers (publish when we have at least one accel and one gyro)
    private double[] lastAccel = null; // m/s^2
    private double[] lastGyro  = null; // rad/s

    public LcmAspnBridge(Context ctx, Listener listener) {
        this.app = ctx.getApplicationContext();
        this.ui  = listener;
    }

    // Acquire LCM and multicast lock
    public void start() {
        try {
            lcm = LCM.getSingleton(); // may throw if jar not found/initialized
        } catch (Throwable t) {
            lcm = null; // we'll still produce UI + CSV; just no network publish
        }
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("lcm-aspn");
                mlock.setReferenceCounted(true);
                mlock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    public void stop() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
        mlock = null;
        lcm = null;
    }

    // ======================= BAROMETER =======================

    // hPa → ASPN Barometer (Pa). variancePa2 is optional; pass 0 if unknown.
    public void publishBarometer(float pressure_hPa, double variancePa2) {
        measurement_barometer m = new measurement_barometer();
        m.icd_measurement_barometer = 0; // ICD discriminator if used by your stack
        m.header = new type_header();    // leave defaults unless your ICD requires more
        m.time_of_validity = new type_timestamp();
        setTimestamp(m.time_of_validity);

        // ASPN expects Pascals; Android sensor gives hPa
        m.pressure = pressure_hPa * 100.0;
        m.variance = variancePa2;

        publish(CHN_BARO, m);

        if (ui != null) {
            String line = String.format(Locale.US,
                    "[ASPN/Baro] P=%.2f hPa (%.1f Pa), t=%d ns",
                    pressure_hPa, m.pressure, m.time_of_validity.elapsed_nsec);
            ui.onAspnBarometerPublished(m, line);
        }
    }

    // ======================= IMU =======================

    // Pass new accelerometer sample (m/s^2). Bridge publishes IMU when both accel+gyro are available.
    public void onAccel(float ax, float ay, float az) {
        lastAccel = new double[]{ ax, ay, az };
        maybePublishImu();
    }

    // Pass new gyroscope sample (rad/s). Bridge publishes IMU when both accel+gyro are available.
    public void onGyro(float gx, float gy, float gz) {
        lastGyro = new double[]{ gx, gy, gz };
        maybePublishImu();
    }

    // When both accel & gyro samples exist, publish one ASPN IMU message (sampled type).
    private void maybePublishImu() {
        if (lastAccel == null || lastGyro == null) return; // need both msmts

        measurement_IMU m = new measurement_IMU();
        m.icd_measurement_IMU = 0;
        m.header = new type_header();
        m.time_of_validity = new type_timestamp();
        setTimestamp(m.time_of_validity);

        // IMU type: 0=integrated (delta-v/theta), 1=sampled (this is what we have on the phone)
        // Lots of 1s...
        m.imu_type = measurement_IMU.IMU_TYPE_SAMPLED;

        // Arrays are fixed length 3
        m.meas_accel = new double[]{ lastAccel[0], lastAccel[1], lastAccel[2] };
        m.meas_gyro  = new double[]{ lastGyro[0],  lastGyro[1],  lastGyro[2]  };

        // Optional integrity omitted for now
        // m.num_integrity = 0; m.integrity = new type_integrity[0];

        publish(CHN_IMU, m);

        if (ui != null) {
            String line = String.format(Locale.US,
                    "[ASPN/IMU] a=[%.2f %.2f %.2f] m/s²  g=[%.3f %.3f %.3f] rad/s  t=%d ns",
                    m.meas_accel[0], m.meas_accel[1], m.meas_accel[2],
                    m.meas_gyro[0],  m.meas_gyro[1],  m.meas_gyro[2],
                    m.time_of_validity.elapsed_nsec);
            ui.onAspnImuPublished(m, line);
        }
    }

    // ======================= SATNAV (per-epoch) =======================

    /**
     * Begin a new SATNAV epoch builder; call addSv(...) per satellite; call publish() once to emit one message.
     * The builder collects parallel lists so the i-th entry across all lists belongs to the same satellite.
     */
    public EpochBuilder newSatnavEpoch() {
        return new EpochBuilder();
    }

    /* this bit is a tiny builder that batches all
       satellite measurements that arrive within one GNSS
       epoch (one measurement tick), so you can publish them together as a single SATNAV message.
       So we call publish() on batch at a time for each new epoch. */
    public final class EpochBuilder {
        private final List<Integer> systems = new ArrayList<>();
        private final List<Integer> svids   = new ArrayList<>();
        private final List<Double>  prs     = new ArrayList<>();
        private final List<Double>  tdcp    = new ArrayList<>();
        private final List<Double>  tdcpR   = new ArrayList<>();

        public EpochBuilder addSv(int constellation, int svid, double prMeters,
                                  Double tdcpMeters, Double tdcpRateMps) {
            systems.add(constellation);
            svids.add(svid);
            prs.add(prMeters);
            tdcp.add(tdcpMeters);
            tdcpR.add(tdcpRateMps);
            return this; // builder chaining (addSv(...).addSv(...).publish())
        }

        // Build and publish a single ASPN SATNAV message that contains *all* SVs collected this epoch.
        public void publish() {
            int n = svids.size(); // num of satellites in this epoch
            if (n == 0) return; // nothing to send

            // Build ASPN message for the whole epoch
            measurement_satnav_with_sv_data msg = new measurement_satnav_with_sv_data();
            msg.icd_measurement_satnav_with_sv_data = 0;
            msg.header = new type_header();
            msg.time_of_validity = new type_timestamp();
            setTimestamp(msg.time_of_validity);  // uses SystemClock.elapsedRealtimeNanos()

            // Receiver clock fields left at defaults (you can fill if your stack needs this)
            msg.receiver_clock_time = new type_satnav_time();
            msg.num_signal_types = 0;            // not used in this simple mapping
            msg.num_signals_tracked = n;

            // Allocate arrays for per-SV observations and metadata
            msg.obs     = new type_satnav_obs[n];
            msg.sv_data = new type_satnav_sv_data[n];

            for (int i = 0; i < n; i++) {
                int sys   = systems.get(i);
                int svid  = svids.get(i);
                Double pr = prs.get(i);
                Double dcp = tdcp.get(i);
                Double dcr = tdcpR.get(i);

                // Observation row: pseudorange + TDCP/TDCP-rate if available
                type_satnav_obs o = new type_satnav_obs();
                // Field names vary by ICD; we try a few common ones via reflection helpers:
                setDoubleIfExists(o, "pseudorange_m", pr);
                setDoubleIfExists(o, "pseudorange",   pr);
                setDoubleIfExists(o, "rho_m",         pr);

                if (dcp != null) {
                    setDoubleIfExists(o, "tdcp_m", dcp);
                    setDoubleIfExists(o, "delta_carrier_phase_m", dcp);
                    setDoubleIfExists(o, "carrier_phase_delta_m", dcp);
                }
                if (dcr != null) {
                    setDoubleIfExists(o, "tdcp_rate_mps", dcr);
                    setDoubleIfExists(o, "tdcp_rate",     dcr);
                    setDoubleIfExists(o, "delta_carrier_phase_rate_mps", dcr);
                }

                // SV metadata row: constellation/system + PRN/SVID
                type_satnav_sv_data s = new type_satnav_sv_data();
                s.prn = (short) svid;
                s.satellite_system = new type_satnav_satellite_system();
                // Different ICD versions expose different field names — try several:
                setIntIfExists(s.satellite_system, "system", sys);
                setIntIfExists(s.satellite_system, "satellite_system", sys);
                setIntIfExists(s.satellite_system, "constellation", sys);
                setIntIfExists(s.satellite_system, "constellation_id", sys);
                setIntIfExists(s.satellite_system, "gnss_id", sys);

                msg.obs[i]     = o;
                msg.sv_data[i] = s;
            }

            msg.num_integrity = 0;
            msg.integrity     = new type_integrity[0];

            // Publish on LCM and mirror to .lcmlog (publish(...) handles both) - optional
            //publish(CHN_SATNV, msg);

            // Optional: push a small human-readable summary to UI listener
            if (ui != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "[ASPN/SATNAV] SVs=%d  t=%d ns\n",
                        n, msg.time_of_validity.elapsed_nsec));
                int show = Math.min(4, n);
                for (int i = 0; i < show; i++) {
                    sb.append(String.format(Locale.US,
                            "  C=%d SVID=%d  PR=%.3f m  TDCP=%s  RATE=%s\n",
                            systems.get(i), svids.get(i),
                            prs.get(i),
                            (tdcp.get(i) != null ? String.format(Locale.US,"%.3f", tdcp.get(i)) : "—"),
                            (tdcpR.get(i) != null ? String.format(Locale.US,"%.3f", tdcpR.get(i)) : "—")));
                }
                ui.onAspnSatnavPublished(msg, sb.toString());
            }
        }
    }

    // ======================= helpers =======================

    /**
     * Publish to LCM (if available) and also mirror to .lcmlog via ApsnEventLogger (if set).
     * We catch-and-ignore all exceptions so the app never crashes due to logging/transport issues.
     */
    private void publish(String channel, Object msg) {
        try {
            if (lcm != null) {
                lcm.publish(channel, (LCMEncodable) msg);
            }
            // Mirror to .lcmlog regardless of multicast behavior:
            if (eventLogger != null) {
                long nowNs = SystemClock.elapsedRealtimeNanos();
                eventLogger.onPublish(channel, (LCMEncodable) msg, nowNs);
            }
        } catch (Throwable ignored) {
            // Ignore transport/logging failures
        }
    }

    // Fill ASPN time struct using Android's monotonic clock (keeps alignment with Sensors/GNSS APIs)
    private static void setTimestamp(type_timestamp ts) {
        ts.icd_type_timestamp = 0;
        ts.elapsed_nsec = SystemClock.elapsedRealtimeNanos(); // monotonic, aligns with sensors/GNSS
    }

    // Reflection helpers to be resilient to ICD field name differences across versions.

    private static void setDoubleIfExists(Object obj, String field, Double value) {
        if (obj == null || value == null) return;
        try {
            java.lang.reflect.Field f = obj.getClass().getField(field);
            if (f.getType() == double.class)      f.setDouble(obj, value);
            else if (f.getType() == float.class)  f.setFloat(obj, value.floatValue());
        } catch (Throwable ignored) { /* field missing in this ICD variant */ }
    }

    private static void setIntIfExists(Object obj, String field, int value) {
        if (obj == null) return;
        try {
            java.lang.reflect.Field f = obj.getClass().getField(field);
            Class<?> t = f.getType();
            if (t == int.class)      f.setInt(obj, value);
            else if (t == short.class) f.setShort(obj, (short) value);
            else if (t == byte.class)  f.setByte(obj, (byte) value);
            else if (t == long.class)  f.setLong(obj, value);
        } catch (Throwable ignored) { /* field missing in this ICD variant */ }
    }
}
