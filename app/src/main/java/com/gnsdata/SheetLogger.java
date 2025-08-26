package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/25
Github: https://github.com/incognitosushiroll/GNSSData.git

This file within the GNSSData project will write to an android's external files directory (Files app).
This class is one tiny thread for writing the GNSS data to a .csv file.

Updated: adds ASPN logging (IMU, Baro, Satnav) alongside the original RAW logs (which is commented out!). "RAW" data is the data off the phone. "ASPN data" is the data put through the lcm in aspn format.

The csv formatting portions were input from ChatGPT - I take no credit for the formatting and csv writers within the logs.
*/

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import aspn23_lcm.measurement_IMU;
import aspn23_lcm.measurement_barometer;
import aspn23_lcm.measurement_satnav_with_sv_data;
import aspn23_lcm.type_satnav_obs;
import aspn23_lcm.type_satnav_sv_data;
import aspn23_lcm.type_timestamp;

public class SheetLogger {

    // these are public getters (so we can show/share paths in UI)
    public File sensorsFile()   { return sensorsFile; }
    public File gnssFile()      { return gnssFile;    }
//    public File aspnImuFile()   { return aspnImuFile; }
//    public File aspnBaroFile()  { return aspnBaroFile; }
//    public File aspnSatnavFile(){ return aspnSatnavFile; }

    public String sensorsPath()   { return sensorsFile.getAbsolutePath(); }
    public String gnssPath()      { return gnssFile.getAbsolutePath();    }
//    public String aspnImuPath()   { return aspnImuFile.getAbsolutePath(); }
//    public String aspnBaroPath()  { return aspnBaroFile.getAbsolutePath(); }
//    public String aspnSatnavPath(){ return aspnSatnavFile.getAbsolutePath(); }

    // internal vars (RAW)
    private final File sensorsFile;
    private final File gnssFile;
    private Writer sensorsWriter;
    private Writer gnssWriter;

//    // ASPN files + writers
//    private final File aspnImuFile;
//    private final File aspnBaroFile;
//    private final File aspnSatnavFile;
//    private Writer aspnImuWriter;
//    private Writer aspnBaroWriter;
//    private Writer aspnSatnavWriter;

    private final char delimiter; // needed for comma-split values
    private final boolean writeBomOnEmpty; // friendly for excel

