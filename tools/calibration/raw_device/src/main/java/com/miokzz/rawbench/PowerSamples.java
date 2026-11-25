package com.miokzz.rawbench;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

final class PowerSamples {
    private final Context context;
    private final BatteryManager battery;
    private final PowerManager power;

    PowerSamples(Context context) {
        this.context = context;
        battery = context.getSystemService(BatteryManager.class);
        power = context.getSystemService(PowerManager.class);
    }

    JSONObject snapshot() throws Exception {
        Intent state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject sample = new JSONObject().put("timeNs", SystemClock.elapsedRealtimeNanos())
                .put("thermalStatus", power.getCurrentThermalStatus()).put("interactive", power.isInteractive())
                .put("currentUa", value(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                .put("chargeUah", value(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                .put("energyNwh", value(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER));
        if (state != null) {
            sample.put("voltageMv", state.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
                    .put("temperatureDeciC", state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                    .put("plugged", state.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
        }
        return sample;
    }

    private Object value(int property) {
        long value = battery.getLongProperty(property);
        return value == Long.MIN_VALUE ? JSONObject.NULL : value;
    }

    JSONObject window(int seconds, Runnable work) throws Exception {
        var sampler = Executors.newSingleThreadScheduledExecutor();
        var samples = new ArrayList<JSONObject>();
        var error = new AtomicReference<Exception>();
        samples.add(snapshot());
        sampler.scheduleAtFixedRate(() -> {
            try {
                samples.add(snapshot());
            } catch (Exception failure) {
                error.compareAndSet(null, failure);
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
        long stop = SystemClock.elapsedRealtime() + seconds * 1000L;
        int frames = 0;
        try {
            if (work == null) {
                SystemClock.sleep(seconds * 1000L);
            } else {
                while (SystemClock.elapsedRealtime() < stop) {
                    work.run();
                    frames++;
                }
            }
        } finally {
            sampler.shutdownNow();
            if (!sampler.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Could not stop power sampler");
            }
        }
        samples.add(snapshot());
        if (error.get() != null) throw error.get();
        return new JSONObject().put("frames", frames).put("samples", new JSONArray(samples));
    }
}
