package com.termux.api.apis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.JsonWriter;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.api.util.ResultReturner.ResultJsonWriter;
import com.termux.shared.logger.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class WifiAPI {

    private static final String LOG_TAG = "WifiAPI";

    // ─── WiFi Direct (P2P) state ──────────────────────────────────────
    private static WifiP2pManager p2pManager;
    private static WifiP2pManager.Channel p2pChannel;

    // ─── WiFi Aware state ─────────────────────────────────────────────
    private static WifiAwareManager awareManager;
    private static WifiAwareSession awareSession;

    // ─── Hotspot state ────────────────────────────────────────────────
    // Tracks whether a LocalOnlyHotspot request is active; prevents
    // duplicate requests (system allows only one per UID).
    private static boolean hotspotRequestActive = false;

    /**
     * Reservation of the last LocalOnlyHotspot we started.  The system ties the
     * hotspot's lifetime to this object: dropping it means the hotspot shuts
     * down at an arbitrary moment (when GC collects it), and without it there is
     * no way to stop on purpose.  `hotspot-status` reports it and `hotspot-stop`
     * releases it.
     */
    private static LocalOnlyHotspotReservation hotspotReservation = null;
    private static String hotspotSsid = null;
    private static String hotspotPassword = null;
    private static int hotspotBand = 0;
    private static int hotspotChannel = 0;
    private static boolean hotspotHidden = false;
    private static boolean hotspotCustomConfig = false;

    /**
     * Unified entry point for all wifi commands.
     * Called from DirectDispatcher with "api_method" = "Wifi".
     * The specific sub-command is read from intent.getStringExtra("command").
     */
    public static void onReceive(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        String command = intent.getStringExtra("command");
        if (command == null) {
            Logger.logError(LOG_TAG, "Missing 'command' extra");
            return;
        }

        try {
            switch (command) {
                case "connection-info":
                    onReceiveWifiConnectionInfo(apiReceiver, context, intent);
                    break;
                case "scan-info":
                    onReceiveWifiScanInfo(apiReceiver, context, intent);
                    break;
                case "enable":
                    onReceiveWifiEnable(apiReceiver, context, intent);
                    break;
                case "hotspot":
                    onReceiveWifiHotspot(apiReceiver, context, intent);
                    break;
                case "hotspot-status":
                    onReceiveWifiHotspotStatus(apiReceiver, context, intent);
                    break;
                case "hotspot-stop":
                    onReceiveWifiHotspotStop(apiReceiver, context, intent);
                    break;
                case "hotspot-enable":
                    onReceiveWifiHotspotSetEnabled(apiReceiver, context, intent, true);
                    break;
                case "hotspot-disable":
                    onReceiveWifiHotspotSetEnabled(apiReceiver, context, intent, false);
                    break;
                case "hotspot-clients":
                    onReceiveWifiHotspotClients(apiReceiver, context, intent);
                    break;
                case "p2p-discover":
                    onReceiveP2pDiscover(apiReceiver, context, intent);
                    break;
                case "p2p-connect":
                    onReceiveP2pConnect(apiReceiver, context, intent);
                    break;
                case "p2p-group":
                    onReceiveP2pGroupInfo(apiReceiver, context, intent);
                    break;
                case "aware-attach":
                    onReceiveAwareAttach(apiReceiver, context, intent);
                    break;
                case "aware-publish":
                    onReceiveAwarePublish(apiReceiver, context, intent);
                    break;
                case "aware-subscribe":
                    onReceiveAwareSubscribe(apiReceiver, context, intent);
                    break;
                case "rtt-range":
                    onReceiveRttRange(apiReceiver, context, intent);
                    break;
                case "suggest":
                    onReceiveNetworkSuggestion(apiReceiver, context, intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Unrecognized wifi command: '" + command + "'");
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in wifi command: " + command, e);
        }
    }

    // ─── Helper methods for WiFi standards, channels, security ────────

    /**
     * Convert frequency in MHz to channel number.
     * Reference: IEEE 802.11 channel plan.
     */
    private static int frequencyToChannel(int freqMhz) {
        if (freqMhz >= 2412 && freqMhz <= 2484) {
            return (freqMhz - 2412) / 5 + 1;
        } else if (freqMhz >= 5170 && freqMhz <= 5250) {
            return (freqMhz - 5170) / 5 + 34;
        } else if (freqMhz >= 5250 && freqMhz <= 5330) {
            return (freqMhz - 5250) / 5 + 52;
        } else if (freqMhz >= 5490 && freqMhz <= 5730) {
            return (freqMhz - 5490) / 5 + 100;
        } else if (freqMhz >= 5745 && freqMhz <= 5825) {
            return (freqMhz - 5745) / 5 + 149;
        } else if (freqMhz >= 5955 && freqMhz <= 7115) {
            // WiFi 6E 6 GHz band
            return (freqMhz - 5955) / 5 + 1;
        }
        return 0;
    }

    /**
     * Map a frequency in MHz to the band name Android uses in its Wi-Fi UI.
     * Callers previously had to redo this arithmetic (and got 6 GHz wrong).
     */
    private static String bandFromFrequency(int frequencyMhz) {
        if (frequencyMhz >= 2400 && frequencyMhz < 2500) return "2.4GHz";
        if (frequencyMhz >= 4900 && frequencyMhz < 5925) return "5GHz";
        if (frequencyMhz >= 5925 && frequencyMhz < 7125) return "6GHz";
        if (frequencyMhz >= 57000) return "60GHz";
        return "unknown";
    }

    /**
     * RSSI in dBm (-30 at the AP, about -90 at the edge) as the 0-100 scale the
     * status bar shows: 2 * (dBm + 100), clamped.
     */
    private static int rssiToPercent(int rssiDbm) {
        int percent = 2 * (rssiDbm + 100);
        if (percent < 0) return 0;
        if (percent > 100) return 100;
        return percent;
    }

    /**
     * Network-layer details of the active connection: addresses, gateway, DNS,
     * DHCP server and private DNS.  getConnectionInfo() only describes the
     * Wi-Fi link, so callers had no way to learn the gateway or resolvers
     * without shelling out to `ip route` and reading /etc/resolv.conf.
     */
    private static void writeLinkProperties(Context context, JsonWriter out) throws Exception {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        Network network = cm.getActiveNetwork();
        LinkProperties lp = network != null ? cm.getLinkProperties(network) : null;
        if (lp == null) return;

        if (!TextUtils.isEmpty(lp.getInterfaceName())) {
            out.name("interface").value(lp.getInterfaceName());
        }

        out.name("ip_addresses");
        out.beginArray();
        for (LinkAddress address : lp.getLinkAddresses()) {
            out.value(address.getAddress().getHostAddress() + "/" + address.getPrefixLength());
        }
        out.endArray();

        String gateway = null;
        for (RouteInfo route : lp.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() != null) {
                gateway = route.getGateway().getHostAddress();
                break;
            }
        }
        if (gateway != null) out.name("gateway").value(gateway);

        out.name("dns_servers");
        out.beginArray();
        for (InetAddress dns : lp.getDnsServers()) {
            out.value(dns.getHostAddress());
        }
        out.endArray();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            out.name("private_dns").value(lp.isPrivateDnsActive());
            if (lp.getPrivateDnsServerName() != null) {
                out.name("private_dns_server").value(lp.getPrivateDnsServerName());
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            InetAddress dhcpServer = lp.getDhcpServerAddress();
            if (dhcpServer != null) out.name("dhcp_server").value(dhcpServer.getHostAddress());
        }
    }

    /**
     * Apply the filters requested by `termux-wifi scan-info`: band, ssid and
     * bssid substrings, a minimum signal, de-duplication by SSID, ordering and a
     * result cap.  Scanning a flat or an office returns 50+ BSSIDs, most of them
     * mesh siblings of the same SSID, so callers used to pipe through jq/awk.
     */
    private static List<ScanResult> filterScans(Intent intent, List<ScanResult> scans) {
        String band = intent.getStringExtra("band");
        String ssid = intent.getStringExtra("ssid");
        String bssid = intent.getStringExtra("bssid");
        int minRssi = intent.getIntExtra("min_rssi", -127);
        boolean dedupe = intent.getBooleanExtra("dedupe", false);
        String sort = intent.getStringExtra("sort");
        int limit = intent.getIntExtra("limit", 0);

        List<ScanResult> result = new ArrayList<>(scans.size());
        for (ScanResult scan : scans) {
            if (band != null && !bandMatches(band, scan.frequency)) continue;
            if (ssid != null) {
                String haystack = scan.SSID == null ? "" : scan.SSID.toLowerCase(Locale.ROOT);
                if (!haystack.contains(ssid.toLowerCase(Locale.ROOT))) continue;
            }
            if (bssid != null) {
                String haystack = scan.BSSID == null ? "" : scan.BSSID.toLowerCase(Locale.ROOT);
                if (!haystack.startsWith(bssid.toLowerCase(Locale.ROOT))) continue;
            }
            if (scan.level < minRssi) continue;
            result.add(scan);
        }

        if (dedupe) {
            java.util.LinkedHashMap<String, ScanResult> strongest = new java.util.LinkedHashMap<>();
            for (ScanResult scan : result) {
                String key = scan.SSID == null ? "" : scan.SSID;
                ScanResult current = strongest.get(key);
                if (current == null || scan.level > current.level) strongest.put(key, scan);
            }
            result = new ArrayList<>(strongest.values());
        }

        Comparator<ScanResult> order;
        if ("ssid".equalsIgnoreCase(sort)) {
            order = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.SSID == null ? "" : a.SSID, b.SSID == null ? "" : b.SSID);
        } else if ("frequency".equalsIgnoreCase(sort) || "freq".equalsIgnoreCase(sort)) {
            order = (a, b) -> Integer.compare(a.frequency, b.frequency);
        } else {
            // Strongest first — the sane default when scanning a room.
            order = (a, b) -> Integer.compare(b.level, a.level);
        }
        Collections.sort(result, order);

        if (limit > 0 && result.size() > limit) {
            result = new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private static boolean bandMatches(String band, int frequencyMhz) {
        String wanted = band.toLowerCase(Locale.ROOT).replace(" ", "");
        String actual = bandFromFrequency(frequencyMhz).toLowerCase(Locale.ROOT);
        if ("2.4".equals(wanted) || "2.4ghz".equals(wanted) || "24".equals(wanted)) return "2.4ghz".equals(actual);
        if ("5".equals(wanted) || "5ghz".equals(wanted)) return "5ghz".equals(actual);
        if ("6".equals(wanted) || "6ghz".equals(wanted)) return "6ghz".equals(actual);
        if ("60".equals(wanted) || "60ghz".equals(wanted)) return "60ghz".equals(actual);
        return actual.equals(wanted);
    }

    /**
     * Determine WiFi standard from frequency and capabilities.
     * Returns 802.11a/b/g/n/ac/ax/be or "unknown".
     */
    private static String getWifiStandard(WifiInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int standard = info.getWifiStandard();
            switch (standard) {
                case 0: return "802.11";   // WIFI_STANDARD_LEGACY
                case 1: return "802.11a";   // WIFI_STANDARD_80211A
                case 2: return "802.11b";   // WIFI_STANDARD_80211B
                case 3: return "802.11g";   // WIFI_STANDARD_80211G
                case 4: return "802.11n";   // WIFI_STANDARD_80211N
                case 5: return "802.11ac";  // WIFI_STANDARD_80211AC
                case 6: return "802.11ax";  // WIFI_STANDARD_80211AX
                case 7: return "802.11be";  // WIFI_STANDARD_80211BE
                default: return "unknown";
            }
        }
        // Fallback: infer from frequency band
        int freq = info.getFrequency();
        if (freq >= 2412 && freq <= 2484) {
            // 2.4 GHz — could be 802.11b/g/n
            return info.getLinkSpeed() > 100 ? "802.11n" : "802.11g";
        } else if (freq >= 5170 && freq <= 5825) {
            // 5 GHz — likely 802.11a/n/ac
            return info.getLinkSpeed() > 300 ? "802.11ac" : "802.11a";
        } else if (freq >= 5955 && freq <= 7115) {
            return "802.11ax";
        }
        return "unknown";
    }

    /**
     * Return human-readable WiFi generation: "WiFi 4", "WiFi 5", "WiFi 6", "WiFi 6E", "WiFi 7", or null.
     */
    private static String getWifiGeneration(int frequencyMhz, String standard) {
        if (standard == null) return null;
        switch (standard) {
            case "802.11n": return "WiFi 4";
            case "802.11ac": return "WiFi 5";
            case "802.11ax":
                if (frequencyMhz >= 5955 && frequencyMhz <= 7115) {
                    return "WiFi 6E";
                }
                return "WiFi 6";
            case "802.11be": return "WiFi 7";
            default: return null;
        }
    }

    /**
     * Infer security type from scan result capabilities string.
     * Returns e.g. "WPA2-PSK", "WPA3-SAE", "WPA2-Enterprise", "Open", "WEP", etc.
     */
    private static String getSecurityType(String capabilities) {
        if (TextUtils.isEmpty(capabilities)) return "unknown";
        String caps = capabilities.toUpperCase();
        if (caps.contains("WPA3-SAE") || caps.contains("SAE")) {
            if (caps.contains("WPA2")) return "WPA2/WPA3-PSK";
            return "WPA3-SAE";
        }
        if (caps.contains("WPA2") && caps.contains("-ENT")) return "WPA2-Enterprise";
        if (caps.contains("WPA2")) return "WPA2-PSK";
        if (caps.contains("WPA") && caps.contains("-ENT")) return "WPA-Enterprise";
        if (caps.contains("WPA")) return "WPA-PSK";
        if (caps.contains("WEP")) return "WEP";
        if (caps.contains("ESS") || caps.contains("IBSS")) return "Open";
        return "unknown";
    }

    /**
     * Determine 802.11 generation for a ScanResult based on frequency and capabilities.
     */
    private static String getScanResultGeneration(ScanResult scan) {
        int freq = scan.frequency;
        String standard = null;
        String caps = scan.capabilities != null ? scan.capabilities.toUpperCase() : "";

        if (freq >= 5955 && freq <= 7115) {
            standard = "802.11ax"; // WiFi 6E
        } else if (freq >= 5170 && freq <= 5825) {
            if (scan.channelWidth > ScanResult.CHANNEL_WIDTH_40MHZ || caps.contains("VHT")) {
                standard = "802.11ac"; // WiFi 5 (or ax on 5GHz)
            } else {
                standard = "802.11a";
            }
        } else if (freq >= 2412 && freq <= 2484) {
            if (scan.channelWidth > ScanResult.CHANNEL_WIDTH_20MHZ || caps.contains("HT")) {
                standard = "802.11n"; // WiFi 4
            } else {
                standard = "802.11g";
            }
        }
        if (standard == null) return null;

        return getWifiGeneration(freq, standard);
    }

    // ─── Existing methods (keep for backward compat via old action strings) ───

    public static void onReceiveWifiConnectionInfo(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiConnectionInfo");

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @SuppressLint("HardwareIds")
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = manager.getConnectionInfo();
                out.beginObject();
                if (info == null) {
                    out.name("API_ERROR").value("No current connection");
                } else {
                    out.name("bssid").value(info.getBSSID());
                    out.name("frequency_mhz").value(info.getFrequency());
                    //noinspection deprecation
                    out.name("ip").value(Formatter.formatIpAddress(info.getIpAddress()));
                    out.name("link_speed_mbps").value(info.getLinkSpeed());
                    out.name("mac_address").value(info.getMacAddress());
                    out.name("network_id").value(info.getNetworkId());
                    out.name("rssi").value(info.getRssi());
                    out.name("ssid").value(info.getSSID().replaceAll("\"", ""));
                    out.name("ssid_hidden").value(info.getHiddenSSID());
                    out.name("supplicant_state").value(info.getSupplicantState().toString());

                    // ── New fields (Task 2) ──
                    int freq = info.getFrequency();
                    out.name("channel").value(frequencyToChannel(freq));
                    out.name("band").value(bandFromFrequency(freq));
                    out.name("rssi_percent").value(rssiToPercent(info.getRssi()));
                    String standard = getWifiStandard(info);
                    out.name("standard").value(standard);
                    String generation = getWifiGeneration(freq, standard);
                    if (generation != null) {
                        out.name("wifi_generation").value(generation);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        out.name("tx_link_speed_mbps").value(info.getTxLinkSpeedMbps());
                        out.name("rx_link_speed_mbps").value(info.getRxLinkSpeedMbps());
                        out.name("max_tx_link_speed_mbps").value(info.getMaxSupportedTxLinkSpeedMbps());
                        out.name("max_rx_link_speed_mbps").value(info.getMaxSupportedRxLinkSpeedMbps());
                    }
                    writeLinkProperties(context, out);
                }
                out.endObject();
            }
        });
    }

    static boolean isLocationEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    public static void onReceiveWifiScanInfo(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiScanInfo");

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                List<ScanResult> scans = manager.getScanResults();
                if (scans != null) {
                    scans = filterScans(intent, scans);
                }
                if (scans == null) {
                    out.beginObject().name("API_ERROR").value("Failed getting scan results").endObject();
                } else if (scans.isEmpty() && !isLocationEnabled(context)) {
                    String errorMessage = "Location needs to be enabled on the device";
                    out.beginObject().name("API_ERROR").value(errorMessage).endObject();
                } else {
                    out.beginArray();
                    for (ScanResult scan : scans) {
                        out.beginObject();
                        out.name("bssid").value(scan.BSSID);
                        out.name("frequency_mhz").value(scan.frequency);
                        out.name("band").value(bandFromFrequency(scan.frequency));
                        out.name("rssi").value(scan.level);
                        out.name("rssi_percent").value(rssiToPercent(scan.level));
                        out.name("ssid").value(scan.SSID);
                        out.name("timestamp").value(scan.timestamp);

                        int channelWidth = scan.channelWidth;
                        String channelWidthMhz = "???";
                        switch (channelWidth) {
                            case ScanResult.CHANNEL_WIDTH_20MHZ:
                                channelWidthMhz = "20";
                                break;
                            case ScanResult.CHANNEL_WIDTH_40MHZ:
                                channelWidthMhz = "40";
                                break;
                            case ScanResult.CHANNEL_WIDTH_80MHZ:
                                channelWidthMhz = "80";
                                break;
                            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                                channelWidthMhz = "80+80";
                                break;
                            case ScanResult.CHANNEL_WIDTH_160MHZ:
                                channelWidthMhz = "160";
                                break;
                        }
                        out.name("channel_bandwidth_mhz").value(channelWidthMhz);
                        if (channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ) {
                            out.name("center_frequency_mhz").value(scan.centerFreq0);
                        }
                        if (!TextUtils.isEmpty(scan.capabilities)) {
                            out.name("capabilities").value(scan.capabilities);
                        }
                        if (!TextUtils.isEmpty(scan.operatorFriendlyName)) {
                            out.name("operator_name").value(scan.operatorFriendlyName.toString());
                        }
                        if (!TextUtils.isEmpty(scan.venueName)) {
                            out.name("venue_name").value(scan.venueName.toString());
                        }

                        // ── New fields (Task 2) ──
                        if (!TextUtils.isEmpty(scan.capabilities)) {
                            out.name("security_type").value(getSecurityType(scan.capabilities));
                        }
                        out.name("channel").value(frequencyToChannel(scan.frequency));
                        String generation = getScanResultGeneration(scan);
                        if (generation != null) {
                            out.name("wifi_generation").value(generation);
                        }

                        out.endObject();
                    }
                    out.endArray();
                }
            }
        });
    }

    public static void onReceiveWifiEnable(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiEnable");

        ResultReturner.returnData(apiReceiver, intent, out -> {
            WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            boolean state = intent.getBooleanExtra("enabled", false);
            manager.setWifiEnabled(state);
        });
    }

    // ─── Hotspot (Task 3) ─────────────────────────────────────────────

    private static void onReceiveWifiHotspot(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiHotspot");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: LocalOnlyHotspot requires Android 8.0+ (API 26)"));
            return;
        }

        // Check permissions:
        // - Android 13+ needs NEARBY_WIFI_DEVICES + ACCESS_FINE_LOCATION (system requires location for startLocalOnlyHotspot)
        // - Older Android needs ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                return;
            }
        } else {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                return;
            }
        }

        // Guard: system allows only one LocalOnlyHotspot request per UID
        if (hotspotRequestActive) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: A hotspot request is already in progress"));
            return;
        }

        // Launch HotspotConsentActivity to get user consent
        hotspotRequestActive = true;
        Intent hotspotIntent = new Intent(context, HotspotConsentActivity.class);
        hotspotIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResultReturner.copyIntentExtras(intent, hotspotIntent);
        context.startActivity(hotspotIntent);
    }

    /**
     * Activity that requests the system-local-only-hotspot consent dialog.
     * Pattern: identical to SAFAPI$SAFActivity.
     * Starts LocalOnlyHotspot, returns SSID+password via ResultReturner on success.
     */
    public static class HotspotConsentActivity extends AppCompatActivity {

        private boolean resultReturned = false;

        private static final String LOG_TAG = "HotspotConsentActivity";

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            Logger.logDebug(LOG_TAG, "onCreate");
            super.onCreate(savedInstanceState);

            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager == null) {
                ResultReturner.returnData(this, getIntent(), out -> out.println("ERROR: WifiManager not available"));
                resultReturned = true;
                finish();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    // Optional custom AP (Android 11+).  Without these the platform
                    // generates a random SSID and password, which is what the
                    // command used to be stuck with.
                    String desiredSsid = getIntent().getStringExtra("ssid");
                    String desiredPassword = getIntent().getStringExtra("password");
                    int desiredBand = getIntent().getIntExtra("band", 0);
                    int desiredChannel = getIntent().getIntExtra("channel", 0);
                    boolean desiredHidden = getIntent().getBooleanExtra("hidden", false);
                    int desiredMaxClients = getIntent().getIntExtra("max_clients", 0);
                    hotspotBand = desiredBand;
                    hotspotChannel = desiredChannel;
                    hotspotHidden = desiredHidden;
                    Object softApConfig = buildSoftApConfiguration(desiredSsid, desiredPassword,
                        desiredBand, desiredChannel, desiredHidden, desiredMaxClients);
                    hotspotCustomConfig = softApConfig != null;

                    WifiManager.LocalOnlyHotspotCallback callback = new WifiManager.LocalOnlyHotspotCallback() {
                        private void clear() {
                            hotspotRequestActive = false;
                        }

                        @Override
                        public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                            Logger.logDebug(LOG_TAG, "Hotspot started");
                            if (resultReturned) return;
                            resultReturned = true;
                            clear();

                            WifiConfiguration config = reservation.getWifiConfiguration();
                            // Hold on to the reservation: the hotspot lives exactly as
                            // long as this object does, and hotspot-stop releases it.
                            hotspotReservation = reservation;
                            if (config != null) {
                                hotspotSsid = config.SSID != null ? config.SSID.replaceAll("\"", "") : null;
                                hotspotPassword = config.preSharedKey;
                            }
                            ResultReturner.returnData(HotspotConsentActivity.this, getIntent(), new ResultJsonWriter() {
                                @Override
                                public void writeJson(JsonWriter out) throws Exception {
                                    out.beginObject();
                                    if (config != null) {
                                        out.name("ssid").value(config.SSID);
                                        out.name("password").value(config.preSharedKey);
                                    } else {
                                        out.name("API_ERROR").value("Hotspot started but no configuration returned");
                                    }
                                    out.name("custom_config").value(hotspotCustomConfig);
                                    out.name("band").value(hotspotBand == 0 ? "auto" : softApBandToString(hotspotBand));
                                    if (hotspotChannel > 0) out.name("channel").value(hotspotChannel);
                                    if (hotspotHidden) out.name("hidden").value(true);
                                    out.endObject();
                                }
                            });

                            finish();
                        }

                        @Override
                        public void onStopped() {
                            Logger.logDebug(LOG_TAG, "Hotspot stopped");
                            if (resultReturned) return;
                            resultReturned = true;
                            clear();
                            ResultReturner.returnData(HotspotConsentActivity.this, getIntent(),
                                out -> out.println("ERROR: Hotspot was stopped"));
                            finish();
                        }

                        @Override
                        public void onFailed(int reason) {
                            Logger.logDebug(LOG_TAG, "Hotspot failed: " + reason);
                            if (resultReturned) return;
                            resultReturned = true;
                            clear();
                            String reasonStr;
                            switch (reason) {
                                case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                                    reasonStr = "No channel available";
                                    break;
                                case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                                    reasonStr = "Generic error";
                                    break;
                                case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                                    reasonStr = "Incompatible mode (another hotspot active?)";
                                    break;
                                case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                                    reasonStr = "Tethering disallowed";
                                    break;
                                default:
                                    reasonStr = "Unknown error (" + reason + ")";
                            }
                            ResultReturner.returnData(HotspotConsentActivity.this, getIntent(),
                                out -> out.println("ERROR: " + reasonStr));
                            finish();
                        }
                    };
                    // A refused custom config (passphrase shorter than 8 chars,
                    // unsupported band, ...) falls back to the platform-generated
                    // credentials instead of failing the whole command.
                    boolean custom = softApConfig != null
                        && startHotspotWithConfig(manager, callback, softApConfig);
                    if (!custom) {
                        hotspotCustomConfig = false;
                        manager.startLocalOnlyHotspot(callback, null);
                    }
                } catch (IllegalStateException e) {
                    Logger.logError(LOG_TAG, "Hotspot already active: " + e.getMessage());
                    if (!resultReturned) {
                        resultReturned = true;
                        hotspotRequestActive = false;
                        ResultReturner.returnData(this, getIntent(),
                            out -> out.println("ERROR: Hotspot is already active or requested"));
                    }
                    finish();
                }
            } else {
                ResultReturner.returnData(this, getIntent(),
                    out -> out.println("ERROR: LocalOnlyHotspot requires Android 8.0+"));
                resultReturned = true;
                finish();
            }
        }

        @Override
        protected void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");
            super.onDestroy();
            finishAndRemoveTask();
            hotspotRequestActive = false;
            if (!resultReturned) {
                ResultReturner.returnData(this, getIntent(), out -> out.write(""));
                resultReturned = true;
            }
        }
    }

    // ─── Hotspot status / stop ────────────────────────────────────────

    /** @return true when the platform accepted the custom SoftApConfiguration. */
    private static boolean startHotspotWithConfig(WifiManager manager,
                                                  WifiManager.LocalOnlyHotspotCallback callback,
                                                  Object softApConfig) {
        try {
            java.lang.reflect.Method start = WifiManager.class.getMethod("startLocalOnlyHotspot",
                WifiManager.LocalOnlyHotspotCallback.class, Handler.class, softApConfig.getClass());
            start.invoke(manager, callback, null, softApConfig);
            return true;
        } catch (Throwable t) {
            Logger.logWarn(LOG_TAG, "startLocalOnlyHotspot(SoftApConfiguration) rejected: " + t);
            return false;
        }
    }

    /**
     * Build a SoftApConfiguration from the CLI options.
     *
     * The class and its builder are public API 30+ but are not present in the
     * SDK stubs, so everything goes through reflection.  Returns null when no
     * custom option was given (the platform then picks a random SSID/password)
     * or when the platform refuses the values (e.g. a passphrase shorter than 8
     * characters), which callers report instead of hiding.
     */
    private static Object buildSoftApConfiguration(String ssid, String password, int band,
                                                   int channel, boolean hidden, int maxClients) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        if (ssid == null && password == null && band == 0 && channel == 0 && !hidden && maxClients == 0) {
            return null;
        }
        try {
            Class<?> builderClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            if (ssid != null) builderClass.getMethod("setSsid", String.class).invoke(builder, ssid);
            if (password != null) builderClass.getMethod("setPassphrase", String.class).invoke(builder, password);
            if (band > 0) builderClass.getMethod("setBand", int.class).invoke(builder, band);
            if (channel > 0) builderClass.getMethod("setChannel", int.class).invoke(builder, channel);
            if (hidden) builderClass.getMethod("setHiddenSsid", boolean.class).invoke(builder, true);
            if (maxClients > 0) builderClass.getMethod("setMaxNumberOfClients", int.class).invoke(builder, maxClients);
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            Logger.logWarn(LOG_TAG, "SoftApConfiguration rejected the requested values: " + t);
            return null;
        }
    }

    /**
     * Hotspot interfaces the platform exposes (ap0, swlan0, wlan1, softap*).
     *
     * This is how a system hotspot enabled from Android's own Settings becomes
     * visible to us: we are not allowed to ask TetheringManager for its state
     * (that needs TETHER_PRIVILEGED), but the interface list is free.
     */
    private static void writeApInterfaces(JsonWriter out) throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        java.util.List<java.net.NetworkInterface> apInterfaces = new ArrayList<>();
        while (interfaces != null && interfaces.hasMoreElements()) {
            java.net.NetworkInterface iface = interfaces.nextElement();
            String name = iface.getName();
            if (name == null) continue;
            if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1")
                || name.startsWith("softap")) {
                apInterfaces.add(iface);
            }
        }
        out.name("system_hotspot_active").value(!apInterfaces.isEmpty());
        out.name("ap_interfaces");
        out.beginArray();
        for (java.net.NetworkInterface iface : apInterfaces) {
            out.beginObject();
            out.name("name").value(iface.getName());
            out.name("up").value(iface.isUp());
            out.name("addresses");
            out.beginArray();
            for (java.net.InterfaceAddress address : iface.getInterfaceAddresses()) {
                out.value(address.getAddress().getHostAddress() + "/" + address.getNetworkPrefixLength());
            }
            out.endArray();
            out.endObject();
        }
        out.endArray();
    }

    /**
     * Neighbours seen on the hotspot interfaces, read from the kernel ARP table.
     *
     * Android has no public API that lists clients of an access point, so this is
     * the honest best effort: /proc/net/arp entries whose device is one of the AP
     * interfaces.  On builds where the file is unreadable the array stays empty
     * and status_note says why.
     */
    private static void writeHotspotClients(JsonWriter out) {
        out.name("clients");
        out.beginArray();
        try (java.io.BufferedReader reader =
                 new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (!headerSkipped) { headerSkipped = true; continue; }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) continue;
                String device = parts[5];
                if (!(device.startsWith("ap") || device.startsWith("swlan")
                      || device.startsWith("wlan1") || device.startsWith("softap"))) continue;
                boolean complete = !"0x0".equals(parts[2]);
                if (!complete) continue;
                out.beginObject();
                out.name("ip").value(parts[0]);
                out.name("mac").value(parts[3]);
                out.name("interface").value(device);
                out.endObject();
            }
        } catch (Throwable ignored) {
            // /proc/net/arp is restricted on some releases; empty list is fine.
        }
        out.endArray();
    }

    /**
     * Report the hotspot this app started (LocalOnlyHotspot) plus, when the
     * platform exposes it, the system Soft AP configuration.
     *
     * Until now the only hotspot command started one — there was no way to ask
     * "is it up, on which SSID/channel, with which credentials" from a shell.
     */
    private static void onReceiveWifiHotspotStatus(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiHotspotStatus");

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                boolean active = hotspotReservation != null;
                out.name("active").value(active);
                if (active) {
                    out.name("type").value("local_only");
                    if (hotspotSsid != null) out.name("ssid").value(hotspotSsid);
                    if (hotspotPassword != null) out.name("password").value(hotspotPassword);
                    out.name("own_custom_config").value(hotspotCustomConfig);
                    out.name("own_band").value(hotspotBand == 0 ? "auto" : softApBandToString(hotspotBand));
                    if (hotspotChannel > 0) out.name("own_channel").value(hotspotChannel);
                    out.name("own_hidden").value(hotspotHidden);
                }

                // Interfaces tell us whether *a* hotspot (ours or the system one
                // from Settings) is up, which TetheringManager won't disclose
                // without TETHER_PRIVILEGED.
                writeApInterfaces(out);
                writeHotspotClients(out);

                // SoftApConfiguration is readable from Android 11; below that the
                // fields stay absent instead of guessing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        WifiManager manager = (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                        Object config = readProperty(manager, "getSoftApConfiguration");
                        if (config != null) {
                            Object ssid = readProperty(config, "getSsid");
                            if (ssid != null) out.name("system_ssid").value(String.valueOf(ssid));
                            Object band = readProperty(config, "getBand");
                            if (band instanceof Integer) {
                                out.name("system_band").value(softApBandToString((Integer) band));
                            }
                            Object channel = readProperty(config, "getChannel");
                            if (channel instanceof Integer && (Integer) channel != 0) {
                                out.name("system_channel").value((Integer) channel);
                            }
                            Object maxClients = readProperty(config, "getMaxNumberOfClients");
                            if (maxClients instanceof Integer) {
                                out.name("max_clients").value((Integer) maxClients);
                            }
                            Object hidden = readProperty(config, "isHiddenSsid");
                            if (hidden instanceof Boolean) {
                                out.name("hidden").value((Boolean) hidden);
                            }
                        }
                    } catch (Exception e) {
                        // Needs NEARBY_WIFI_DEVICES / location on some builds.
                        // Say why instead of failing the whole command.
                        out.name("status_note")
                           .value("SoftApConfiguration unavailable: " + e.getClass().getSimpleName());
                    }
                }

                out.endObject();
            }
        });
    }

    /** Release the LocalOnlyHotspot this app started, if it still holds it. */
    private static void onReceiveWifiHotspotStop(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiHotspotStop");

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                if (hotspotReservation == null) {
                    out.name("stopped").value(false);
                    out.name("reason").value("No hotspot started by this app is active");
                } else {
                    try {
                        hotspotReservation.close();
                        out.name("stopped").value(true);
                    } catch (Exception e) {
                        out.name("stopped").value(false);
                        out.name("reason").value(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    hotspotReservation = null;
                    hotspotSsid = null;
                    hotspotPassword = null;
                }
                out.endObject();
            }
        });
    }

    /**
     * Enable or disable the *system* Wi-Fi hotspot — the same AP Android's
     * Settings toggles, the one that shares the phone's connection.
     *
     * This is the privileged path: WifiManager.setWifiApEnabled() is @SystemApi
     * and requires android.permission.TETHER_PRIVILEGED, which is
     * signature|privileged.  `pm grant` refuses it; the app has to be installed
     * as a priv-app that is whitelisted in privapp-permissions (or the device
     * has to run the call as root).  When the permission is present the call
     * works; when it is missing the failure is reported verbatim together with
     * the exact XML needed to grant it, instead of failing silently.
     */
    private static void onReceiveWifiHotspotSetEnabled(TermuxApiReceiver apiReceiver, Context context,
                                                       Intent intent, final boolean enable) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiHotspotSetEnabled(" + enable + ")");

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                out.name("requested_state").value(enable ? "enabled" : "disabled");

                // Path that always works, privileged or not: open the system
                // tethering screen so the user can flip it by hand.  The action
                // constant itself is not in the SDK stubs, so the string is used
                // directly, with the wireless settings screen as a fallback.
                if (intent.getBooleanExtra("open_settings", false)) {
                    Intent settings = new Intent("android.settings.TETHER_SETTINGS");
                    settings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(settings);
                        out.name("settings_action").value("android.settings.TETHER_SETTINGS");
                    } catch (android.content.ActivityNotFoundException e) {
                        Intent fallback = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
                        fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(fallback);
                        out.name("settings_action").value("android.settings.WIRELESS_SETTINGS");
                    }
                    out.name("opened_settings").value(true);
                    out.endObject();
                    return;
                }

                String ssid = intent.getStringExtra("ssid");
                String password = intent.getStringExtra("password");
                WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

                try {
                    WifiConfiguration config = null;
                    if (ssid != null) {
                        config = new WifiConfiguration();
                        config.SSID = "\"" + ssid + "\"";
                        if (password != null && !password.isEmpty()) {
                            config.preSharedKey = "\"" + password + "\"";
                            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                        } else {
                            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                        }
                    }
                    java.lang.reflect.Method method = WifiManager.class
                        .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                    Object result = method.invoke(manager, config, enable);
                    boolean accepted = Boolean.TRUE.equals(result);
                    out.name("accepted").value(accepted);
                    if (accepted) {
                        out.name("note").value(enable
                            ? "Hotspot enable requested; give clients a moment to connect"
                            : "Hotspot disable requested");
                    } else {
                        out.name("API_ERROR").value("setWifiApEnabled() returned false");
                    }
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    out.name("accepted").value(false);
                    out.name("error_type").value(cause.getClass().getSimpleName());
                    out.name("error").value(String.valueOf(cause.getMessage()));
                    out.name("required_permission").value("android.permission.TETHER_PRIVILEGED");
                    out.name("grant_note").value(
                        "signature|privileged: `pm grant` rejects it. Install the APK as a priv-app "
                        + "and whitelist it, or perform the call as root.");
                    out.name("privapp_permissions_xml").value(
                        "<permissions><privapp-permissions package=\"com.termux\">"
                        + "<permission name=\"android.permission.TETHER_PRIVILEGED\"/>"
                        + "<permission name=\"android.permission.NETWORK_SETTINGS\"/>"
                        + "</privapp-permissions></permissions>");
                    out.name("settings_fallback").value("termux-wifi hotspot-enable --open-settings");
                } catch (NoSuchMethodException e) {
                    out.name("accepted").value(false);
                    out.name("error_type").value("NoSuchMethodException");
                    out.name("error").value(
                        "WifiManager.setWifiApEnabled() is not available on this Android version");
                    out.name("settings_fallback").value("termux-wifi hotspot-enable --open-settings");
                }
                out.endObject();
            }
        });
    }

    /** Clients seen on the hotspot interfaces (kernel ARP table, best effort). */
    private static void onReceiveWifiHotspotClients(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiHotspotClients");
        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                writeHotspotClients(out);
                out.endObject();
            }
        });
    }

    /**
     * Read a no-argument getter reflectively.
     *
     * SoftApConfiguration's getters (and WifiManager.getSoftApConfiguration)
     * are not part of the public SDK stubs, so they cannot be called directly
     * even though the framework implements them.  Reflection keeps this
     * compiling against any SDK level while still reporting real values on a
     * device, and returns null when the method genuinely does not exist.
     */
    private static Object readProperty(Object target, String getter) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** SoftApConfiguration band constants, as defined by the platform. */
    private static String softApBandToString(int band) {
        switch (band) {
            case 1: return "2.4GHz";
            case 2: return "5GHz";
            case 3: return "6GHz";
            case 4: return "60GHz";
            default: return "unknown";
        }
    }

    // ─── WiFi Direct (P2P) — Task 4 ───────────────────────────────────

    /**
     * Initialize (or return existing) WifiP2pManager and Channel.
     */
    private static WifiP2pManager getP2pManager(Context context) {
        if (p2pManager == null) {
            p2pManager = (WifiP2pManager) context.getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        }
        return p2pManager;
    }

    private static WifiP2pManager.Channel getP2pChannel(Context context) {
        WifiP2pManager manager = getP2pManager(context);
        if (manager == null) return null;
        if (p2pChannel == null) {
            p2pChannel = manager.initialize(context, context.getMainLooper(), null);
        }
        return p2pChannel;
    }

    /**
     * Format a WifiP2pDevice list to JSON.
     */
    private static void writePeersToJson(JsonWriter out, Collection<WifiP2pDevice> peers) throws Exception {
        out.beginArray();
        for (WifiP2pDevice device : peers) {
            out.beginObject();
            out.name("device_name").value(device.deviceName != null ? device.deviceName : "");
            out.name("device_address").value(device.deviceAddress != null ? device.deviceAddress : "");
            String status;
            switch (device.status) {
                case WifiP2pDevice.AVAILABLE:       status = "available"; break;
                case WifiP2pDevice.INVITED:         status = "invited"; break;
                case WifiP2pDevice.CONNECTED:       status = "connected"; break;
                case WifiP2pDevice.FAILED:          status = "failed"; break;
                case WifiP2pDevice.UNAVAILABLE:     status = "unavailable"; break;
                default:                            status = "unknown"; break;
            }
            out.name("status").value(status);
            out.name("primary_device_type").value(device.primaryDeviceType != null ? device.primaryDeviceType : "");
            out.name("is_group_owner").value(device.isGroupOwner());
            out.endObject();
        }
        out.endArray();
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private static void onReceiveP2pDiscover(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveP2pDiscover");

        // Permission check: Android 13+ needs NEARBY_WIFI_DEVICES + ACCESS_FINE_LOCATION
        // (system service enforces location for discoverPeers internally)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                return;
            }
        } else {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                return;
            }
        }

        WifiP2pManager manager = getP2pManager(context);
        WifiP2pManager.Channel channel = getP2pChannel(context);
        if (manager == null || channel == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi P2P not available on this device"));
            return;
        }

        // Ensure WiFi is enabled before P2P discovery
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi must be enabled for P2P discovery"));
            return;
        }

        final int discoverTimeout = intent.getIntExtra("timeout", 10000);

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Logger.logDebug(LOG_TAG, "P2P discover initiated");

                // Wait briefly for peers to be discovered, then request peer list
                try {
                    Thread.sleep(Math.min(discoverTimeout, 10000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                    @Override
                    public void onPeersAvailable(WifiP2pDeviceList peerList) {
                        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                            @Override
                            public void writeJson(JsonWriter out) throws Exception {
                                Collection<WifiP2pDevice> peers = peerList.getDeviceList();
                                out.beginObject();
                                out.name("peers_count").value(peers.size());
                                out.name("peers");
                                writePeersToJson(out, peers);
                                out.endObject();
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                String reasonStr;
                switch (reason) {
                    case WifiP2pManager.BUSY:             reasonStr = "BUSY"; break;
                    case WifiP2pManager.ERROR:            reasonStr = "ERROR"; break;
                    case WifiP2pManager.P2P_UNSUPPORTED:  reasonStr = "P2P_UNSUPPORTED"; break;
                    default:                              reasonStr = "UNKNOWN(" + reason + ")"; break;
                }
                ResultReturner.returnData(apiReceiver, intent,
                    out -> out.println("ERROR: P2P discover failed: " + reasonStr));
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private static void onReceiveP2pConnect(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveP2pConnect");

        String deviceAddress = intent.getStringExtra("address");
        if (deviceAddress == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Missing 'address' extra (MAC address of peer)"));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.NEARBY_WIFI_DEVICES)) {
                return;
            }
        } else {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                return;
            }
        }

        WifiP2pManager manager = getP2pManager(context);
        WifiP2pManager.Channel channel = getP2pChannel(context);
        if (manager == null || channel == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi P2P not available on this device"));
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;

        // Set WPS method (default: PBC, can be overridden via extra)
        String wpsMethod = intent.getStringExtra("wps_method");
        if ("keypad".equals(wpsMethod)) {
            config.wps.setup = WpsInfo.KEYPAD;
        } else if ("display".equals(wpsMethod)) {
            config.wps.setup = WpsInfo.DISPLAY;
        } else {
            config.wps.setup = WpsInfo.PBC;
        }

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Logger.logDebug(LOG_TAG, "P2P connect initiated successfully");
                ResultReturner.returnData(apiReceiver, intent,
                    out -> out.println("Connected to " + deviceAddress));
            }

            @Override
            public void onFailure(int reason) {
                String reasonStr;
                switch (reason) {
                    case WifiP2pManager.BUSY:             reasonStr = "BUSY"; break;
                    case WifiP2pManager.ERROR:            reasonStr = "ERROR"; break;
                    case WifiP2pManager.P2P_UNSUPPORTED:  reasonStr = "P2P_UNSUPPORTED"; break;
                    default:                              reasonStr = "UNKNOWN(" + reason + ")"; break;
                }
                ResultReturner.returnData(apiReceiver, intent,
                    out -> out.println("ERROR: P2P connect failed: " + reasonStr));
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private static void onReceiveP2pGroupInfo(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveP2pGroupInfo");

        WifiP2pManager manager = getP2pManager(context);
        WifiP2pManager.Channel channel = getP2pChannel(context);
        if (manager == null || channel == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi P2P not available on this device"));
            return;
        }

        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject();
                        if (group == null) {
                            out.name("API_ERROR").value("Not part of a P2P group");
                        } else {
                            out.name("network_name").value(group.getNetworkName());
                            out.name("is_group_owner").value(group.isGroupOwner());
                            WifiP2pDevice owner = group.getOwner();
                            if (owner != null) {
                                out.name("owner_device_name").value(owner.deviceName != null ? owner.deviceName : "");
                                out.name("owner_device_address").value(owner.deviceAddress != null ? owner.deviceAddress : "");
                            }
                            out.name("passphrase").value(group.getPassphrase());
                            out.name("interface").value(group.getInterface());
                            Collection<WifiP2pDevice> clients = group.getClientList();
                            out.name("clients_count").value(clients.size());
                            out.name("clients");
                            writePeersToJson(out, clients);
                        }
                        out.endObject();
                    }
                });
            }
        });
    }

    // ─── WiFi Aware (NAN) — Task 5 ────────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    private static void onReceiveAwareAttach(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveAwareAttach");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                    Manifest.permission.NEARBY_WIFI_DEVICES)) {
                return;
            }
        }

        if (awareManager == null) {
            awareManager = (WifiAwareManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_AWARE_SERVICE);
        }
        if (awareManager == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi Aware not available on this device"));
            return;
        }

        if (!awareManager.isAvailable()) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi Aware is not available on this device"));
            return;
        }

        awareManager.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                Logger.logDebug(LOG_TAG, "Aware session attached");
                awareSession = session;
                ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject();
                        out.name("status").value("attached");
                        out.name("session_id").value(System.identityHashCode(session));
                        out.endObject();
                    }
                });
            }

            @Override
            public void onAwareSessionTerminated() {
                Logger.logDebug(LOG_TAG, "Aware session terminated");
                awareSession = null;
            }
        }, new Handler(Looper.getMainLooper()));
    }

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    private static void onReceiveAwarePublish(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveAwarePublish");

        if (awareSession == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Not attached to WiFi Aware. Run 'aware-attach' first."));
            return;
        }

        String serviceName = intent.getStringExtra("service_name");
        if (serviceName == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Missing 'service_name' extra"));
            return;
        }

        byte[] serviceSpecificInfo = intent.getStringExtra("service_info") != null
            ? intent.getStringExtra("service_info").getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : null;

        PublishConfig config = new PublishConfig.Builder()
                .setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo)
                .build();

        awareSession.publish(config, new DiscoverySessionCallback() {
            @Override
            public void onPublishStarted(PublishDiscoverySession session) {
                Logger.logDebug(LOG_TAG, "Aware publish started for: " + serviceName);
                ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject();
                        out.name("status").value("publishing");
                        out.name("service_name").value(serviceName);
                        out.endObject();
                    }
                });
            }

            @Override
            public void onSessionConfigFailed() {
                ResultReturner.returnData(apiReceiver, intent,
                    out -> out.println("ERROR: Failed to configure publish session"));
            }

            @Override
            public void onSessionTerminated() {
                Logger.logDebug(LOG_TAG, "Aware publish session terminated");
            }
        }, new Handler(Looper.getMainLooper()));
    }

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    private static void onReceiveAwareSubscribe(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveAwareSubscribe");

        if (awareSession == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Not attached to WiFi Aware. Run 'aware-attach' first."));
            return;
        }

        String serviceName = intent.getStringExtra("service_name");
        if (serviceName == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Missing 'service_name' extra"));
            return;
        }

        SubscribeConfig config = new SubscribeConfig.Builder()
                .setServiceName(serviceName)
                .build();

        awareSession.subscribe(config, new DiscoverySessionCallback() {
            @Override
            public void onSubscribeStarted(SubscribeDiscoverySession session) {
                Logger.logDebug(LOG_TAG, "Aware subscribe started for: " + serviceName);
                ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject();
                        out.name("status").value("subscribing");
                        out.name("service_name").value(serviceName);
                        out.endObject();
                    }
                });
            }

            @Override
            public void onSessionConfigFailed() {
                ResultReturner.returnData(apiReceiver, intent,
                    out -> out.println("ERROR: Failed to configure subscribe session"));
            }

            @Override
            public void onSessionTerminated() {
                Logger.logDebug(LOG_TAG, "Aware subscribe session terminated");
            }
        }, new Handler(Looper.getMainLooper()));
    }

    // ─── WiFi RTT (Ranging) — Task 5 ──────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static void onReceiveRttRange(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveRttRange");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi RTT requires Android 9.0+ (API 28)"));
            return;
        }

        String targetBssid = intent.getStringExtra("bssid");
        if (targetBssid == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Missing 'bssid' extra"));
            return;
        }

        WifiRttManager rttManager = (WifiRttManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        if (rttManager == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: WiFi RTT not available on this device"));
            return;
        }

        // Build a ranging request: add the target by BSSID (must be from scan results)
        RangingRequest.Builder requestBuilder = new RangingRequest.Builder();

        // Try to find the ScanResult for this BSSID from the most recent scan
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        List<ScanResult> scanResults = wifiManager.getScanResults();
        boolean found = false;
        if (scanResults != null) {
            for (ScanResult scan : scanResults) {
                if (targetBssid.equalsIgnoreCase(scan.BSSID)) {
                    requestBuilder.addAccessPoint(scan);
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: BSSID '" + targetBssid
                    + "' not found in recent scan results. Run 'scan-info' first."));
            return;
        }

        RangingRequest request = requestBuilder.build();

        Executors.newSingleThreadExecutor().execute(() -> {
            rttManager.startRanging(request, Executors.newSingleThreadExecutor(),
                new RangingResultCallback() {
                    @Override
                    public void onRangingFailure(int code) {
                        ResultReturner.returnData(apiReceiver, intent,
                            out -> out.println("ERROR: RTT ranging failed with code " + code));
                    }

                    @Override
                    public void onRangingResults(List<RangingResult> results) {
                        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                            @Override
                            public void writeJson(JsonWriter out) throws Exception {
                                out.beginArray();
                                for (RangingResult result : results) {
                                    out.beginObject();
                                    out.name("mac_address").value(result.getMacAddress().toString());
                                    out.name("distance_mm").value(result.getDistanceMm());
                                    out.name("distance_std_dev_mm").value(result.getDistanceStdDevMm());
                                    out.name("rssi").value(result.getRssi());
                                    out.name("num_attempted_measurements")
                                        .value(result.getNumAttemptedMeasurements());
                                    out.name("num_successful_measurements")
                                        .value(result.getNumSuccessfulMeasurements());
                                    int status = result.getStatus();
                                    String statusStr;
                                    switch (status) {
                                        case RangingResult.STATUS_SUCCESS:   statusStr = "success"; break;
                                        case RangingResult.STATUS_FAIL:      statusStr = "fail"; break;
                                        default:                            statusStr = "unknown(" + status + ")"; break;
                                    }
                                    out.name("status").value(statusStr);
                                    out.endObject();
                                }
                                out.endArray();
                            }
                        });
                    }
                });
        });
    }

    // ─── Network Suggestions — Task 5 ─────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static void onReceiveNetworkSuggestion(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceiveNetworkSuggestion");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Network suggestions require Android 10.0+ (API 29)"));
            return;
        }

        String ssid = intent.getStringExtra("ssid");
        if (ssid == null) {
            ResultReturner.returnData(apiReceiver, intent,
                out -> out.println("ERROR: Missing 'ssid' extra"));
            return;
        }

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid);

        String password = intent.getStringExtra("password");
        String securityType = intent.getStringExtra("security_type");
        boolean isHidden = intent.getBooleanExtra("is_hidden", false);
        boolean isAppInteraction = intent.getBooleanExtra("app_interaction_required", false);

        if (isHidden) {
            builder.setIsHiddenSsid(true);
        }

        if (isAppInteraction) {
            builder.setIsAppInteractionRequired(true);
        }

        if (password != null && securityType != null) {
            switch (securityType.toUpperCase()) {
                case "WPA2":
                    builder.setWpa2Passphrase(password);
                    break;
                case "WPA3":
                    builder.setWpa3Passphrase(password);
                    break;
                default:
                    builder.setWpa2Passphrase(password);
                    break;
            }
        }
        // If no password: open network (no credential set)

        WifiNetworkSuggestion suggestion = builder.build();

        WifiManager manager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        List<WifiNetworkSuggestion> suggestionsList = Collections.singletonList(suggestion);
        int result = manager.addNetworkSuggestions(suggestionsList);

        final String resultMsg;
        switch (result) {
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
                resultMsg = "success";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
                resultMsg = "duplicate";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                resultMsg = "exceeded_max_per_app";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                resultMsg = "not_allowed";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
                resultMsg = "add_invalid";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                resultMsg = "app_disallowed";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL:
                resultMsg = "internal_error";
                break;
            default:
                resultMsg = "unknown(" + result + ")";
        }

        final boolean success = result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                out.name("status").value(success ? "success" : "error");
                out.name("result").value(resultMsg);
                if (success) {
                    out.name("ssid").value(ssid);
                }
                out.endObject();
            }
        });
    }
}
