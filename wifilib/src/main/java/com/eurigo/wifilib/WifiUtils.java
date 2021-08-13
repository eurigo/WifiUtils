package com.eurigo.wifilib;

import static android.content.Context.WIFI_SERVICE;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.PatternMatcher;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.eurigo.dx.stock.ProxyBuilder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Eurigo
 * Created on 2021/7/14 16:46
 * desc   :
 */
public class WifiUtils {

    private static final String TAG = "WifiUtils";

    public static final int REQUEST_WRITE_SETTING_CODE = 1;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private final WifiReceiver wifiReceiver = new WifiReceiver();

    private boolean isWifiReceiverRegister = false;

    private WifiManager wifiManager;

    public WifiUtils() {
    }

    public static WifiUtils getInstance() {
        return SingletonHelper.INSTANCE;
    }

    private static class SingletonHelper {
        private final static WifiUtils INSTANCE = new WifiUtils();
    }

    /**
     * wifi是否打开
     */
    public boolean isWifiEnable(Context context) {
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        return wifiManager.isWifiEnabled();
    }

    /**
     * 打开Wifi, 不适用于Android 10.0+设备
     */
    public void openWifi(Context context) throws RuntimeException {
        if (Build.VERSION.SDK_INT >= Q) {
            throw new RuntimeException("no support Android 10+ system");
        }
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        wifiManager.setWifiEnabled(true);
    }

    /**
     * 关闭Wi-Fi, 不适用于Android 10.0+设备
     */
    public void closeWifi(Context context) throws RuntimeException {
        if (Build.VERSION.SDK_INT >= Q) {
            throw new RuntimeException("not support Android 10+ system!");
        }
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        wifiManager.setWifiEnabled(false);
    }

