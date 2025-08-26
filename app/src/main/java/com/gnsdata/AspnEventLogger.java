package com.gnsdata;

/*
Created by: Katki
Date: 08/14 - 08/25
Github: https://github.com/incognitosushiroll/GNSSData.git


Logs ASPN-formatted messages (IMU, Barometer, Satnav) to an on-device LCM log file (.lcmlog).

How it works:
 - Opens lcm.logging.Log at <external files>/logs/aspn.lcmlog (note that when testing on a Google Pixel these files were found in:
        Internal storage > "Google Pixel 6" > system traces > Android > com.gnsdata > data > logs
- Subscribes to ASPN channels (so messages from this app or other publishers get captured)
- Also exposes onPublish(...) so the bridge can explicitly mirror the message to the log
  (helpful on Android where multicast reception can be flaky).
- File format is the standard LCM log format. Use LCM tools to replay/inspect.
*/

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import lcm.lcm.LCM;
import lcm.lcm.LCMDataInputStream;
import lcm.lcm.LCMEncodable;
import lcm.lcm.LCMSubscriber;
import lcm.logging.Log;

// ASPN generated (from aspn_messages.jar)
import aspn23_lcm.measurement_IMU;
import aspn23_lcm.measurement_barometer;
import aspn23_lcm.measurement_satnav_with_sv_data;

// pulls from files inside LCMSubscriber to subscribe to ASPN channels and write to logs.
public class AspnEventLogger implements LCMSubscriber {

    // Use the same channel names as in your bridge class.
    public static final String CHN_IMU   = "ASPN/IMU";
    public static final String CHN_BARO  = "ASPN/BAROMETER";
    public static final String CHN_SATNV = "ASPN/SATNAV";

    private final Context app;
    private LCM lcm;
    private Log log;                // lcm.logging.Log writer
    private File logFile;           // absolute file where we write
    private boolean running = false;

    public AspnEventLogger(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    // Where the .lcmlog lives (file path is described above).
    public String logPath() {
        if (logFile != null) return logFile.getAbsolutePath();
        File base = app.getExternalFilesDir(null);
        File dir  = new File(base, "logs");
        return new File(dir, "aspn.lcmlog").getAbsolutePath(); // file name
    }

    // open the .lcmlog and subscribe to ASPN channels. Call in onResume() of MainActivity.java
    public void start() {
        if (running) return;
        try {
            // Prepare file path
            File base = app.getExternalFilesDir(null);
            File dir  = new File(base, "logs");
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "aspn.lcmlog");

            // Open for read/write (LCM Log API expects "rw")
            log = new Log(logFile.getAbsolutePath(), "rw");

            // Get LCM singleton and subscribe to channels we care about
            lcm = LCM.getSingleton();
            lcm.subscribe(CHN_IMU,   this); // accel & gyro
            lcm.subscribe(CHN_BARO,  this); // pressure
            lcm.subscribe(CHN_SATNV, this); // tdcp and pr

            running = true;
        } catch (IOException ioe) {
            // If we can't open the file, we remain non-running (no throws to keep app alive)
            log = null;
            running = false;
        } catch (Throwable t) {
            // LCM not available? We'll still allow explicit onPublish() mirroring if log!=null
        }
    }

    // must unsubscribe and close the .lcmlog. Call in onPause()/onDestroy() in MainActivity.java
    public void stop() {
        if (!running) return;
        try {
            if (lcm != null) {
                lcm.unsubscribe(CHN_IMU,   this);
                lcm.unsubscribe(CHN_BARO,  this);
                lcm.unsubscribe(CHN_SATNV, this);
            }
        } catch (Throwable ignored) {}
        try {
            if (log != null) log.close();
        } catch (IOException ignored) {}
        running = false;
        lcm = null;
        log = null;
    }

    // This is the LCMSubscriber callback: it fires when ANY publisher (your app or others) sends a message.
    // We decode by channel -> message class, then write to .lcmlog using Log.write(utime, channel, msg).
    @Override
    public void messageReceived(LCM lcm, String channel, LCMDataInputStream dins) { // dins = data input stream
        if (log == null) return;
        long utimeMicros = SystemClock.elapsedRealtimeNanos() / 1000L; // LCM tools expect microseconds, so convert

        try {
            if (CHN_IMU.equals(channel)) {
                measurement_IMU msg = new measurement_IMU(dins);
                log.write(utimeMicros, channel, msg);
            } else if (CHN_BARO.equals(channel)) {
                measurement_barometer msg = new measurement_barometer(dins);
                log.write(utimeMicros, channel, msg);
            } else if (CHN_SATNV.equals(channel)) {
                measurement_satnav_with_sv_data msg = new measurement_satnav_with_sv_data(dins);
                log.write(utimeMicros, channel, msg);
            } else {
                // Unknown ASPN channel? Skip (or add cases).
            }
        } catch (IOException e) {
            // Ignore decode/write errors (keeps app stable)
        }
    }

    // Explicit mirror from the bridge class.
    // Call this right after a publish so we log even if multicast reception is blocked on Android.
    public void onPublish(String channel, LCMEncodable msg, long elapsedNanos) {
        if (log == null) return;
        long utimeMicros = elapsedNanos / 1000L; // convert to microseconds for lcm
        try {
            log.write(utimeMicros, channel, msg);
        } catch (IOException ignored) {}
    }
}