    // Thread-safe date/time formatters (we create fresh ones per each call bc SimpleDateFormat isn’t thread-safe).
    private static String fmtDate(long wallMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(wallMs));
    }
    private static String fmtTime(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(wallMs));
    }

    // A Java "Factory" which put logs under app-specific *external* storage (easy to grab via Files/USB; no permission). */
    public static SheetLogger atExternal(Context ctx, char delimiter, boolean bom) {
        File base = ctx.getExternalFilesDir(null);
        File dir = new File(base, "logs");
        if (!dir.exists()) dir.mkdirs();
        return new SheetLogger(
                new File(dir, "sensors_log.csv"),
                new File(dir, "gnss_log.csv"),
//                new File(dir, "aspn_imu.csv"),
//                new File(dir, "aspn_baro.csv"),
//                new File(dir, "aspn_satnav.csv"),
                delimiter, bom);
    }

    // Excel normally wants ';' when decimal separator is ','. So we use , or ; as delim and make it a basic "," //
    public static char defaultExcelDelimiterForLocale() {
        char decimalSep = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        return (decimalSep == ',') ? ';' : ',';
    }

    // Constructor which has open writers and will write headers if files empty.
    private SheetLogger(File sensors, File gnss,
                        //File aspnImu, File aspnBaro, File aspnSatnav,
                        char delimiter, boolean bom) {
        this.sensorsFile = sensors;
        this.gnssFile = gnss;
//        this.aspnImuFile = aspnImu;
//        this.aspnBaroFile = aspnBaro;
//        this.aspnSatnavFile = aspnSatnav;

        this.delimiter = delimiter;
        this.writeBomOnEmpty = bom;

        this.sensorsWriter = openWriter(sensors);
        this.gnssWriter    = openWriter(gnss);
//        this.aspnImuWriter   = openWriter(aspnImu);
//        this.aspnBaroWriter  = openWriter(aspnBaro);
//        this.aspnSatnavWriter= openWriter(aspnSatnav);

        writeSensorsHeaderIfEmpty();
        writeGnssHeaderIfEmpty();
//        writeAspnImuHeaderIfEmpty();
//        writeAspnBaroHeaderIfEmpty();
//        writeAspnSatnavHeaderIfEmpty();
    }

    // Will open the parent file if it exists, or create a new parent file and then create output streams and writers
    private Writer openWriter(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            boolean newFile = !file.exists() || file.length() == 0;
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            if (newFile && writeBomOnEmpty) {
                // UTF-8 BOM (byte order map) so Excel detects encoding (important for “m/s²” characters etc.)
                w.write('\uFEFF');
                w.flush();
            }
            return w;
        } catch (IOException e) {
            return null; // falls into a pillow of feathers
        }
    }

    // ===================== RAW: SENSORS =====================

    // Make the header: one-time, wide columns for sensors. //
    private synchronized void writeSensorsHeaderIfEmpty() {
        if (sensorsWriter == null || !isEffectivelyEmpty(sensorsFile)) return;
        writeSensorsRowRaw(
                "Date", "Time", "ElapsedNs",
                "Baro_hPa",
                "Accel_X_mps2", "Accel_Y_mps2", "Accel_Z_mps2",
                "Gyro_X_radps", "Gyro_Y_radps", "Gyro_Z_radps"
        );
    }

    // Write one VERY wide sensor row. Any null value → blank cell (so columns stay aligned, and Excel shows empty)//
    public synchronized void logSensorsWide(
            long wallMs, long elapsedNs,
            Float baro_hPa,
            Float ax, Float ay, Float az,
            Float gx, Float gy, Float gz
    ) {
        if (sensorsWriter == null) return;
        writeSensorsRowRaw(
                fmtDate(wallMs), fmtTime(wallMs), String.valueOf(elapsedNs),
                toCsv(baro_hPa),
                toCsv(ax), toCsv(ay), toCsv(az),
                toCsv(gx), toCsv(gy), toCsv(gz)
        );
    }

    // Uses the function in the GNSS section to write the sensors row
    private void writeSensorsRowRaw(Object... fields) {
        writeRow(sensorsWriter, fields);
    }

    // ===================== RAW: GNSS =====================

    // Header for GNSS per-satellite rows. //
    private synchronized void writeGnssHeaderIfEmpty() {
        if (gnssWriter == null || !isEffectivelyEmpty(gnssFile)) return;
        writeGnssRowRaw(
                "Date", "Time", "ElapsedNs",
                "Constellation", "Svid",
                "Pseudorange_m", "TDCP_m", "TDCP_rate_mps"
        );
    }

    // GNSS row per satellite per epoch, logged and written to log
    public synchronized void logGnssPerSv(
            long wallMs, long elapsedNs,
            int constellation, int svid,
            double prMeters,
            Double tdcpMeters, Double tdcpRateMps
    ) {
        if (gnssWriter == null) return;
        writeGnssRowRaw(
                fmtDate(wallMs), fmtTime(wallMs), String.valueOf(elapsedNs),
                constellation, svid,
                toCsv(prMeters), toCsv(tdcpMeters), toCsv(tdcpRateMps)
        );
    }

    // Same as Sensor fx above
    private void writeGnssRowRaw(Object... fields) {
        writeRow(gnssWriter, fields);
    }

    // ===================== ASPN: IMU =====================

    /*private synchronized void writeAspnImuHeaderIfEmpty() {
        if (aspnImuWriter == null || !isEffectivelyEmpty(aspnImuFile)) return;
        writeRow(aspnImuWriter,
                "Date","Time","ElapsedNs",
                "imu_type",
                "Accel_X_mps2","Accel_Y_mps2","Accel_Z_mps2",
                "Gyro_X_radps","Gyro_Y_radps","Gyro_Z_radps");
    }

    // Log the ASPN IMU message we published (which has both accel/gyro arrays)
    public synchronized void logAspnImu(measurement_IMU msg) {
        if (aspnImuWriter == null || msg == null) return;
        long nowMs = System.currentTimeMillis();
        long elapsed = safeElapsed(msg.time_of_validity);

        writeRow(aspnImuWriter,
                fmtDate(nowMs), fmtTime(nowMs), String.valueOf(elapsed),
                // imu_type is a byte in your class (0 = integrated, 1 = sampled)
                msg.imu_type,
                // arrays are always length 3
                fmtArrDbl(msg.meas_accel, 0), fmtArrDbl(msg.meas_accel, 1), fmtArrDbl(msg.meas_accel, 2),
                fmtArrDbl(msg.meas_gyro,  0), fmtArrDbl(msg.meas_gyro,  1), fmtArrDbl(msg.meas_gyro,  2)
        );
    }

    // ===================== ASPN: Barometer =====================

    private synchronized void writeAspnBaroHeaderIfEmpty() {
        if (aspnBaroWriter == null || !isEffectivelyEmpty(aspnBaroFile)) return;
        writeRow(aspnBaroWriter,
                "Date","Time","ElapsedNs",
                "Pressure_Pa","Variance_Pa2");
    }

    // Log the ASPN Barometer message (pressure Pa + variance).
    public synchronized void logAspnBarometer(measurement_barometer msg) {
        if (aspnBaroWriter == null || msg == null) return;
        long nowMs = System.currentTimeMillis();
        long elapsed = safeElapsed(msg.time_of_validity);

        writeRow(aspnBaroWriter,
                fmtDate(nowMs), fmtTime(nowMs), String.valueOf(elapsed),
                toCsv(msg.pressure), toCsv(msg.variance));
    }

    // ===================== ASPN: Satnav =====================

    private synchronized void writeAspnSatnavHeaderIfEmpty() {
        if (aspnSatnavWriter == null || !isEffectivelyEmpty(aspnSatnavFile)) return;
        writeRow(aspnSatnavWriter,
                "Date","Time","ElapsedNs",
                "Constellation","Svid",
                "Pseudorange_m","TDCP_m","TDCP_rate_mps");
    }

    // Log the ASPN Satnav epoch: explode arrays into per-SV rows.
    public synchronized void logAspnSatnav(measurement_satnav_with_sv_data msg) {
        if (aspnSatnavWriter == null || msg == null) return;
        long nowMs = System.currentTimeMillis();
        long elapsed = safeElapsed(msg.time_of_validity);

        int n = msg.num_signals_tracked;
        if (msg.obs == null || msg.sv_data == null) return;
        n = Math.min(n, Math.min(msg.obs.length, msg.sv_data.length));

        for (int i = 0; i < n; i++) {
            type_satnav_obs     o = msg.obs[i];
            type_satnav_sv_data s = msg.sv_data[i];

            // constellation/system code (best-effort via sv_data.satellite_system.*) — use reflection to be robust across ICD versions
            Integer systemCode = readIntLike(
                    (s != null ? s.satellite_system : null),
                    new String[]{"system","satellite_system","id","constellation","constellation_id","gnss_id"});
            if (systemCode == null) systemCode = 0;

            // svid/prn (prefer sv_data.prn); note that "prn" is a variable for pseudorange used within the aspn_messages.jar classes
            Integer svid = (s != null) ? (int) s.prn : readIntLike(o, new String[]{"svid","sv_id","prn","satellite_id"});
            if (svid == null) svid = -1;

            // pseudorange + TDCP (from obs; field names vary by schema) — reflection keeps us flexible
            Double pr  = readDouble(o, new String[]{"pseudorange_m","pseudorange","rho_m"});
            Double dcp = readDouble(o, new String[]{"tdcp_m","delta_carrier_phase_m","carrier_phase_delta_m"});
            Double dcr = readDouble(o, new String[]{"tdcp_rate_mps","tdcp_rate","delta_carrier_phase_rate_mps"});

            writeRow(aspnSatnavWriter,
                    fmtDate(nowMs), fmtTime(nowMs), String.valueOf(elapsed),
                    systemCode, svid,
                    toCsv(pr), toCsv(dcp), toCsv(dcr));
        }
    } */

    // CSV glue

    private void writeRow(Writer w, Object... fields) {
        if (w == null) return;
        try {
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) w.write(delimiter);
                w.write(escapeCsvField(fields[i]));
            }
            w.write("\r\n"); // CRLF is Excel-friendly
            w.flush();
        } catch (IOException ignored) {}
    }

    // Convert Java object into a CSV cell; numbers use Locale.US to ensure '.' decimal point. If Null, leave blank
    private String escapeCsvField(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof Float || v instanceof Double) {
            s = String.format(Locale.US, "%.9f", ((Number) v).doubleValue());
            s = s.indexOf('.') >= 0 ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        } else {
            s = String.valueOf(v);
        }
        boolean needsQuotes = s.indexOf(delimiter) >= 0 || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (needsQuotes) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // Add this method inside SheetLogger (e.g., near other helpers)
    private boolean isEffectivelyEmpty(File f) {
        long len = f.length();
        // If we chose to write a BOM, a just-created file will be 3 bytes long.
        return len == 0 || (writeBomOnEmpty && len <= 3);
    }

    // Ran into some issues with headers not appearing, this was added to push them on
    public synchronized void ensureHeaders() {
        writeSensorsHeaderIfEmpty();
        writeGnssHeaderIfEmpty();
//        writeAspnImuHeaderIfEmpty();
//        writeAspnBaroHeaderIfEmpty();
//        writeAspnSatnavHeaderIfEmpty();
    }
    // getting the correct format...

    private static String toCsv(Float f)  { return f == null ? "" : String.format(Locale.US, "%.6f", f); }
    private static String toCsv(Double d) { return d == null ? "" : String.format(Locale.US, "%.9f", d); }
    private static String toCsv(double d) { return String.format(Locale.US, "%.9f", d); }

    // Helpers for ASPN payloads, not used
    private static long safeElapsed(type_timestamp ts) {
        return (ts != null) ? ts.elapsed_nsec : 0L;
    }
    private static String fmtArrDbl(double[] a, int idx) {
        if (a == null || a.length <= idx) return "";
        return String.format(Locale.US, "%.9f", a[idx]);
    }
    private static Integer readIntLike(Object obj, String[] names) {
        if (obj == null) return null;
        for (String n : names) {
            try {
                java.lang.reflect.Field f = obj.getClass().getField(n);
                Class<?> t = f.getType();
                if (t == int.class)    return f.getInt(obj);
                if (t == short.class)  return (int) f.getShort(obj);
                if (t == byte.class)   return (int) f.getByte(obj);
                if (t == long.class)   return (int) f.getLong(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }
    private static Double readDouble(Object obj, String[] names) {
        if (obj == null) return null;
        for (String n : names) {
            try {
                java.lang.reflect.Field f = obj.getClass().getField(n);
                Class<?> t = f.getType();
                if (t == double.class) return f.getDouble(obj);
                if (t == float.class)  return (double) f.getFloat(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // Graceful exit for this chunky beast
    public synchronized void close() {
        try { if (sensorsWriter    != null) sensorsWriter.close();    } catch (IOException ignored) {}
        try { if (gnssWriter       != null) gnssWriter.close();       } catch (IOException ignored) {}
//        try { if (aspnImuWriter    != null) aspnImuWriter.close();    } catch (IOException ignored) {}
//        try { if (aspnBaroWriter   != null) aspnBaroWriter.close();   } catch (IOException ignored) {}
//        try { if (aspnSatnavWriter != null) aspnSatnavWriter.close(); } catch (IOException ignored) {}
        sensorsWriter = null;
        gnssWriter = null;
//        aspnImuWriter = null;
//        aspnBaroWriter = null;
//        aspnSatnavWriter = null;
    }
}
