package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.BuildConfig;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.motion.HumanMotionConfig;
import com.zcshou.motion.ProducerEvidenceRecorder;
import com.zcshou.motion.ProducerSessionMetadata;
import com.zcshou.motion.SyntheticMotionCoordinator;
import com.zcshou.motion.SyntheticMotionStatus;
import com.zcshou.route.LocationStateArbiter;
import com.zcshou.route.RoutePlan;
import com.zcshou.route.RoutePlaybackController;
import com.zcshou.route.RouteSample;
import com.zcshou.route.RouteSessionState;
import com.zcshou.route.RouteSnapshot;
import com.zcshou.route.RouteStartResult;
import com.zcshou.route.ServiceLocationMode;
import com.zcshou.route.ServiceLocationState;
import com.zcshou.route.TestLocationSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    // V2-C route-playback state
    private final java.util.concurrent.atomic.AtomicLong mNextRouteSessionId = new java.util.concurrent.atomic.AtomicLong(0L);
    private LocationStateArbiter mLocationArbiter;
    private RoutePlaybackController mRouteController;

    // V2-E producer evidence. Synthetic motion remains producer-internal.
    private final SyntheticMotionCoordinator mMotionCoordinator =
            new SyntheticMotionCoordinator();
    private long mProducerStartElapsedNs = -1L;
    private HumanMotionConfig mProducerMotionConfig;
    private volatile String mLastEvidenceSessionId = "";

    private volatile boolean mGpsProviderReady = false;
    private volatile boolean mNetworkProviderReady = false;
    private int mConsecutiveProviderFailureCycles = 0;
    private static final int PROVIDER_FAILURE_THRESHOLD = 3;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        // Initialize V2-C arbitration with default manual state
        mLocationArbiter = new LocationStateArbiter(
                ServiceLocationState.manual(DEFAULT_LNG, DEFAULT_LAT, DEFAULT_ALT, mSpeed, DEFAULT_BEA)
        );

        // Initialize V2-C controller with Android clock
        mRouteController = new RoutePlaybackController(
                new RoutePlaybackController.Clock() {
                    @Override
                    public long elapsedRealtimeNanos() {
                        return SystemClock.elapsedRealtimeNanos();
                    }

                    @Override
                    public long currentTimeMillis() {
                        return System.currentTimeMillis();
                    }
                },
                sample -> onRouteSample(sample)
        );

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Only apply position extras if they are actually present
        if (intent != null && intent.hasExtra(MainActivity.LNG_MSG_ID)) {
            double lng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            double lat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            double alt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;

            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        // Stop route controller before provider cleanup
        if (mRouteController != null) {
            mRouteController.shutdown();
        }
        finishProducerEvidence("SESSION_INTERRUPTED");
        TestLocationSource.clear();

        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        if (mLocHandlerThread != null) {
            mLocHandlerThread.quit();
        }

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    // ---- V2-C Route Sample Listener ----

    private void onRouteSample(RouteSample sample) {
        // Accept sample into arbiter
        boolean accepted = mLocationArbiter.acceptRouteSample(sample);
        if (!accepted) return;

        // Feed the isolated V2-E producer evidence path after arbitration.
        mMotionCoordinator.onRouteSample(sample);

        // Publish to diagnostic mirror
        RouteSnapshot snap = RouteSnapshot.fromSample(sample, mLocationArbiter.getMode());
        TestLocationSource.publishSnapshot(snap);

        // Check for terminal state: update joystick position
        RouteSessionState state = sample.getState();
        if (state == RouteSessionState.STOPPED || state == RouteSessionState.FINISHED || state == RouteSessionState.ERROR) {
            mCurLng = sample.getLongitudeWgs84();
            mCurLat = sample.getLatitudeWgs84();
            mCurAlt = sample.getAltitudeMeters();
            mCurBea = (float) sample.getBearingDeg();
            mSpeed = 0.0;
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            mJoyStick.show(); // Re-enable joystick when route ends

            finishProducerEvidence(
                    state == RouteSessionState.ERROR
                            ? "ROUTE_ERROR"
                            : null
            );
        }
    }

    // ---- Provider Readiness ----

    private boolean preflightRequiredProviders() {
        if (mLocManager == null) return false;

        // Check GPS
        try {
            mGpsProviderReady = mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (!mGpsProviderReady) {
                XLog.w("SERVICEGO: GPS provider not enabled during preflight");
                return false;
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: GPS preflight failed", e);
            mGpsProviderReady = false;
            return false;
        }

        // Check NETWORK
        try {
            mNetworkProviderReady = mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!mNetworkProviderReady) {
                XLog.w("SERVICEGO: NETWORK provider not enabled during preflight");
                return false;
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: NETWORK preflight failed", e);
            mNetworkProviderReady = false;
            return false;
        }

        // Attempt a controlled write with current canonical state
        ServiceLocationState current = mLocationArbiter.getLocationState();
        try {
            setLocationGPS(current);
            setLocationNetwork(current);
        } catch (Exception e) {
            XLog.e("SERVICEGO: preflight write test failed", e);
            return false;
        }

        return true;
    }

    // ---- Route Commands (called from Binder) ----

    private RouteStartResult startRouteInternal(RoutePlan plan) {
        try {
            // Validate request
            if (plan == null) {
                return RouteStartResult.failure(RouteStartResult.ErrorCode.INVALID_ROUTE, "Plan is null");
            }

            // Resolve altitude from current state if needed
            RoutePlan resolvedPlan = plan;
            if (plan.requiresAltitudeResolution()) {
                resolvedPlan = plan.resolveAltitude(mCurAlt);
            }

            // Strict provider preflight
            if (!preflightRequiredProviders()) {
                boolean gpsOk = false;
                boolean netOk = false;
                try { gpsOk = mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                try { netOk = mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}

                if (!gpsOk && !netOk) {
                    return RouteStartResult.failure(RouteStartResult.ErrorCode.MOCK_PROVIDER_UNAVAILABLE, "Both GPS and NETWORK mock providers unavailable");
                } else if (!gpsOk) {
                    return RouteStartResult.failure(RouteStartResult.ErrorCode.GPS_PROVIDER_UNAVAILABLE, "GPS mock provider not ready");
                } else if (!netOk) {
                    return RouteStartResult.failure(RouteStartResult.ErrorCode.NETWORK_PROVIDER_UNAVAILABLE, "NETWORK mock provider not ready");
                } else {
                    return RouteStartResult.failure(RouteStartResult.ErrorCode.MOCK_PROVIDER_UNAVAILABLE, "Provider write test failed");
                }
            }

            // A newer route must never mix evidence with an older active route.
            if (mMotionCoordinator.snapshot().isActive()) {
                finishProducerEvidence("SESSION_REPLACED");
            }

            long sessionId = mNextRouteSessionId.incrementAndGet();

            String evidenceSessionId = resolvedPlan.getEvidenceSessionId();
            if (evidenceSessionId != null) {
                long seed = 0x563245L ^ (long) evidenceSessionId.hashCode();
                mProducerMotionConfig = HumanMotionConfig.defaultConfig(seed);

                File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (base == null) {
                    base = getFilesDir();
                }
                File producerRoot = new File(base, "v2e/producer");

                try {
                    mMotionCoordinator.start(
                            sessionId,
                            evidenceSessionId,
                            producerRoot,
                            mProducerMotionConfig
                    );
                    mLastEvidenceSessionId = evidenceSessionId;
                    mProducerStartElapsedNs =
                            SystemClock.elapsedRealtimeNanos();
                } catch (Exception e) {
                    XLog.e("SERVICEGO: producer evidence start failed", e);
                    mProducerMotionConfig = null;
                    mProducerStartElapsedNs = -1L;
                    return RouteStartResult.failure(
                            RouteStartResult.ErrorCode.CONTROLLER_START_FAILED,
                            "V2-E producer evidence start failed"
                    );
                }
            }

            if (!mLocationArbiter.beginRoute(sessionId)) {
                finishProducerEvidence("ARBITER_START_FAILED");
                return RouteStartResult.failure(
                        RouteStartResult.ErrorCode.CONTROLLER_START_FAILED,
                        "Location arbiter rejected route session"
                );
            }

            mJoyStick.hide();

            boolean controllerStarted =
                    mRouteController.start(sessionId, resolvedPlan);
            if (!controllerStarted) {
                mLocationArbiter.cancelRoute(sessionId);
                finishProducerEvidence("CONTROLLER_START_FAILED");
                mJoyStick.show();
                return RouteStartResult.failure(
                        RouteStartResult.ErrorCode.CONTROLLER_START_FAILED,
                        "Controller failed to start"
                );
            }

            mConsecutiveProviderFailureCycles = 0;
            return RouteStartResult.success(sessionId);
        } catch (Exception e) {
            XLog.e("SERVICEGO: startRoute error", e);
            return RouteStartResult.failure(RouteStartResult.ErrorCode.CONTROLLER_START_FAILED, e.getMessage());
        }
    }

    // ---- Provider write methods (consume canonical state) ----

    private void setLocationGPS(ServiceLocationState state) {
        try {
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);
            loc.setAltitude(state.getAltitudeMeters());
            loc.setBearing(state.getBearingDeg());
            loc.setLatitude(state.getLatitudeWgs84());
            loc.setLongitude(state.getLongitudeWgs84());
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed((float) state.getSpeedMps());
            long locationElapsedNs = SystemClock.elapsedRealtimeNanos();
            loc.setElapsedRealtimeNanos(locationElapsedNs);
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
            recordProducerPublication(
                    LocationManager.GPS_PROVIDER,
                    SystemClock.elapsedRealtimeNanos(),
                    locationElapsedNs,
                    state,
                    loc.getAccuracy()
            );
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS", e);
            throw new RuntimeException(e);
        }
    }

    private void setLocationNetwork(ServiceLocationState state) {
        try {
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);
            loc.setAltitude(state.getAltitudeMeters());
            loc.setBearing(state.getBearingDeg());
            loc.setLatitude(state.getLatitudeWgs84());
            loc.setLongitude(state.getLongitudeWgs84());
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed((float) state.getSpeedMps());
            long locationElapsedNs = SystemClock.elapsedRealtimeNanos();
            loc.setElapsedRealtimeNanos(locationElapsedNs);

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
            recordProducerPublication(
                    LocationManager.NETWORK_PROVIDER,
                    SystemClock.elapsedRealtimeNanos(),
                    locationElapsedNs,
                    state,
                    loc.getAccuracy()
            );
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork", e);
            throw new RuntimeException(e);
        }
    }

    // ---- V2-E Producer Evidence ----

    private void recordProducerPublication(
            String provider,
            long publicationElapsedNs,
            long locationElapsedNs,
            ServiceLocationState state,
            double accuracyM
    ) {
        if (!mMotionCoordinator.snapshot().isActive()) {
            return;
        }

        ServiceLocationMode mode = mLocationArbiter.getMode();
        if (mode == ServiceLocationMode.MANUAL) {
            return;
        }

        mMotionCoordinator.onLocationPublished(
                provider,
                publicationElapsedNs,
                locationElapsedNs,
                state.getLatitudeWgs84(),
                state.getLongitudeWgs84(),
                state.getSpeedMps(),
                state.getBearingDeg(),
                accuracyM
        );
    }

    private void finishProducerEvidence(String additionalErrorCode) {
        SyntheticMotionStatus status = mMotionCoordinator.snapshot();
        if (!status.isActive()) {
            return;
        }

        HumanMotionConfig config = mProducerMotionConfig;
        if (config == null) {
            config = HumanMotionConfig.defaultConfig(
                    0x563245L
                            ^ (long) status.getEvidenceSessionId().hashCode()
            );
        }

        ProducerSessionMetadata.Builder builder =
                new ProducerSessionMetadata.Builder(
                        status.getEvidenceSessionId(),
                        status.getRouteSessionId(),
                        config
                )
                        .elapsedRange(
                                mProducerStartElapsedNs,
                                SystemClock.elapsedRealtimeNanos()
                        )
                        .appVersion(BuildConfig.VERSION_NAME)
                        .sourceCommitSha(BuildConfig.SOURCE_COMMIT_SHA)
                        .device(
                                Build.MODEL,
                                Build.VERSION.RELEASE,
                                Build.VERSION.SDK_INT
                        )
                        .bootMarker(readBootMarker());

        for (String code : status.getErrorCodes()) {
            builder.addErrorCode(code);
        }
        if (additionalErrorCode != null
                && !additionalErrorCode.isEmpty()) {
            builder.addErrorCode(additionalErrorCode);
        }

        ProducerEvidenceRecorder.CloseResult result =
                mMotionCoordinator.finish(builder.build());

        if (result == null || !result.isSuccess()) {
            XLog.e(
                    "SERVICEGO: producer evidence finalization failed: "
                            + (result == null
                            ? "TRACE_CLOSE_FAILURE"
                            : result.getErrorCode())
            );
        }

        mProducerMotionConfig = null;
        mProducerStartElapsedNs = -1L;
    }

    private String readBootMarker() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        } catch (Exception ignored) {
        }

        long approximateBootEpochMs =
                System.currentTimeMillis()
                        - SystemClock.elapsedRealtime();
        return Build.FINGERPRINT + ":" + approximateBootEpochMs;
    }

    // ---- Original Provider Methods (adapted for canonical state) ----

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // Compute new position
                double newLng = mCurLng + disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                double newLat = mCurLat + disLat / 110.574;

                // Try to write through arbiter
                boolean accepted = mLocationArbiter.updateManual(newLng, newLat, mCurAlt, speed, (float) angle);
                if (accepted) {
                    mCurLng = newLng;
                    mCurLat = newLat;
                    mSpeed = speed;
                    mCurBea = (float) angle;
                }
                // If rejected (route owns location), do not mutate route state
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                boolean accepted = mLocationArbiter.updateManual(lng, lat, alt, mSpeed, mCurBea);
                if (accepted) {
                    mCurLng = lng;
                    mCurLat = lat;
                    mCurAlt = alt;
                }
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        // Get canonical state from arbiter
                        ServiceLocationState state = mLocationArbiter.getLocationState();

                        boolean networkOk = false;
                        boolean gpsOk = false;

                        try {
                            setLocationNetwork(state);
                            networkOk = true;
                        } catch (Exception e) {
                            XLog.e("SERVICEGO: setLocationNetwork failed");
                        }

                        try {
                            setLocationGPS(state);
                            gpsOk = true;
                        } catch (Exception e) {
                            XLog.e("SERVICEGO: setLocationGPS failed");
                        }

                        // Track consecutive failures
                        if (networkOk && gpsOk) {
                            mConsecutiveProviderFailureCycles = 0;
                        } else {
                            mConsecutiveProviderFailureCycles++;
                            XLog.w("SERVICEGO: provider failure cycle " + mConsecutiveProviderFailureCycles);
                        }

                        // Fatal threshold check during route ownership
                        if (mConsecutiveProviderFailureCycles >= PROVIDER_FAILURE_THRESHOLD
                                && mLocationArbiter.getMode() != ServiceLocationMode.MANUAL) {
                            long currentSession = mLocationArbiter.getCurrentSessionId();
                            mRouteController.fail(currentSession,
                                    "Provider write failure after " + PROVIDER_FAILURE_THRESHOLD + " consecutive cycles");
                        }

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    @SuppressWarnings("unused")
    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    @SuppressWarnings("unused")
    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            boolean accepted = mLocationArbiter.updateManual(lng, lat, alt, mSpeed, mCurBea);
            if (accepted) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            }
        }

        // V2-C Route Binder API
        public RouteStartResult startRoute(RoutePlan plan) {
            return startRouteInternal(plan);
        }

        public boolean pauseRoute(long sessionId) {
            return mRouteController.pause(sessionId);
        }

        public boolean resumeRoute(long sessionId) {
            return mRouteController.resume(sessionId);
        }

        public boolean stopRoute(long sessionId) {
            return mRouteController.stop(sessionId);
        }

        public RouteSnapshot getRouteSnapshot() {
            ServiceLocationMode mode = mLocationArbiter.getMode();
            return mRouteController.getSnapshot(mode);
        }

        public ServiceLocationMode getLocationMode() {
            return mLocationArbiter.getMode();
        }

        public String getEvidenceSessionId() {
            SyntheticMotionStatus status = mMotionCoordinator.snapshot();
            if (status.isActive()
                    && status.getEvidenceSessionId() != null
                    && !status.getEvidenceSessionId().isEmpty()) {
                return status.getEvidenceSessionId();
            }
            return mLastEvidenceSessionId;
        }
    }
}