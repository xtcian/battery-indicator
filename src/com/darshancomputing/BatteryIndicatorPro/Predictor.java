/*
    Copyright (c) 2012-2013 Darshan-Josiah Barber

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

package com.darshancomputing.BatteryIndicatorPro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

public class Predictor {
    private static final int DISCHARGE    = 0;
    private static final int RECHARGE_AC  = 1;
    private static final int RECHARGE_WL  = 2;
    private static final int RECHARGE_USB = 3;

    public static final int ONE_MINUTE      = 60 * 1000;
    public static final int FIVE_MINUTES    = ONE_MINUTE *  5;
    public static final int TEN_MINUTES     = ONE_MINUTE * 10;
    public static final int FIFTEEN_MINUTES = ONE_MINUTE * 15;
    public static final int THIRTY_MINUTES  = ONE_MINUTE * 30;
    public static final int ONE_HOUR        = ONE_MINUTE * 60;
    public static final int TWO_HOURS       = ONE_HOUR *  2;
    public static final int THREE_HOURS     = ONE_HOUR *  3;
    public static final int FOUR_HOURS      = ONE_HOUR *  4;
    public static final int SIX_HOURS       = ONE_HOUR *  6;
    public static final int EIGHT_HOURS     = ONE_HOUR *  8;
    public static final int TWELVE_HOURS    = ONE_HOUR * 12;
    public static final int ONE_DAY         = ONE_HOUR * 24;

    public static final int TYPE_FIVE_MINUTES    =  1;
    public static final int TYPE_TEN_MINUTES     =  2;
    public static final int TYPE_FIFTEEN_MINUTES =  3;
    public static final int TYPE_THIRTY_MINUTES  =  4;
    public static final int TYPE_ONE_HOUR        =  5;
    public static final int TYPE_TWO_HOURS       =  6;
    public static final int TYPE_THREE_HOURS     =  7;
    public static final int TYPE_FOUR_HOURS      =  8;
    public static final int TYPE_SIX_HOURS       =  9;
    public static final int TYPE_EIGHT_HOURS     = 10;
    public static final int TYPE_TWELVE_HOURS    = 11;
    public static final int TYPE_ONE_DAY         = 12;
    public static final int TYPE_STATUS_CHANGE   = 13;
    public static final int TYPE_LONG_TERM       = 14;

    private static final double WEIGHT_OLD_AVERAGE = 0.998;
    private static final double WEIGHT_NEW_DATA =  1 - WEIGHT_OLD_AVERAGE;

    private static final int[] DEFAULT = { 24 * 60 * 60 * 1000 / 100,
                                            3 * 60 * 60 * 1000 / 100,
                                            4 * 60 * 60 * 1000 / 100,
                                            6 * 60 * 60 * 1000 / 100 };

    private static final String[] KEY_AVERAGE = { "key_ave_discharge",
                                                  "key_ave_recharge_ac",
                                                  "key_ave_recharge_wl",
                                                  "key_ave_recharge_usb" };

    private int recent_duration = FIVE_MINUTES;
    private int prediction_type = TYPE_FIVE_MINUTES;

    private long[] timestamps = new long[101];
    private int ts_head;

    private double[] average = new double[4];

    private double recent_average;

    private int last_level;
    private int last_status;
    private int last_plugged;
    private int last_charging_status;
    private int dir_inc; // -1 if charging; 1 if discharging; unspecified otherwise. For iterating over timestamps.

    private SharedPreferences sp_store;
    private SharedPreferences.Editor editor;

    public Predictor(Context context) {
        sp_store = context.getSharedPreferences("predictor_sp_store", 0);
        editor = sp_store.edit();

        average[DISCHARGE]    = sp_store.getFloat(KEY_AVERAGE[DISCHARGE],    DEFAULT[DISCHARGE]);
        average[RECHARGE_AC]  = sp_store.getFloat(KEY_AVERAGE[RECHARGE_AC],  DEFAULT[RECHARGE_AC]);
        average[RECHARGE_WL]  = sp_store.getFloat(KEY_AVERAGE[RECHARGE_WL],  DEFAULT[RECHARGE_WL]);
        average[RECHARGE_USB] = sp_store.getFloat(KEY_AVERAGE[RECHARGE_USB], DEFAULT[RECHARGE_USB]);
    }

    public void update(BatteryInfo info) {
        if (info.status != last_status ||
            info.plugged != last_plugged ||
            info.status == BatteryInfo.STATUS_FULLY_CHARGED ||
            (info.status == BatteryInfo.STATUS_CHARGING && info.percent < ts_head) ||
            (info.status == BatteryInfo.STATUS_DISCHARGING && info.percent > ts_head))
        {
            ts_head = info.percent;
            dir_inc = info.status == BatteryInfo.STATUS_CHARGING ? -1 : 1;

            timestamps[info.percent] = SystemClock.elapsedRealtime();

            setLasts(info);
            updateInfoPrediction(info);
            return;
        }

        if ((info.status == BatteryInfo.STATUS_CHARGING && info.percent < last_level) ||
            (info.status == BatteryInfo.STATUS_DISCHARGING && info.percent > last_level))
        {
            setLasts(info);
            updateInfoPrediction(info);
            return;
        }

        int level_diff = Math.abs(last_level - info.percent);

        if (level_diff != 0) {
            long now = SystemClock.elapsedRealtime();
            timestamps[info.percent] = now;

            long last_ms = timestamps[info.percent] - timestamps[info.percent + dir_inc]; // TODO: I'm pretty sure this is safe indexing...
            double ms_per_point = ((double) (now - last_ms)) / level_diff;

            int charging_status = chargingStatusFor(info.status, info.plugged);

            // Initial level change may happen promptly and should not shorten prediction
            if (Math.abs(ts_head - info.percent) <= 1 && ms_per_point < average[charging_status]) {
                setLasts(info);
                return;
            }

            for (int i = 0; i < level_diff; i++) {
                average[charging_status] = average[charging_status] * WEIGHT_OLD_AVERAGE + ms_per_point * WEIGHT_NEW_DATA;
                editor.putFloat(KEY_AVERAGE[charging_status], (float) average[charging_status]);
            }
            editor.commit();
        }

        setLasts(info);
        updateInfoPrediction(info);
    }

    private void updateInfoPrediction(BatteryInfo info) {
        int secs_left;

        if (info.status == BatteryInfo.STATUS_CHARGING)
            secs_left = secondsUntilCharged();
        else
            secs_left = secondsUntilDrained();

        info.prediction.update(secs_left);
    }

    public int secondsUntilDrained() {
        if (last_status != BatteryInfo.STATUS_UNPLUGGED) {
            return -1;
        }

        double predicted = recentAverage();

        return (int) (predicted * last_level / 1000);
    }

    public int secondsUntilCharged() {
        if (last_status == BatteryInfo.STATUS_FULLY_CHARGED) {
            return 0;
        }

        if (last_status != BatteryInfo.STATUS_CHARGING) {
            return -1;
        }

        return (int) ((100 - last_level) * recentAverage() / 1000);
    }

    private void setLasts(BatteryInfo info) {
        last_level = info.percent; // TODO: Resolve level/percent discrepancy?
        last_status = info.status;
        last_plugged = info.plugged;
        last_charging_status = chargingStatusFor(last_status, last_plugged);
    }

    private double recentAverage() {
        double total_points = 0d;
        double total_ms = 0d;
        double needed_ms = recent_duration;

        for (int i = last_level - dir_inc; i != ts_head; i += dir_inc) {
            double t;
            if (i == last_level - dir_inc)
                t = SystemClock.elapsedRealtime() - timestamps[last_level];
            else
                t = timestamps[i] - timestamps[i + dir_inc];

            if (t > needed_ms) {
                total_points += needed_ms / t;
                total_ms += needed_ms;
                needed_ms = 0;
                break;
            }

            total_points += 1;
            total_ms += t;
            needed_ms -= t;
        }

        if (needed_ms > 0)
            total_points += needed_ms / average[last_charging_status];

        recent_average = recent_duration / total_points;
        return recent_average;
    }

    private int chargingStatusFor(int status, int plugged) {
        if (status == BatteryInfo.STATUS_CHARGING) {
            if (plugged == BatteryInfo.PLUGGED_USB)
                return RECHARGE_USB;
            else if (plugged == BatteryInfo.PLUGGED_WIRELESS)
                return RECHARGE_WL;
            else
                return RECHARGE_AC;
        } else {
            return DISCHARGE;
        }
    }
}
