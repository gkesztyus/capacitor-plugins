package com.capacitorjs.plugins.geolocation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(
    name = "Geolocation",
    permissions = {
        @Permission(
            strings = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION },
            alias = GeolocationPlugin.LOCATION
        ),
        @Permission(strings = { Manifest.permission.ACCESS_COARSE_LOCATION }, alias = GeolocationPlugin.COARSE_LOCATION)
    }
)
public class GeolocationPlugin extends Plugin {

    static final String LOCATION = "location";
    static final String COARSE_LOCATION = "coarseLocation";
    private Geolocation implementation;
    private Map<String, PluginCall> watchingCalls = new HashMap<>();
    private GeolocationBackgroundService.LocalBinder backgroundService;
    private ServiceConnection backgroundServiceConnection;
    private BroadcastReceiver backgroundLocationReceiver;

    @Override
    public void load() {
        implementation = new Geolocation(getContext());
        createBackgroundNotificationChannel();

        backgroundServiceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    backgroundService = (GeolocationBackgroundService.LocalBinder) binder;
                    for (PluginCall call : watchingCalls.values()) {
                        if (call.getBoolean("background", false)) {
                            startBackgroundWatch(call);
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    backgroundService = null;
                }
            };
        getContext()
            .bindService(
                new Intent(getContext(), GeolocationBackgroundService.class),
                backgroundServiceConnection,
                Context.BIND_AUTO_CREATE
            );

        backgroundLocationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String callbackId = intent.getStringExtra("id");
                    PluginCall call = watchingCalls.get(callbackId);
                    Location location = intent.getParcelableExtra("location");
                    if (call != null && location != null) {
                        call.resolve(getJSObjectForLocation(location));
                    }
                }
            };
        LocalBroadcastManager
            .getInstance(getContext())
            .registerReceiver(backgroundLocationReceiver, new IntentFilter(GeolocationBackgroundService.ACTION_LOCATION));
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        implementation.clearLocationUpdates();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        for (PluginCall call : watchingCalls.values()) {
            if (!call.getBoolean("background", false)) {
                startWatch(call);
            }
        }
    }

    @Override
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        if (implementation.isLocationServicesEnabled()) {
            super.checkPermissions(call);
        } else {
            call.reject("Location services are not enabled");
        }
    }

    @Override
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (implementation.isLocationServicesEnabled()) {
            super.requestPermissions(call);
        } else {
            call.reject("Location services are not enabled");
        }
    }

    /**
     * Gets a snapshot of the current device position if permission is granted. The call continues
     * in the {@link #completeCurrentPosition(PluginCall)} method if a permission request is required.
     *
     * @param call Plugin call
     */
    @PluginMethod
    public void getCurrentPosition(final PluginCall call) {
        String alias = getAlias(call);
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias, call, "completeCurrentPosition");
        } else {
            getPosition(call);
        }
    }

    /**
     * Completes the getCurrentPosition plugin call after a permission request
     * @see #getCurrentPosition(PluginCall)
     * @param call the plugin call
     */
    @PermissionCallback
    private void completeCurrentPosition(PluginCall call) {
        if (getPermissionState(GeolocationPlugin.COARSE_LOCATION) == PermissionState.GRANTED) {
            implementation.sendLocation(
                isHighAccuracy(call),
                new LocationResultCallback() {
                    @Override
                    public void success(Location location) {
                        call.resolve(getJSObjectForLocation(location));
                    }

                    @Override
                    public void error(String message) {
                        call.reject(message);
                    }
                }
            );
        } else {
            call.reject("Location permission was denied");
        }
    }

    /**
     * Begins watching for live location changes if permission is granted. The call continues
     * in the {@link #completeWatchPosition(PluginCall)} method if a permission request is required.
     *
     * @param call the plugin call
     */
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void watchPosition(PluginCall call) {
        call.setKeepAlive(true);
        String alias = getAlias(call);
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias, call, "completeWatchPosition");
        } else {
            startWatch(call);
        }
    }

    /**
     * Completes the watchPosition plugin call after a permission request
     * @see #watchPosition(PluginCall)
     * @param call the plugin call
     */
    @PermissionCallback
    private void completeWatchPosition(PluginCall call) {
        if (getPermissionState(GeolocationPlugin.COARSE_LOCATION) == PermissionState.GRANTED) {
            startWatch(call);
        } else {
            call.reject("Location permission was denied");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void getPosition(PluginCall call) {
        int maximumAge = call.getInt("maximumAge", 0);
        Location location = implementation.getLastLocation(maximumAge);
        if (location != null) {
            call.resolve(getJSObjectForLocation(location));
        } else {
            implementation.sendLocation(
                isHighAccuracy(call),
                new LocationResultCallback() {
                    @Override
                    public void success(Location location) {
                        call.resolve(getJSObjectForLocation(location));
                    }

                    @Override
                    public void error(String message) {
                        call.reject(message);
                    }
                }
            );
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startWatch(final PluginCall call) {
        long interval = Math.max(call.getLong("interval", 1000L), 0L);
        long minimumUpdateInterval = Math.min(Math.max(call.getLong("minimumUpdateInterval", Math.max(interval / 2, 0L)), 0L), interval);
        long maximumUpdateDelay = Math.max(call.getLong("maximumUpdateDelay", interval), interval);
        float minimumUpdateDistance = Math.max(call.getFloat("minimumUpdateDistance", 0f), 0f);

        watchingCalls.put(call.getCallbackId(), call);
        if (call.getBoolean("background", false)) {
            startBackgroundWatch(call);
            return;
        }

        implementation.requestLocationUpdates(
            isHighAccuracy(call),
            interval,
            minimumUpdateInterval,
            maximumUpdateDelay,
            minimumUpdateDistance,
            new LocationResultCallback() {
                @Override
                public void success(Location location) {
                    call.resolve(getJSObjectForLocation(location));
                }

                @Override
                public void error(String message) {
                    call.reject(message);
                }
            }
        );
    }

    private void startBackgroundWatch(PluginCall call) {
        if (backgroundService == null) {
            return;
        }

        long interval = Math.max(call.getLong("interval", 1000L), 0L);
        long minimumUpdateInterval = Math.min(Math.max(call.getLong("minimumUpdateInterval", Math.max(interval / 2, 0L)), 0L), interval);
        long maximumUpdateDelay = Math.max(call.getLong("maximumUpdateDelay", interval), interval);
        float minimumUpdateDistance = Math.max(call.getFloat("minimumUpdateDistance", 0f), 0f);

        if (
            !backgroundService.addWatcher(
                call.getCallbackId(),
                createBackgroundNotification(call),
                isHighAccuracy(call),
                interval,
                minimumUpdateInterval,
                maximumUpdateDelay,
                minimumUpdateDistance
            )
        ) {
            watchingCalls.remove(call.getCallbackId());
            call.reject("Failed to start background location updates");
        }
    }

    /**
     * Removes an active geolocation watch.
     *
     * @param call Plugin call
     */
    @SuppressWarnings("MissingPermission")
    @PluginMethod
    public void clearWatch(PluginCall call) {
        String callbackId = call.getString("id");

        if (callbackId != null) {
            PluginCall removed = watchingCalls.remove(callbackId);
            if (removed != null) {
                if (removed.getBoolean("background", false) && backgroundService != null) {
                    backgroundService.removeWatcher(callbackId);
                }
                removed.release(bridge);
            }

            if (watchingCalls.size() == 0) {
                implementation.clearLocationUpdates();
            }

            call.resolve();
        } else {
            call.reject("Watch call id must be provided");
        }
    }

    private JSObject getJSObjectForLocation(Location location) {
        JSObject ret = new JSObject();
        JSObject coords = new JSObject();
        ret.put("coords", coords);
        ret.put("timestamp", location.getTime());
        coords.put("latitude", location.getLatitude());
        coords.put("longitude", location.getLongitude());
        coords.put("accuracy", location.getAccuracy());
        coords.put("altitude", location.getAltitude());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            coords.put("altitudeAccuracy", location.getVerticalAccuracyMeters());
        }
        coords.put("speed", location.getSpeed());
        coords.put("heading", location.getBearing());
        return ret;
    }

    private void createBackgroundNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
            GeolocationBackgroundService.class.getName(),
            "Játék közbeni helymegosztás",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private Notification createBackgroundNotification(PluginCall call) {
        String title = call.getString("backgroundTitle", "GrillParty helymegosztás");
        String message = call.getString("backgroundMessage", "A játék alatt megosztjuk a pozíciódat.");
        Notification.Builder builder = new Notification.Builder(getContext())
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setSmallIcon(getContext().getApplicationInfo().icon)
            .setCategory(Notification.CATEGORY_SERVICE);

        Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
        if (launchIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(getContext(), 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(GeolocationBackgroundService.class.getName());
        }
        return builder.build();
    }

    @Override
    protected void handleOnDestroy() {
        if (backgroundService != null) {
            backgroundService.stopService();
        }
        if (backgroundLocationReceiver != null) {
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(backgroundLocationReceiver);
        }
        if (backgroundServiceConnection != null) {
            getContext().unbindService(backgroundServiceConnection);
        }
        super.handleOnDestroy();
    }

    private String getAlias(PluginCall call) {
        String alias = GeolocationPlugin.LOCATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean enableHighAccuracy = call.getBoolean("enableHighAccuracy", false);
            if (!enableHighAccuracy) {
                alias = GeolocationPlugin.COARSE_LOCATION;
            }
        }
        return alias;
    }

    private boolean isHighAccuracy(PluginCall call) {
        boolean enableHighAccuracy = call.getBoolean("enableHighAccuracy", false);
        return enableHighAccuracy && getPermissionState(GeolocationPlugin.LOCATION) == PermissionState.GRANTED;
    }
}
