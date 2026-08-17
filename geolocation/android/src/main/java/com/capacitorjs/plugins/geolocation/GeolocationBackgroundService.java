package com.capacitorjs.plugins.geolocation;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.Logger;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.util.HashMap;
import java.util.Map;

public class GeolocationBackgroundService extends Service {

    static final String ACTION_LOCATION = "com.capacitorjs.plugins.geolocation.LOCATION";
    private static final int NOTIFICATION_ID = 28352;
    private final IBinder binder = new LocalBinder();
    private final Map<String, Watcher> watchers = new HashMap<>();

    private static class Watcher {

        FusedLocationProviderClient client;
        LocationCallback callback;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopAllWatchers();
        stopSelf();
        return false;
    }

    private void stopAllWatchers() {
        for (Watcher watcher : watchers.values()) {
            watcher.client.removeLocationUpdates(watcher.callback);
        }
        watchers.clear();
        stopForeground(true);
    }

    public class LocalBinder extends Binder {

        @SuppressWarnings("MissingPermission")
        boolean addWatcher(
            String id,
            Notification notification,
            boolean enableHighAccuracy,
            long interval,
            long minimumUpdateInterval,
            long maximumUpdateDelay,
            float minimumUpdateDistance
        ) {
            removeWatcher(id);

            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(GeolocationBackgroundService.this);
            LocationRequest request = new LocationRequest.Builder(interval)
                .setMinUpdateIntervalMillis(minimumUpdateInterval)
                .setMaxUpdateDelayMillis(maximumUpdateDelay)
                .setMinUpdateDistanceMeters(minimumUpdateDistance)
                .setPriority(enableHighAccuracy ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build();
            LocationCallback callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    Location location = result.getLastLocation();
                    if (location == null) {
                        return;
                    }

                    Intent intent = new Intent(ACTION_LOCATION);
                    intent.putExtra("id", id);
                    intent.putExtra("location", location);
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
                }
            };

            Watcher watcher = new Watcher();
            watcher.client = client;
            watcher.callback = callback;
            watchers.put(id, watcher);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                client.requestLocationUpdates(request, callback, null);
                return true;
            } catch (Exception error) {
                watchers.remove(id);
                client.removeLocationUpdates(callback);
                if (watchers.isEmpty()) {
                    stopForeground(true);
                }
                Logger.error("Failed to start background location updates", error);
                return false;
            }
        }

        void removeWatcher(String id) {
            Watcher watcher = watchers.remove(id);
            if (watcher != null) {
                watcher.client.removeLocationUpdates(watcher.callback);
            }
            if (watchers.isEmpty()) {
                stopForeground(true);
            }
        }

        void stopService() {
            stopAllWatchers();
            GeolocationBackgroundService.this.stopSelf();
        }
    }
}
