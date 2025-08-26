
# Global Navigation Satellite System (GNSS) Data Extractor for Android Phones

The "main" branch contains code for an Android application that will display barometer, gyro, acceleration, and satellite navigation data (described below, includes both TDCP and Pseudorange), as well as logs this data in two csv logs, one for gnss data and one for sensor data. 

The "detached" branch contains code for an Android application that will still display the above stats, but now incorporates the Lightweight Communications and Marshalling (LCM: http://lcm-proj.github.io/lcm/) system for inter-process message passing. The app formats the data into the All-Source POsitioning and Navigation (ASPN: https://www.aspn.us/) format and publishes that data through the LCM subscriber, logging it to a .lcmlog file.

# Set-Up 

1. Install lcm.jar following the steps here: https://lcm-proj.github.io/lcm/content/install-instructions.html#installing-lcm (note that I did this development on an Ubuntu system, Windows system set-up for lcm.jar wasn't friendly). 
2. Download the "aspn_messages.jar" file from aspn - you will need an account through your org's aspn admin. 
3. Download the newest version of Android Studio for SDK development: https://developer.android.com/studio 
***The phone you are using for development will need to be connected to WiFi to retrieve GNSS data***


# Background 

This project aims to 1) perform GPS Data gleaning and logging methods, and 2) teach the programmer further java skills! Built-in AI was used to help design the files in "res", "androidmanifest.xml", and any files that helped paint style/formatting of the app. 


# Tools used for project:
1. Github repo
2. Android Studio
3. Google pixel phone + USB cable and Pixel 9 emulator
4. Used a .java file instead of a Kotlin file (deleted)
5. aspn_messages.jar and lcm.jar 
6. Android Studio's built-in Gemini for code debugging 

# Files included:
- SensorGnssListener.java    (collects sensors + GNSS and calls back into this Activity)
- LcmApsnBridge.java         (turns our data into ASPN messages and publishes over LCM)
- MainActivity.java          (handles app activity)
- AspnEventLogger.java (used to publish aspn data to lcm logger)
- SheetLogger.java (saves all raw data (not aspn) to .csvs)

# FOUR Desired GPS Measurements:
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
                    Note: GPS Epoch is a continuous time system for all satellites and observation systems. It is represented in seconds since Jan 6, 1980 at 00:00:00 UTC.



# Logs 
Because this app was first tested on a Google Pixel 6, the logs were found in Files > system traces> Android > tap the hamburger in top left > Google Pixel 6 > Android > data > com.gnsdata > files > logs 
- "gnss_log.csv" : contains all satnav data, which includes date/time, satellite constellation, satellite vehicle number, pseudorange, and tdcp 
- "sensors_log.csv" : contains all barometer, accel, and gyro information. 
- "aspn.lcmlog" : contains the published aspn data using the lcm.logging class from the lcm.jar 

# Sources 
- GNSLogger repo for understanding set-up and logic for this repo: https://github.com/google/gps-measurement-tools
- Android Studio docs: https://developer.android.com/studio
- Java sensor logging docs: https://developer.android.com/develop/sensors-and-location
- GPS Data/measurements crash course: 1) https://www.spirent.com/blogs/2011-01-25_what_is_pseudorange, 2) https://www.mdpi.com/1999-4893/17/1/2 (good to look at using Doppler or TDCP-based velocity determination), 2) helpful link for understanding pseudorange: https://stackoverflow.com/questions/54904681/how-to-compute-pseudorange-from-the-parameters-fetched-via-google-gnsslogger)
- Pseudoranges and clock-bias correction: https://web.gps.caltech.edu/classes/ge111/Docs/GPSbasics.pdf (chunky but I had ChatGPT sum up how to fix GPS constellation block bias errors in my PR calc)
- Basic Java to csv: https://www.baeldung.com/java-csv
- aspn website above 
- lcm documentation: https://lcm-proj.github.io/lcm/content/log-file-format.html


