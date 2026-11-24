package com.aegisvip.vpn

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity hosts the AegisVIP VPN WebView interface (assets/index.html)
 * and bridges connect/disconnect requests to Android's VpnService and AegisVpnService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingServerName: String = "Singapore Free #01"

    // Launches the system VPN consent dialog when VpnService.prepare() returns a non-null Intent
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == RESULT_OK) {
                    launchVpnService(pendingServerName)
                    notifyWebState(connected = true, permissionGranted = true)
                } else {
                    notifyWebState(connected = false, permissionGranted = false)
                }
            } catch (_: Exception) {
                notifyWebState(connected = false, permissionGranted = false)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            webView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = WebViewClient()
                addJavascriptInterface(VpnBridge(), "AndroidVpn")
            }
            setContentView(webView)

            // Embed the complete AegisVIP VPN UI directly so MainActivity.kt renders it without needing external asset files
            val embeddedHtml = """
<!DOCTYPE html>
<html lang="km" class="dark">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>AegisVIP VPN — Mobile App</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Noto+Sans+Khmer:wght@400;500;600;700&display=swap" rel="stylesheet" />
  <script>
    tailwind.config = {
      darkMode: 'class',
      theme: {
        extend: {
          fontFamily: {
            sans: ['"Noto Sans Khmer"', 'Inter', 'sans-serif'],
          },
          colors: {
            obsidian: '#060a11',
            surface: '#0d1522',
            card: '#111b2b',
            skyAccent: '#38bdf8',
            vipGold: '#f59e0b',
          },
        },
      },
    };
  </script>
  <style>
    body {
      background-color: #060a11;
      color: #f1f5f9;
      -webkit-tap-highlight-color: transparent;
      overflow-x: hidden;
    }

    @keyframes sonarPulse {
      0% {
        transform: scale(0.96);
        opacity: 0.65;
      }
      75% {
        transform: scale(1.22);
        opacity: 0;
      }
      100% {
        transform: scale(1.22);
        opacity: 0;
      }
    }

    .sonar-ring {
      animation: sonarPulse 2.1s cubic-bezier(0.22, 1, 0.36, 1) infinite;
    }

    .no-scrollbar::-webkit-scrollbar {
      display: none;
    }
    .no-scrollbar {
      -ms-overflow-style: none;
      scrollbar-width: none;
    }
  </style>
</head>
<body class="min-h-screen bg-obsidian text-slate-100 antialiased">
  <div class="mx-auto flex min-h-screen w-full max-w-md flex-col bg-obsidian sm:my-4 sm:min-h-[840px] sm:rounded-[28px] sm:border sm:border-slate-800/80 sm:shadow-2xl overflow-hidden">

    <header class="flex items-center justify-between border-b border-slate-800/70 bg-obsidian/95 px-4 py-3">
      <div class="flex items-center gap-2.5 min-w-0">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-sky-400/20 to-indigo-500/20 border border-sky-400/40 text-sky-400 shadow-inner">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 3l7 3.8v5.2c0 4.9-3.2 8.4-7 9.9-3.8-1.5-7-5-7-9.9V6.8L12 3z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M9.5 12.2l1.7 1.7 3.4-3.6" />
          </svg>
        </div>

        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <h1 class="truncate text-base font-extrabold tracking-tight text-white">AegisVIP VPN</h1>
            <span id="headerBadge" class="rounded-md border border-slate-700 bg-slate-800/90 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-slate-300">
              FREE
            </span>
          </div>
          <p id="headerSubtext" class="truncate text-[11px] text-slate-400">កញ្ចប់មូលដ្ឋាន • គ្មានកំណត់ថ្ងៃ</p>
        </div>
      </div>

      <div class="flex items-center gap-1.5 shrink-0">
        <button id="btnSync" type="button" class="rounded-xl border border-sky-500/35 bg-sky-500/10 px-3 py-1.5 text-xs font-bold text-sky-300 transition hover:bg-sky-500/20 active:scale-95">
          Sync
        </button>
        <button id="btnTestVip" type="button" class="rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-xs font-bold text-amber-300 transition hover:bg-amber-500/20 active:scale-95">
          តេស្ត VIP
        </button>
      </div>
    </header>

    <div id="bannerBox" class="hidden mx-4 mt-3 flex items-center justify-between gap-2 rounded-2xl border px-3.5 py-2.5 text-xs font-medium shadow-lg">
      <span id="bannerText"></span>
      <button id="bannerClose" type="button" class="px-1 text-slate-400 hover:text-white">✕</button>
    </div>

    <main class="flex-1 overflow-y-auto px-4 pb-24 pt-4 no-scrollbar">

      <section id="view-home" class="space-y-4">
        <div class="rounded-3xl border border-slate-800/80 bg-surface/90 p-5 text-center shadow-xl">
          <div class="inline-flex items-center gap-2 rounded-full border border-slate-700/70 bg-obsidian/80 px-3.5 py-1.5 text-xs">
            <span id="statusIndicator" class="h-2.5 w-2.5 rounded-full bg-emerald-400"></span>
            <span id="statusText" class="font-bold text-emerald-300">បានភ្ជាប់សុវត្ថិភាព (Connected)</span>
          </div>

          <div class="my-6 flex items-center justify-center">
            <div class="relative flex items-center justify-center">
              <div id="powerPulse" class="sonar-ring absolute h-36 w-36 rounded-full bg-emerald-400/20"></div>
              <button id="btnPower" type="button" class="relative z-10 flex h-36 w-36 flex-col items-center justify-center rounded-full border-[3px] border-emerald-400/90 bg-gradient-to-b from-emerald-950/70 to-obsidian text-white shadow-2xl transition duration-200 active:scale-95">
                <svg id="powerSvg" class="mb-1.5 h-9 w-9 text-emerald-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v9" />
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6.36 6.36a8 8 0 1011.28 0" />
                </svg>
                <span id="powerLabel" class="text-sm font-bold text-slate-100">ផ្តាច់</span>
              </button>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-3 rounded-2xl border border-slate-800/70 bg-obsidian/70 p-3.5 text-left">
            <div>
              <span class="block text-[11px] font-medium text-slate-400">Protocol</span>
              <span id="homeProtocol" class="mt-0.5 block text-xs font-bold text-sky-400">WireGuard + TLS</span>
            </div>
            <div>
              <span class="block text-[11px] font-medium text-slate-400">Virtual IP</span>
              <span id="homeIp" class="mt-0.5 block font-mono text-xs font-semibold leading-snug text-slate-200">
                103.21.244.18<br/>(Secured)
              </span>
            </div>
          </div>
        </div>

        <div class="flex items-center justify-between gap-3 rounded-2xl border border-slate-800/80 bg-card/90 p-3.5 shadow-md">
          <div class="flex items-center gap-3 min-w-0">
            <span id="homeFlag" class="text-2xl leading-none">🇸🇬</span>
            <div class="min-w-0">
              <div class="flex items-center gap-1.5">
                <p id="homeServerName" class="truncate text-sm font-extrabold text-white">Singapore Free #01</p>
                <span id="homeServerBadge" class="rounded bg-slate-800 border border-slate-700 px-1.5 py-0.5 text-[10px] font-bold text-slate-300">
                  FREE
                </span>
              </div>
              <p id="homeServerMeta" class="mt-0.5 truncate text-xs text-slate-400">Latency: 38 ms • Load: 44%</p>
            </div>
          </div>

          <button type="button" data-go-tab="servers" class="shrink-0 rounded-xl border border-slate-700/80 bg-slate-800/90 px-3.5 py-2 text-xs font-bold text-slate-200 transition hover:bg-slate-700 active:scale-95">
            ប្តូរ Server
          </button>
        </div>

        <div class="grid grid-cols-3 gap-2.5">
          <div class="rounded-2xl border border-slate-800/80 bg-card/90 p-3 text-center">
            <p class="text-[11px] font-medium text-slate-400">រយៈពេល</p>
            <p id="metricDuration" class="mt-1 font-mono text-sm font-extrabold text-white">00:00:57</p>
          </div>
          <div class="rounded-2xl border border-slate-800/80 bg-card/90 p-3 text-center">
            <p class="text-[11px] font-medium text-slate-400">ទាញយក (↓)</p>
            <p id="metricDown" class="mt-1 font-mono text-sm font-extrabold text-emerald-400">31.6 MB</p>
          </div>
          <div class="rounded-2xl border border-slate-800/80 bg-card/90 p-3 text-center">
            <p class="text-[11px] font-medium text-slate-400">ផ្ញើចេញ (↑)</p>
            <p id="metricUp" class="mt-1 font-mono text-sm font-extrabold text-sky-400">9.3 MB</p>
          </div>
        </div>

        <div id="vipBanner" class="flex items-center justify-between gap-3 rounded-2xl border border-amber-500/35 bg-gradient-to-r from-amber-950/50 via-amber-900/20 to-card p-4">
          <div class="min-w-0">
            <h2 class="text-xs font-extrabold text-amber-400">ដំឡើងទៅកញ្ចប់ VIP</h2>
            <p class="mt-1 text-xs leading-relaxed text-slate-300">
              ទទួលបាន Server ល្បឿនលឿន Ping ទាប គ្មានការកំណត់ និងប្រើបានច្រើនឧបករណ៍។
            </p>
          </div>
          <button type="button" data-go-tab="vip" class="shrink-0 rounded-xl bg-amber-400 px-3.5 py-2 text-xs font-extrabold text-slate-950 shadow-md transition hover:bg-amber-300 active:scale-95">
            មើល VIP
          </button>
        </div>
      </section>

      <section id="view-servers" class="hidden space-y-4">
        <div class="flex items-center justify-between gap-2">
          <div>
            <h2 class="text-base font-extrabold text-white">បញ្ជី Server (Free &amp; VIP)</h2>
            <p id="serverModeSubtitle" class="text-xs text-slate-400">Mode: Local Preview / Fallback</p>
          </div>
          <button id="btnPing" type="button" class="rounded-xl border border-slate-700/80 bg-card px-3.5 py-2 text-xs font-bold text-slate-200 transition hover:bg-slate-800 active:scale-95">
            ↻ វាស់ Ping
          </button>
        </div>

        <div class="flex items-center gap-2">
          <button type="button" data-filter="all" class="filter-pill rounded-xl bg-sky-400 px-4 py-1.5 text-xs font-extrabold text-slate-950">ទាំងអស់</button>
          <button type="button" data-filter="free" class="filter-pill rounded-xl border border-slate-700/80 bg-card px-4 py-1.5 text-xs font-bold text-slate-300">Free</button>
          <button type="button" data-filter="vip" class="filter-pill rounded-xl border border-amber-500/35 bg-amber-950/35 px-4 py-1.5 text-xs font-bold text-amber-300">VIP Nodes</button>
        </div>

        <div id="serverCards" class="space-y-3"></div>
      </section>

      <section id="view-vip" class="hidden space-y-4">
        <div class="rounded-3xl border border-amber-500/35 bg-gradient-to-br from-amber-950/50 via-surface to-obsidian p-4">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-[11px] font-semibold text-amber-300">ស្ថានភាពកញ្ចប់បច្ចុប្បន្ន</p>
              <h2 id="vipStatusTitle" class="mt-0.5 text-lg font-extrabold text-white">Free Account</h2>
            </div>
            <span id="vipDaysBadge" class="rounded-full border border-slate-700 bg-slate-800 px-3 py-1 text-xs font-bold text-slate-300">0 ថ្ងៃសល់</span>
          </div>
          <p id="vipStatusDesc" class="mt-2 text-xs leading-relaxed text-slate-300">
            កំពុងប្រើប្រាស់ Server Free។ បើកប្រើកញ្ចប់ VIP ដើម្បីដោះសោ Server ល្បឿនលឿនទាំងអស់។
          </p>
        </div>

        <div class="space-y-2.5">
          <h3 class="text-sm font-extrabold text-white">ជ្រើសរើសកញ្ចប់ VIP</h3>

          <div class="flex items-center justify-between rounded-2xl border border-slate-800 bg-card p-3.5">
            <div>
              <span class="rounded bg-sky-500/15 px-2 py-0.5 text-[10px] font-bold text-sky-300">៧ ថ្ងៃ</span>
              <p class="mt-1 text-sm font-bold text-white">VIP Weekly Pass</p>
              <p class="text-xs text-slate-400">បើក Server VIP ទាំងអស់ • 2 ឧបករណ៍</p>
            </div>
            <div class="text-right">
              <p class="text-sm font-extrabold text-amber-400">$2.99</p>
              <button type="button" data-plan-days="7" data-plan-name="VIP Weekly (7 ថ្ងៃ)" class="plan-buy-btn mt-1.5 rounded-xl bg-amber-400 px-3 py-1.5 text-xs font-extrabold text-slate-950 hover:bg-amber-300 active:scale-95">បើកប្រើ</button>
            </div>
          </div>

          <div class="flex items-center justify-between rounded-2xl border-2 border-amber-400/75 bg-gradient-to-r from-amber-950/35 to-card p-3.5">
            <div>
              <span class="rounded bg-amber-400 px-2 py-0.5 text-[10px] font-extrabold text-slate-950">ពេញនិយម • ៣០ ថ្ងៃ</span>
              <p class="mt-1 text-sm font-bold text-white">VIP Monthly Pro</p>
              <p class="text-xs text-slate-300">Ping ទាបបំផុត • 3 ឧបករណ៍</p>
            </div>
            <div class="text-right">
              <p class="text-sm font-extrabold text-amber-300">$6.99</p>
              <button type="button" data-plan-days="30" data-plan-name="VIP Monthly (30 ថ្ងៃ)" class="plan-buy-btn mt-1.5 rounded-xl bg-amber-400 px-3 py-1.5 text-xs font-extrabold text-slate-950 hover:bg-amber-300 active:scale-95">បើកប្រើ</button>
            </div>
          </div>

          <div class="flex items-center justify-between rounded-2xl border border-slate-800 bg-card p-3.5">
            <div>
              <span class="rounded bg-indigo-500/20 px-2 py-0.5 text-[10px] font-bold text-indigo-300">៩០ ថ្ងៃ</span>
              <p class="mt-1 text-sm font-bold text-white">VIP Quarterly Bundle</p>
              <p class="text-xs text-slate-400">បើក Server VIP ទាំងអស់ • 5 ឧបករណ៍</p>
            </div>
            <div class="text-right">
              <p class="text-sm font-extrabold text-amber-400">$16.99</p>
              <button type="button" data-plan-days="90" data-plan-name="VIP Quarterly (90 ថ្ងៃ)" class="plan-buy-btn mt-1.5 rounded-xl bg-amber-400 px-3 py-1.5 text-xs font-extrabold text-slate-950 hover:bg-amber-300 active:scale-95">បើកប្រើ</button>
            </div>
          </div>
        </div>

        <div class="rounded-2xl border border-slate-800 bg-card p-4">
          <h4 class="text-xs font-bold text-slate-200">បញ្ចូលកូដ Voucher</h4>
          <p class="mt-1 text-[11px] text-slate-400">សាកល្បងកូដ <code class="text-sky-300">VIP30</code> ឬ <code class="text-sky-300">VIP7</code></p>
          <form id="voucherForm" class="mt-3 flex gap-2">
            <input id="voucherInput" type="text" placeholder="VIP30" class="flex-1 rounded-xl border border-slate-700 bg-obsidian px-3 py-2 text-xs font-bold uppercase text-white placeholder-slate-500 focus:border-sky-400 focus:outline-none" />
            <button type="submit" class="rounded-xl bg-sky-400 px-4 py-2 text-xs font-extrabold text-slate-950 hover:bg-sky-300 active:scale-95">បញ្ចូល</button>
          </form>
        </div>
      </section>

      <section id="view-account" class="hidden space-y-4">
        <div class="rounded-2xl border border-slate-800 bg-card p-4 space-y-3">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-[11px] text-slate-400">Device ID</p>
              <p id="accDeviceId" class="font-mono text-sm font-bold text-white">DEV-KH-8842</p>
            </div>
            <span id="accBadge" class="rounded-lg bg-slate-800 px-2.5 py-1 text-xs font-bold text-slate-300">FREE</span>
          </div>

          <div class="grid grid-cols-2 gap-2 border-t border-slate-800 pt-3 text-xs">
            <div>
              <span class="block text-[11px] text-slate-400">Device Limit</span>
              <span id="accLimit" class="font-bold text-slate-200">1 Device</span>
            </div>
            <div>
              <span class="block text-[11px] text-slate-400">ផុតកំណត់ VIP</span>
              <span id="accExpiry" class="font-bold text-slate-200">មិនមាន</span>
            </div>
          </div>

          <div class="flex gap-2 pt-1">
            <button id="btnExpireVip" type="button" class="flex-1 rounded-xl border border-rose-500/35 bg-rose-500/10 py-2 text-xs font-bold text-rose-300 hover:bg-rose-500/20 active:scale-95">បញ្ចប់ VIP</button>
            <button id="btnResetAll" type="button" class="rounded-xl border border-slate-700 bg-slate-800 px-3.5 py-2 text-xs font-bold text-slate-300 hover:bg-slate-700 active:scale-95">Reset</button>
          </div>
        </div>
      </section>
    </main>

    <nav class="fixed bottom-0 left-1/2 z-20 flex w-full max-w-md -translate-x-1/2 items-center justify-around border-t border-slate-800/80 bg-obsidian/95 px-2 py-2 sm:static sm:translate-x-0">
      <button type="button" data-nav="home" class="tab-btn flex flex-1 flex-col items-center gap-1 rounded-xl py-1 text-sky-400 font-bold">
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M3 10.5L12 3l9 7.5V20a1 1 0 01-1 1h-5v-6H9v6H4a1 1 0 01-1-1v-9.5z" />
        </svg>
        <span class="text-[11px]">ភ្ជាប់</span>
      </button>

      <button type="button" data-nav="servers" class="tab-btn flex flex-1 flex-col items-center gap-1 rounded-xl py-1 text-slate-400 hover:text-slate-200">
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="4" width="18" height="6" rx="2" />
          <rect x="3" y="14" width="18" height="6" rx="2" />
          <circle cx="7" cy="7" r="1" fill="currentColor" />
          <circle cx="7" cy="17" r="1" fill="currentColor" />
        </svg>
        <span class="text-[11px]">Server</span>
      </button>

      <button type="button" data-nav="vip" class="tab-btn flex flex-1 flex-col items-center gap-1 rounded-xl py-1 text-slate-400 hover:text-amber-300">
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M3 7l4.5 5L12 5l4.5 7L21 7l-2 11H5L3 7z" />
        </svg>
        <span class="text-[11px]">VIP</span>
      </button>

      <button type="button" data-nav="account" class="tab-btn flex flex-1 flex-col items-center gap-1 rounded-xl py-1 text-slate-400 hover:text-slate-200">
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="8" r="4" />
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 20a7 8 0 0114 0" />
        </svg>
        <span class="text-[11px]">គណនី</span>
      </button>
    </nav>
  </div>

  <script>
    (function () {
      const STORAGE_KEY = 'aegisvip_ui_state_v1';

      const INITIAL_SERVERS = [
        { id: 'sg-free-01', name: 'Singapore Free #01', flag: '🇸🇬', tier: 'free', ping: 38, load: 44, ip: '103.21.244.18', protocol: 'WireGuard + TLS' },
        { id: 'jp-free-02', name: 'Tokyo Free #02', flag: '🇯🇵', tier: 'free', ping: 64, load: 61, ip: '45.76.192.11', protocol: 'WireGuard + TLS' },
        { id: 'sg-vip-ultra', name: 'Singapore VIP Ultra', flag: '🇸🇬', tier: 'vip', ping: 19, load: 18, ip: '172.65.210.9', protocol: 'WireGuard Turbo' },
        { id: 'hk-vip-gaming', name: 'Hong Kong VIP Low-Ping', flag: '🇭🇰', tier: 'vip', ping: 24, load: 22, ip: '198.18.42.77', protocol: 'Xray / Reality' },
        { id: 'us-vip-stream', name: 'Los Angeles VIP Stream', flag: '🇺🇸', tier: 'vip', ping: 118, load: 27, ip: '104.28.14.66', protocol: 'WireGuard Turbo' },
      ];

      function defaultState() {
        return {
          deviceId: 'DEV-KH-8842',
          vipExpiresAt: null,
          deviceLimit: 1,
          selectedServerId: 'sg-free-01',
          filter: 'all',
          connected: true,
          seconds: 57,
          downMb: 31.6,
          upMb: 9.3,
          servers: INITIAL_SERVERS.map((s) => ({ ...s })),
        };
      }

      let appState = loadSavedState();
      let tickTimer = null;
      let bannerTimer = null;

      function loadSavedState() {
        try {
          const raw = localStorage.getItem(STORAGE_KEY);
          if (!raw) return defaultState();
          const parsed = JSON.parse(raw);
          const base = defaultState();
          return {
            ...base,
            ...parsed,
            servers: Array.isArray(parsed.servers) && parsed.servers.length ? parsed.servers : base.servers,
          };
        } catch (_e) {
          return defaultState();
        }
      }

      function persistState() {
        try {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(appState));
        } catch (_e) {}
      }

      function hasActiveVip() {
        return typeof appState.vipExpiresAt === 'number' && appState.vipExpiresAt > Date.now();
      }

      function remainingVipDays() {
        if (!hasActiveVip()) return 0;
        return Math.max(1, Math.ceil((appState.vipExpiresAt - Date.now()) / (1000 * 60 * 60 * 24)));
      }

      function formatClock(sec) {
        const h = Math.floor(sec / 3600);
        const m = Math.floor((sec % 3600) / 60);
        const s = sec % 60;
        return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':');
      }

      function showBanner(msg, variant = 'info') {
        const box = document.getElementById('bannerBox');
        const txt = document.getElementById('bannerText');
        if (!box || !txt) return;

        txt.textContent = msg;
        box.className = 'mx-4 mt-3 flex items-center justify-between gap-2 rounded-2xl border px-3.5 py-2.5 text-xs font-semibold shadow-lg';

        if (variant === 'success') {
          box.classList.add('border-emerald-500/40', 'bg-emerald-950/90', 'text-emerald-200');
        } else if (variant === 'warn') {
          box.classList.add('border-amber-500/40', 'bg-amber-950/90', 'text-amber-200');
        } else {
          box.classList.add('border-sky-500/40', 'bg-surface', 'text-sky-200');
        }

        box.classList.remove('hidden');
        if (bannerTimer) clearTimeout(bannerTimer);
        bannerTimer = setTimeout(() => {
          box.classList.add('hidden');
        }, 3200);
      }

      function currentServer() {
        return appState.servers.find((s) => s.id === appState.selectedServerId) || appState.servers[0];
      }

      function getNativeVpnBridge() {
        return typeof window !== 'undefined' && window.AndroidVpn ? window.AndroidVpn : null;
      }

      function applyNativeState(connected, permissionGranted = true) {
        if (!permissionGranted && connected) {
          appState.connected = false;
          persistState();
          stopLoop();
          renderHome();
          showBanner('មិនបានផ្តល់សិទ្ធិ VPN លើឧបករណ៍។', 'warn');
          return;
        }

        appState.connected = Boolean(connected);
        persistState();
        if (appState.connected) {
          startLoop();
          showBanner('បានភ្ជាប់ទៅ ' + currentServer().name, 'success');
        } else {
          stopLoop();
          showBanner('បានផ្តាច់ការភ្ជាប់ VPN។', 'info');
        }
        renderHome();
      }

      window.onNativeVpnState = function (connected, permissionGranted = true) {
        applyNativeState(connected, permissionGranted);
      };

      function guardVipServerSelection() {
        const srv = currentServer();
        if (srv && srv.tier === 'vip' && !hasActiveVip()) {
          const freeSrv = appState.servers.find((s) => s.tier === 'free') || appState.servers[0];
          appState.selectedServerId = freeSrv.id;
          persistState();
        }
      }

      function grantVip(days, label) {
        const startFrom = hasActiveVip() ? appState.vipExpiresAt : Date.now();
        appState.vipExpiresAt = startFrom + days * 24 * 60 * 60 * 1000;
        appState.deviceLimit = days >= 90 ? 5 : days >= 30 ? 3 : 2;
        persistState();
        renderAll();
        showBanner('បានបើកប្រើ ' + label + ' រួចរាល់!', 'success');
      }

      function revokeVip() {
        appState.vipExpiresAt = null;
        appState.deviceLimit = 1;
        guardVipServerSelection();
        persistState();
        renderAll();
        showBanner('បានត្រឡប់ទៅកញ្ចប់ FREE។', 'warn');
      }

      function setTab(tabName) {
        ['home', 'servers', 'vip', 'account'].forEach((name) => {
          const el = document.getElementById('view-' + name);
          if (el) el.classList.toggle('hidden', name !== tabName);
        });

        document.querySelectorAll('.tab-btn').forEach((btn) => {
          const active = btn.getAttribute('data-nav') === tabName;
          btn.classList.toggle('text-sky-400', active && tabName !== 'vip');
          btn.classList.toggle('text-amber-300', active && tabName === 'vip');
          btn.classList.toggle('font-bold', active);
          btn.classList.toggle('text-slate-400', !active);
        });
      }

      function startLoop() {
        stopLoop();
        tickTimer = setInterval(() => {
          if (!appState.connected) return;
          const srv = currentServer();
          const factor = srv && srv.tier === 'vip' ? 2.2 : 0.8;
          appState.seconds += 1;
          appState.downMb = Number((appState.downMb + (0.25 + Math.random() * 0.55) * factor).toFixed(1));
          appState.upMb = Number((appState.upMb + (0.06 + Math.random() * 0.18) * factor).toFixed(1));
          persistState();
          renderMetrics();
        }, 1000);
      }

      function stopLoop() {
        if (tickTimer) {
          clearInterval(tickTimer);
          tickTimer = null;
        }
      }

      function renderMetrics() {
        const dur = document.getElementById('metricDuration');
        const down = document.getElementById('metricDown');
        const up = document.getElementById('metricUp');
        if (dur) dur.textContent = formatClock(appState.seconds);
        if (down) down.textContent = appState.downMb.toFixed(1) + ' MB';
        if (up) up.textContent = appState.upMb.toFixed(1) + ' MB';
      }

      function renderHeaderAndVip() {
        const vip = hasActiveVip();
        const days = remainingVipDays();

        const badge = document.getElementById('headerBadge');
        const sub = document.getElementById('headerSubtext');
        const banner = document.getElementById('vipBanner');

        if (badge) {
          if (vip) {
            badge.textContent = 'VIP';
            badge.className = 'rounded-md bg-amber-400 px-2 py-0.5 text-[10px] font-extrabold uppercase tracking-wider text-slate-950';
          } else {
            badge.textContent = 'FREE';
            badge.className = 'rounded-md border border-slate-700 bg-slate-800/90 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-slate-300';
          }
        }

        if (sub) {
          sub.textContent = vip ? ('សកម្មភាព VIP • សល់ ' + days + ' ថ្ងៃ') : 'កញ្ចប់មូលដ្ឋាន • គ្មានកំណត់ថ្ងៃ';
        }

        if (banner) {
          banner.classList.toggle('hidden', vip);
        }

        const vipTitle = document.getElementById('vipStatusTitle');
        const vipDays = document.getElementById('vipDaysBadge');
        const vipDesc = document.getElementById('vipStatusDesc');
        if (vipTitle) vipTitle.textContent = vip ? 'VIP Member Active' : 'Free Account';
        if (vipDays) {
          vipDays.textContent = vip ? (days + ' ថ្ងៃសល់') : '0 ថ្ងៃសល់';
          vipDays.className = vip
            ? 'rounded-full border border-amber-400/50 bg-amber-400/20 px-3 py-1 text-xs font-extrabold text-amber-300'
            : 'rounded-full border border-slate-700 bg-slate-800 px-3 py-1 text-xs font-bold text-slate-300';
        }
        if (vipDesc) {
          vipDesc.textContent = vip
            ? ('បានបើក Server VIP ទាំងអស់ • កំណត់ ' + appState.deviceLimit + ' ឧបករណ៍។')
            : 'កំពុងប្រើប្រាស់ Server Free។ បើកប្រើកញ្ចប់ VIP ដើម្បីដោះសោ Server ល្បឿនលឿនទាំងអស់។';
        }

        const accId = document.getElementById('accDeviceId');
        const accBadge = document.getElementById('accBadge');
        const accLimit = document.getElementById('accLimit');
        const accExpiry = document.getElementById('accExpiry');
        if (accId) accId.textContent = appState.deviceId;
        if (accBadge) {
          accBadge.textContent = vip ? 'VIP' : 'FREE';
          accBadge.className = vip
            ? 'rounded-lg bg-amber-400 px-2.5 py-1 text-xs font-extrabold text-slate-950'
            : 'rounded-lg bg-slate-800 px-2.5 py-1 text-xs font-bold text-slate-300';
        }
        if (accLimit) accLimit.textContent = appState.deviceLimit + (appState.deviceLimit > 1 ? ' Devices' : ' Device');
        if (accExpiry) {
          accExpiry.textContent = vip ? new Date(appState.vipExpiresAt).toLocaleDateString('km-KH') : 'មិនមាន';
        }
      }

      function renderHome() {
        const srv = currentServer();
        const dot = document.getElementById('statusIndicator');
        const statusTxt = document.getElementById('statusText');
        const pulse = document.getElementById('powerPulse');
        const powerBtn = document.getElementById('btnPower');
        const powerSvg = document.getElementById('powerSvg');
        const powerLbl = document.getElementById('powerLabel');
        const proto = document.getElementById('homeProtocol');
        const ip = document.getElementById('homeIp');
        const flag = document.getElementById('homeFlag');
        const srvName = document.getElementById('homeServerName');
        const srvBadge = document.getElementById('homeServerBadge');
        const srvMeta = document.getElementById('homeServerMeta');

        if (appState.connected) {
          if (dot) dot.className = 'h-2.5 w-2.5 rounded-full bg-emerald-400';
          if (statusTxt) {
            statusTxt.textContent = 'បានភ្ជាប់សុវត្ថិភាព (Connected)';
            statusTxt.className = 'font-bold text-emerald-300';
          }
          if (pulse) pulse.classList.remove('hidden');
          if (powerBtn) powerBtn.className = 'relative z-10 flex h-36 w-36 flex-col items-center justify-center rounded-full border-[3px] border-emerald-400/90 bg-gradient-to-b from-emerald-950/70 to-obsidian text-white shadow-2xl transition duration-200 active:scale-95';
          if (powerSvg) powerSvg.className = 'mb-1.5 h-9 w-9 text-emerald-400';
          if (powerLbl) powerLbl.textContent = 'ផ្តាច់';
          if (ip && srv) ip.innerHTML = srv.ip + '<br/>(Secured)';
        } else {
          if (dot) dot.className = 'h-2.5 w-2.5 rounded-full bg-slate-500';
          if (statusTxt) {
            statusTxt.textContent = 'មិនទាន់ភ្ជាប់ (Disconnected)';
            statusTxt.className = 'font-bold text-slate-300';
          }
          if (pulse) pulse.classList.add('hidden');
          if (powerBtn) powerBtn.className = 'relative z-10 flex h-36 w-36 flex-col items-center justify-center rounded-full border-[3px] border-slate-700 bg-gradient-to-b from-slate-800/80 to-obsidian text-white shadow-2xl transition duration-200 hover:border-sky-400/70 active:scale-95';
          if (powerSvg) powerSvg.className = 'mb-1.5 h-9 w-9 text-sky-400';
          if (powerLbl) powerLbl.textContent = 'ភ្ជាប់';
          if (ip) ip.innerHTML = '10.8.0.2<br/>(Idle)';
        }

        if (proto && srv) proto.textContent = srv.protocol;
        if (flag && srv) flag.textContent = srv.flag;
        if (srvName && srv) srvName.textContent = srv.name;
        if (srvBadge && srv) {
          if (srv.tier === 'vip') {
            srvBadge.textContent = 'VIP';
            srvBadge.className = 'rounded border border-amber-400/40 bg-amber-400/20 px-1.5 py-0.5 text-[10px] font-extrabold text-amber-300';
          } else {
            srvBadge.textContent = 'FREE';
            srvBadge.className = 'rounded border border-slate-700 bg-slate-800 px-1.5 py-0.5 text-[10px] font-bold text-slate-300';
          }
        }
        if (srvMeta && srv) srvMeta.textContent = 'Latency: ' + srv.ping + ' ms • Load: ' + srv.load + '%';

        renderMetrics();
      }

      function renderServers() {
        const listEl = document.getElementById('serverCards');
        if (!listEl) return;

        const vip = hasActiveVip();
        const filtered = appState.servers.filter((s) => (appState.filter === 'all' ? true : s.tier === appState.filter));

        document.querySelectorAll('.filter-pill').forEach((btn) => {
          const f = btn.getAttribute('data-filter');
          const active = f === appState.filter;
          if (f === 'vip') {
            btn.className = active
              ? 'filter-pill rounded-xl bg-amber-400 px-4 py-1.5 text-xs font-extrabold text-slate-950'
              : 'filter-pill rounded-xl border border-amber-500/35 bg-amber-950/35 px-4 py-1.5 text-xs font-bold text-amber-300';
          } else {
            btn.className = active
              ? 'filter-pill rounded-xl bg-sky-400 px-4 py-1.5 text-xs font-extrabold text-slate-950'
              : 'filter-pill rounded-xl border border-slate-700/80 bg-card px-4 py-1.5 text-xs font-bold text-slate-300';
          }
        });

        listEl.innerHTML = '';

        filtered.forEach((srv) => {
          const selected = srv.id === appState.selectedServerId;
          const locked = srv.tier === 'vip' && !vip;
          const pingClass = srv.ping < 35 ? 'text-emerald-400' : srv.ping < 85 ? 'text-amber-300' : 'text-rose-400';

          const card = document.createElement('button');
          card.type = 'button';
          card.className = [
            'flex w-full items-center justify-between gap-2 rounded-2xl border p-3.5 text-left transition active:scale-[0.99]',
            selected
              ? 'border-sky-400/80 bg-sky-950/25'
              : 'border-slate-800/80 bg-card/90 hover:border-slate-700',
          ].join(' ');

          card.innerHTML =
            '<div class="flex items-center gap-3 min-w-0">' +
              '<span class="text-2xl leading-none">' + srv.flag + '</span>' +
              '<div class="min-w-0">' +
                '<div class="flex items-center gap-1.5">' +
                  '<span class="truncate text-sm font-extrabold text-white">' + srv.name + '</span>' +
                  (srv.tier === 'vip'
                    ? '<span class="rounded border border-amber-500/40 bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-extrabold text-amber-300">VIP</span>'
                    : '<span class="rounded border border-slate-700 bg-slate-800 px-1.5 py-0.5 text-[10px] font-bold text-slate-300">FREE</span>') +
                '</div>' +
                '<p class="mt-0.5 truncate text-xs text-slate-400">' + srv.protocol + ' • Load ' + srv.load + '%</p>' +
              '</div>' +
            '</div>' +
            '<div class="flex items-center gap-2.5 shrink-0">' +
              '<span class="font-mono text-xs font-bold ' + pingClass + '">' + srv.ping + ' ms</span>' +
              (locked
                ? '<span class="rounded-lg border border-amber-500/35 bg-amber-950/50 px-2.5 py-1 text-[11px] font-bold text-amber-300">🔒 VIP</span>'
                : selected
                ? '<span class="flex h-7 w-7 items-center justify-center rounded-lg bg-sky-400 text-xs font-extrabold text-slate-950">✓</span>'
                : '<span class="px-1 text-sm text-slate-500">›</span>') +
            '</div>';

          card.addEventListener('click', () => {
            if (locked) {
              showBanner('សូមបើកប្រើកញ្ចប់ VIP ជាមុនដើម្បីប្រើ Server នេះ។', 'warn');
              setTab('vip');
              return;
            }
            appState.selectedServerId = srv.id;
            persistState();
            renderAll();
            setTab('home');
            showBanner('បានជ្រើសរើស ' + srv.name, 'info');
          });

          listEl.appendChild(card);
        });
      }

      function renderAll() {
        renderHeaderAndVip();
        renderHome();
        renderServers();
      }

      function bindUI() {
        document.querySelectorAll('.tab-btn').forEach((btn) => {
          btn.addEventListener('click', () => {
            const t = btn.getAttribute('data-nav');
            if (t) setTab(t);
          });
        });

        document.querySelectorAll('[data-go-tab]').forEach((btn) => {
          btn.addEventListener('click', () => {
            const t = btn.getAttribute('data-go-tab');
            if (t) setTab(t);
          });
        });

        const powerBtn = document.getElementById('btnPower');
        if (powerBtn) {
          powerBtn.addEventListener('click', () => {
            const bridge = getNativeVpnBridge();
            const srv = currentServer();
            const nextConnected = !appState.connected;

            if (nextConnected && srv && srv.tier === 'vip' && !hasActiveVip()) {
              showBanner('សូមបើកប្រើកញ្ចប់ VIP ជាមុនដើម្បីប្រើ Server នេះ។', 'warn');
              setTab('vip');
              return;
            }

            if (bridge) {
              try {
                if (nextConnected) {
                  bridge.startVpn(srv ? srv.name : 'AegisVIP Node');
                } else {
                  bridge.stopVpn();
                }
                return;
              } catch (_err) {}
            }

            applyNativeState(nextConnected, true);
          });
        }

        const syncBtn = document.getElementById('btnSync');
        if (syncBtn) {
          syncBtn.addEventListener('click', () => {
            guardVipServerSelection();
            renderAll();
            showBanner('បាន Sync ស្ថានភាព Server & VIP រួចរាល់។', 'success');
          });
        }

        const testVipBtn = document.getElementById('btnTestVip');
        if (testVipBtn) {
          testVipBtn.addEventListener('click', () => {
            if (hasActiveVip()) {
              revokeVip();
            } else {
              grantVip(30, 'VIP តេស្ត (30 ថ្ងៃ)');
            }
          });
        }

        document.querySelectorAll('.filter-pill').forEach((btn) => {
          btn.addEventListener('click', () => {
            appState.filter = btn.getAttribute('data-filter') || 'all';
            persistState();
            renderServers();
          });
        });

        const pingBtn = document.getElementById('btnPing');
        if (pingBtn) {
          pingBtn.addEventListener('click', () => {
            appState.servers.forEach((s) => {
              const delta = Math.floor(Math.random() * 9) - 4;
              s.ping = Math.max(14, s.ping + delta);
            });
            persistState();
            renderAll();
            showBanner('បានវាស់ Ping Server ឡើងវិញ។', 'info');
          });
        }

        document.querySelectorAll('.plan-buy-btn').forEach((btn) => {
          btn.addEventListener('click', () => {
            const days = Number(btn.getAttribute('data-plan-days') || '30');
            const label = btn.getAttribute('data-plan-name') || ('VIP (' + days + ' ថ្ងៃ)');
            grantVip(days, label);
          });
        });

        const voucherForm = document.getElementById('voucherForm');
        if (voucherForm) {
          voucherForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const input = document.getElementById('voucherInput');
            const code = (input ? input.value : '').trim().toUpperCase();
            if (code === 'VIP30') {
              grantVip(30, 'កូដ VIP30 (30 ថ្ងៃ)');
              if (input) input.value = '';
            } else if (code === 'VIP7') {
              grantVip(7, 'កូដ VIP7 (7 ថ្ងៃ)');
              if (input) input.value = '';
            } else {
              showBanner('កូដមិនត្រឹមត្រូវ។ សាកល្បង VIP30 ឬ VIP7។', 'warn');
            }
          });
        }

        const expireBtn = document.getElementById('btnExpireVip');
        if (expireBtn) expireBtn.addEventListener('click', revokeVip);

        const resetBtn = document.getElementById('btnResetAll');
        if (resetBtn) {
          resetBtn.addEventListener('click', () => {
            stopLoop();
            appState = defaultState();
            persistState();
            renderAll();
            startLoop();
            showBanner('បានកំណត់ឡើងវិញ (Reset)។', 'info');
          });
        }

        const bannerClose = document.getElementById('bannerClose');
        if (bannerClose) {
          bannerClose.addEventListener('click', () => {
            const box = document.getElementById('bannerBox');
            if (box) box.classList.add('hidden');
          });
        }
      }

      window.addEventListener('DOMContentLoaded', () => {
        bindUI();
        guardVipServerSelection();
        renderAll();
        if (appState.connected) {
          startLoop();
        }
      });
    })();
  </script>
</body>
</html>
            """.trimIndent()

            webView.loadDataWithBaseURL("https://aegisvip.local/", embeddedHtml, "text/html", "UTF-8", null)
        } catch (_: Exception) {
            // Fallback screen if assets/index.html is missing in a test build
            val fallbackHtml = """
                <!DOCTYPE html>
                <html lang="km">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>AegisVIP VPN</title>
                </head>
                <body style="background:#060a11;color:#f1f5f9;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;gap:14px;">
                  <h2 style="color:#38bdf8;margin:0;">AegisVIP VPN Service Ready</h2>
                  <button onclick="AndroidVpn.startVpn('Singapore Free #01')" style="padding:12px 22px;border-radius:12px;border:0;background:#38bdf8;color:#060a11;font-weight:700;">ភ្ជាប់ VPN</button>
                  <button onclick="AndroidVpn.stopVpn()" style="padding:12px 22px;border-radius:12px;border:0;background:#1e293b;color:#f8fafc;font-weight:700;">ផ្តាច់ VPN</button>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, fallbackHtml, "text/html", "UTF-8", null)
        }
    }

    /**
     * Checks VPN permission via VpnService.prepare() before starting AegisVpnService.
     */
    private fun requestOrStartVpn(serverName: String) {
        pendingServerName = serverName.ifBlank { "Singapore Free #01" }
        try {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                launchVpnService(pendingServerName)
                notifyWebState(connected = true, permissionGranted = true)
            }
        } catch (_: Exception) {
            notifyWebState(connected = false, permissionGranted = false)
        }
    }

    /**
     * Starts AegisVpnService with the selected server name.
     */
    private fun launchVpnService(serverName: String) {
        try {
            val intent = Intent(this, AegisVpnService::class.java).apply {
                action = AegisVpnService.ACTION_START
                putExtra(AegisVpnService.EXTRA_SERVER_NAME, serverName)
            }
            startService(intent)
        } catch (_: Exception) {
            notifyWebState(connected = false, permissionGranted = true)
        }
    }

    /**
     * Stops AegisVpnService and notifies the WebView UI.
     */
    private fun stopVpnService() {
        try {
            val intent = Intent(this, AegisVpnService::class.java).apply {
                action = AegisVpnService.ACTION_STOP
            }
            startService(intent)
        } catch (_: Exception) {
            // Ignore service stop errors during shutdown
        } finally {
            notifyWebState(connected = false, permissionGranted = true)
        }
    }

    /**
     * Pushes native VPN connection and permission status back to JavaScript.
     */
    private fun notifyWebState(connected: Boolean, permissionGranted: Boolean) {
        runOnUiThread {
            try {
                val js = "window.onNativeVpnState && window.onNativeVpnState($connected, $permissionGranted);"
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
                // Ignore evaluateJavascript errors if WebView is finishing
            }
        }
    }

    /**
     * JavaScript interface exposed to assets/index.html as `window.AndroidVpn`.
     */
    inner class VpnBridge {
        @JavascriptInterface
        fun startVpn(serverName: String = "Singapore Free #01") {
            runOnUiThread {
                requestOrStartVpn(serverName)
            }
        }

        @JavascriptInterface
        fun stopVpn() {
            runOnUiThread {
                stopVpnService()
            }
        }
    }
}