    /**
     * 连接WIFI,密码为空则不使用密码连接
     * 9.0以下设备需要关闭热点
     *
     * @param ssid     wifi名称
     * @param password wifi密码
     */
    public void connectWifi(Context context, String ssid, String password) {
        // 9.0以下系统不支持关闭热点和WiFi共存
        if (Build.VERSION.SDK_INT <= P) {
            if (isApEnable(context)) {
                closeAp(context);
            }
            // 打开WiFi
            if (!isWifiEnable(context)) {
                openWifi(context);
            }
        } else {
            if (!isWifiEnable(context)) {
                // 9.0以上设备需要手动打开WiFi
                Toast.makeText(context, "请先打开WiFi开关", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // 需要等待WiFi开启再去连接
        new ThreadPoolExecutor(CPU_COUNT
                , 2 * CPU_COUNT + 1
                , 30
                , TimeUnit.SECONDS
                , new LinkedBlockingQueue<>()
                , new ConnectWiFiThread()).execute(new Runnable() {
            @Override
            public void run() {
                while (!wifiManager.isWifiEnabled()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                // 7.1以下设备使用反射连接，以上使用ConnectivityManager
                if (Build.VERSION.SDK_INT < Q) {
                    wifiManager.disableNetwork(wifiManager.getConnectionInfo().getNetworkId());
                    int netId = wifiManager.addNetwork(getWifiConfig(ssid, password, !TextUtils.isEmpty(password)));
                    wifiManager.enableNetwork(netId, true);
                } else {
                    connect(context, ssid, password);
                }
            }
        });
    }

    /**
     * 便携热点是否开启
     */
    public boolean isApEnable(Context context) {
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        try {
            Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (boolean) method.invoke(wifiManager);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 关闭便携热点, Android 7.1版本可能存在问题
     */
    public void closeAp(Context context) {
        // 6.0+申请修改系统设置权限
        if (Build.VERSION.SDK_INT >= M) {
            if (!isGrantedWriteSettings(context)) {
                requestWriteSettings(context);
            }
        }
        // 8.0以上的关闭方式不一样
        if (Build.VERSION.SDK_INT >= O) {
            stopTethering(context);
        } else {
            if (wifiManager == null) {
                wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
            }
            try {
                Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                method.setAccessible(true);
                method.invoke(wifiManager, null, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 开启便携热点, Android 7.1版本可能存在问题
     *
     * @param context  上下文
     * @param ssid     便携热点SSID
     * @param password 便携热点密码
     */
    public void openAp(Context context, String ssid, String password) {
        // 6.0+申请修改系统设置权限
        if (Build.VERSION.SDK_INT >= M) {
            if (!isGrantedWriteSettings(context)) {
                requestWriteSettings(context);
            }
        }
        // 9.0以下版本不支持热点和WiFi共存
        if (Build.VERSION.SDK_INT <= P) {
            // 关闭WiFi
            if (isWifiEnable(context)) {
                wifiManager.setWifiEnabled(false);
            }
        }
        // 8.0以下的开启方式不一样
        if (Build.VERSION.SDK_INT >= O) {
            startTethering(context);
        } else {
            if (wifiManager == null) {
                wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
            }
            try {
                // 热点的配置类
                WifiConfiguration config = new WifiConfiguration();
                // 配置热点的名称(可以在名字后面加点随机数什么的)
                config.SSID = ssid;
                config.preSharedKey = password;
                //是否隐藏网络
                config.hiddenSSID = false;
                //开放系统认证
                config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
                config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
                config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                config.status = WifiConfiguration.Status.ENABLED;
                // 调用反射打开热点
                Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, Boolean.TYPE);
                // 返回热点打开状态
                method.invoke(wifiManager, config, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 查看授权情况, 开启热点需要申请系统设置修改权限，如有必要，可提前申请
     *
     * @param context activity
     */

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void requestWriteSettings(Context context) {
        if (isGrantedWriteSettings(context)) {
            Log.e(TAG, "已授权修改系统设置权限");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        ((Activity) context).startActivityForResult(intent, REQUEST_WRITE_SETTING_CODE);
    }

    /**
     * 返回应用程序是否可以修改系统设置
     *
     * @return {@code true}: yes
     * {@code false}: no
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean isGrantedWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    /**
     * 是否连接着指定WiFi,通过已连接的SSID判断
     */
    @RequiresApi(JELLY_BEAN_MR1)
    public boolean isConnectedSpecifySsid(Context context, String ssid) {
        return ssid.equals(getSsid(context));
    }

    /**
     * 获取当前WiFi名称
     */
    public String getSsid(Context context) {
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        return wifiManager.getConnectionInfo().getSSID();
    }

    /**
     * 获取WiFi列表
     *
     * @return WIFI列表
     */
    public List<ScanResult> getWifiList(Context context) {
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        List<ScanResult> resultList = new ArrayList<>();
        if (wifiManager != null && isWifiEnable(context)) {
            resultList.addAll(wifiManager.getScanResults());
        }
        return resultList;
    }

    /**
     * 获取开启便携热点后自身热点IP地址
     *
     * @return ip地址
     */
    public String getLocalIp(Context context) {
        if (wifiManager == null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        }
        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
        if (dhcpInfo != null) {
            int address = dhcpInfo.serverAddress;
            return ((address & 0xFF)
                    + "." + ((address >> 8) & 0xFF)
                    + "." + ((address >> 16) & 0xFF)
                    + "." + ((address >> 24) & 0xFF));
        }
        return null;
    }

    /**
     * android8.0以上开启手机热点
     */
    private void startTethering(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            Class classOnStartTetheringCallback = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
            Method startTethering = connectivityManager.getClass()
                    .getDeclaredMethod("startTethering", int.class, boolean.class, classOnStartTetheringCallback);
            Object proxy = ProxyBuilder.forClass(classOnStartTetheringCallback)
                    .handler(new InvocationHandler() {
                        @Override
                        public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
                            return null;
                        }
                    }).build();
            startTethering.invoke(connectivityManager, 0, false, proxy);
        } catch (Exception e) {
            Log.e(TAG, "打开热点失败");
            e.printStackTrace();
        }
    }

    /**
     * android8.0以上关闭手机热点
     */
    private void stopTethering(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            Method stopTethering = connectivityManager
                    .getClass().getDeclaredMethod("stopTethering", int.class);
            stopTethering.invoke(connectivityManager, 0);
        } catch (Exception e) {
            Log.e(TAG, "关闭热点失败");
            e.printStackTrace();
        }
    }

    /**
     * wifi设置
     *
     * @param ssid     WIFI名称
     * @param pws      WIFI密码
     * @param isHasPws 是否有密码
     */
    private WifiConfiguration getWifiConfig(String ssid, String pws, boolean isHasPws) {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedAuthAlgorithms.clear();
        config.allowedGroupCiphers.clear();
        config.allowedKeyManagement.clear();
        config.allowedPairwiseCiphers.clear();
        config.allowedProtocols.clear();
        config.SSID = "\"" + ssid + "\"";

        WifiConfiguration tempConfig = isExist(ssid);
        if (tempConfig != null) {
            wifiManager.removeNetwork(tempConfig.networkId);
        }
        if (isHasPws) {
            config.preSharedKey = "\"" + pws + "\"";
            config.hiddenSSID = true;
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            config.status = WifiConfiguration.Status.ENABLED;
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        return config;
    }

    /**
     * 得到配置好的网络连接
     *
     * @param ssid
     * @return
     */
    @SuppressLint("MissingPermission")
    private WifiConfiguration isExist(String ssid) {
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null) {
            Log.e(TAG, "isExist: null");
            return null;
        }
        for (WifiConfiguration config : configs) {
            if (config.SSID.equals("\"" + ssid + "\"")) {
                return config;
            }
        }
        return null;
    }

    /**
     * 这个方法连接上的WIFI ，只能在当前应用中使用，当应用被kill之后，连接的这个wifi会断开
     *
     * @param context  上下文
     * @param ssid     wifi名称
     * @param password wifi密码
     */
    @RequiresApi(api = Q)
    private void connect(Context context, String ssid, String password) {
        if (isGrantedWriteSettings(context)) {
            requestWriteSettings(context);
        }
        ConnectivityManager connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        // 创建一个请求
        NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(ssid, PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(password)
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                // 创建的是WIFI网络。
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 网络不受限
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                // 信任网络，增加这个参数让设备连接wifi之后还联网。
                .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .setNetworkSpecifier(specifier)
                .build();
        connectivityManager.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.e(TAG, "onAvailable");
            }

            @Override
            public void onUnavailable() {
                Log.e(TAG, "onUnavailable");
            }
        });
    }

    private static final class ConnectWiFiThread extends AtomicInteger
            implements ThreadFactory {

        private final AtomicInteger POOL_NUMBER = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r) {
                @Override
                public void run() {
                    try {
                        super.run();
                    } catch (Throwable e) {
                        Log.e(TAG, "Thread run threw throwable", e);
                    }
                }
            };
            t.setName(TAG + new AtomicInteger(1)
                    + "-pool-" + POOL_NUMBER.getAndIncrement() +
                    "-thread-");
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    Log.e(TAG, "Thread run threw uncaughtException throwable", e);
                }
            });
            return t;
        }
    }

    public boolean isRegisterWifiBroadcast() {
        return isWifiReceiverRegister;
    }

    /**
     * 注册Wifi广播
     */
    public void registerWifiBroadcast(Context context, WifiReceiver.WifiStateListener wifiStateListener) {
        // 刚注册广播时会立即收到一条当前状态的广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        context.registerReceiver(wifiReceiver, filter);
        wifiReceiver.setWifiStateListener(wifiStateListener);
        isWifiReceiverRegister = true;
    }

    /**
     * 解除Wifi广播
     */
    public void unregisterWifiBroadcast(Context context) {
        if (isWifiReceiverRegister) {
            wifiReceiver.setWifiStateListener(null);
            context.unregisterReceiver(wifiReceiver);
        }
    }

}