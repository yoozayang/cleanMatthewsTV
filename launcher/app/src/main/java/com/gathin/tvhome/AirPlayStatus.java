package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether this TV is offering AirPlay, the way an iPhone finds out: an mDNS browse for {@code _airplay._tcp}, and
 * the one service whose address is this TV's own. Only the local network is asked; nothing leaves it. A browse
 * runs for a few seconds when Home comes up, at most every ten minutes, and the answer is the service's name
 * ("小米電視"), or null when no receiver on this TV answered.
 */
final class AirPlayStatus {
    interface Listener {
        void onAirPlay(String name, boolean known);
    }

    private static final String TYPE = "_airplay._tcp.";
    private static final long BROWSE_MS = 7_000L;
    private static final long FRESH_MS = 10 * 60_000L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving;
    private Set<String> ownAddresses = new HashSet<>();
    /** Shared by Home's status line and the control centre, so opening the panel does not browse again. */
    private static String found;
    private static long checkedAt;

    AirPlayStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Browses again unless the last answer is under ten minutes old; the listener hears either way. */
    void refresh() {
        if (checkedAt != 0 && SystemClock.elapsedRealtime() - checkedAt < FRESH_MS) {
            listener.onAirPlay(found, true);
            return;
        }
        if (discovery != null) return;
        ownAddresses = ownAddresses();
        if (ownAddresses.isEmpty()) {
            listener.onAirPlay(null, false);   // offline: say nothing rather than "not available"
            return;
        }
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        found = null;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {
                main.post(() -> finish());
            }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    toResolve.add(info);
                    resolveNext();
                });
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
            return;
        }
        main.postDelayed(this::finish, BROWSE_MS);
    }

    /** The last answer, or null; known() says whether there is one. */
    static String name() {
        return found;
    }

    static boolean known() {
        return checkedAt != 0;
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        stopBrowse();
        toResolve.clear();
        resolving = false;
    }

    /** One resolve at a time: NsdManager refuses a second while one is running. */
    @SuppressWarnings("deprecation")   // resolveService: its replacement (registerServiceInfoCallback) is API 34 only
    private void resolveNext() {
        if (resolving || found != null || discovery == null) return;
        NsdServiceInfo next = toResolve.poll();
        if (next == null) return;
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    InetAddress host = info.getHost();
                    String name = info.getServiceName();
                    main.post(() -> {
                        resolving = false;
                        if (host != null && ownAddresses.contains(host.getHostAddress())) {
                            found = name;
                            finish();
                        } else {
                            resolveNext();
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private void finish() {
        if (discovery == null) return;
        stopBrowse();
        toResolve.clear();
        checkedAt = SystemClock.elapsedRealtime();
        listener.onAirPlay(found, true);
    }

    private void stopBrowse() {
        if (discovery == null || nsd == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException ignored) {
            // Already stopped.
        }
        discovery = null;
    }

    private Set<String> ownAddresses() {
        Set<String> out = new HashSet<>();
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return out;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) out.add(a.getAddress().getHostAddress());
        }
        return out;
    }
}
