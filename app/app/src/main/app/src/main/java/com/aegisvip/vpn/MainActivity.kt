package com.example.aegisvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * ម៉ូដែលទិន្នន័យសម្រាប់ម៉ាស៊ីនមេ VIP នីមួយៗ
 */
data class VipServerNode(
    val id: String,
    val name: String,
    val subtitle: String,
    val protocol: String, // Xray V2Ray, OpenVPN SSH, SSH WS, Shadowsocks
    val flagEmoji: String,
    var ping: Int,
    val host: String
)

class MainActivity : AppCompatActivity() {

    // ស្ថានភាពកម្មវិធី
    private var isVpnConnected = false
    private var isVipUnlocked = false
    private var currentFilter = "ទាំងអស់"
    private var secondsElapsed = 0

    // បញ្ជីម៉ាស៊ីនមេ VIP (អាចបន្ថែម ឬលុបបានតាមរយៈផ្ទាំង Admin)
    private val servers = mutableListOf(
        VipServerNode("kh", "កម្ពុជា • ភ្នំពេញ VIP", "Xray V2Ray (Vless WS + TLS) • Ping ទាបបំផុត", "Xray V2Ray", "🇰🇭", 12, "kh-vless.aegisvpn.net"),
        VipServerNode("sg", "សិង្ហបុរី • Direct VIP", "Xray V2Ray (Vmess WS + AEAD) • លេងហ្គេម & 4K", "Xray V2Ray", "🇸🇬", 24, "sg-vmess.aegisvpn.net"),
        VipServerNode("vn", "វៀតណាម • ហូជីមិញ VIP", "OpenVPN SSH Tunnel • ល្បឿនលឿន", "OpenVPN SSH", "🇻🇳", 22, "vn-ssh.aegisvpn.net"),
        VipServerNode("hk", "ហុងកុង • CN2 VIP", "Xray V2Ray CDN • ផ្លូវតភ្ជាប់រហ័ស", "Xray V2Ray", "🇭🇰", 36, "hk-vless.aegisvpn.net"),
        VipServerNode("jp", "ជប៉ុន • តូក្យូ VIP", "Xray V2Ray + TLS • មើលវីដេអូ 4K", "Xray V2Ray", "🇯🇵", 48, "jp-vmess.aegisvpn.net"),
        VipServerNode("sg-ws", "សិង្ហបុរី • SSH WS VIP", "SSH WebSocket + CDN • តភ្ជាប់រហ័ស", "SSH WS", "🇸🇬", 26, "sg-sshws.aegisvpn.net"),
        VipServerNode("sg-ss", "សិង្ហបុរី • Shadowsocks AEAD", "Shadowsocks 2022 • ល្បឿនលឿន & ស្រាល", "Shadowsocks", "🇸🇬", 29, "sg-ss.aegisvpn.net")
    )

    private var selectedServer: VipServerNode = servers[2] // លំនាំដើម៖ វៀតណាម VIP

    // ធាតុ UI សំខាន់ៗ
    private lateinit var rootContainer: FrameLayout
    private lateinit var homeView: LinearLayout
    private lateinit var serversView: LinearLayout
    private lateinit var settingsView: LinearLayout

    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var powerBtnCircle: LinearLayout
    private lateinit var powerBtnIcon: TextView
    private lateinit var powerBtnLabel: TextView
    private lateinit var timerText: TextView
    private lateinit var ipText: TextView
    private lateinit var homeServerFlag: TextView
    private lateinit var homeServerName: TextView
    private lateinit var homeServerDesc: TextView
    private lateinit var homeServerPing: TextView
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var serverListContainer: LinearLayout

    // នាឡិការាប់ម៉ោងតភ្ជាប់
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isVpnConnected) {
                secondsElapsed++
                val h = secondsElapsed / 3600
                val m = (secondsElapsed % 3600) / 60
                val s = secondsElapsed % 60
                timerText.text = String.format("%02d:%02d:%02d", h, m, s)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    // ទទួលស្ថានភាព និងល្បឿនពី AegisVpnService
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AegisVpnService.BROADCAST_VPN_STATE) {
                val connected = intent.getBooleanExtra("CONNECTED", false)
                val upSpeed = intent.getStringExtra("UP_SPEED") ?: "0.0 Mbps"
                val downSpeed = intent.getStringExtra("DOWN_SPEED") ?: "0.0 Mbps"
                updateConnectionState(connected, upSpeed, downSpeed)
            }
        }
    }

    // ស្នើសុំសិទ្ធិ VPN ពីប្រព័ន្ធ Android
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            showToast("សូមអនុញ្ញាតសិទ្ធិ VPN ដើម្បីភ្ជាប់!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#060608")
        window.navigationBarColor = Color.parseColor("#060608")
        buildAegisVipUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AegisVpnService.BROADCAST_VPN_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(vpnStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * បង្កើតផ្ទាំង UI ទាំងមូលតាមទម្រង់ AMOLED Dark Mode (#060608)
     */
    private fun buildAegisVipUI() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#060608"))
        }

        val mainShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Header Bar (AegisVPN VIP + 85.4 GB Badge)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(44, 36, 44, 20)
        }

        val logoBox = TextView(this).apply {
            text = "🛡️"
            textSize = 18f
            gravity = Gravity.CENTER
            background = createRoundedCard("#1F190D", "#F59E0B", 24f, 2)
            layoutParams = LinearLayout.LayoutParams(96, 96)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "AegisVPN VIP"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
        }

        val appSub = TextView(this).apply {
            text = "ប្រព័ន្ធការពារល្បឿនលឿន"
            textSize = 11f
            setTextColor(Color.parseColor("#A1A1AA"))
        }

        titleCol.addView(appTitle)
        titleCol.addView(appSub)

        val dataBadge = TextView(this).apply {
            text = "● 85.4 GB"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(28, 14, 28, 14)
            background = createRoundedCard("#121215", "#26262C", 40f, 2)
            setOnClickListener { showVipUnlockDialog() }
        }

        header.addView(logoBox)
        header.addView(titleCol)
        header.addView(dataBadge)

        // 2. តំបន់មាតិកាកណ្តាល (Home / Servers / Settings)
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(36, 8, 36, 8)
        }

        homeView = createHomeScreen()
        serversView = createServersScreen().apply { visibility = View.GONE }
        settingsView = createSettingsScreen().apply { visibility = View.GONE }

        contentFrame.addView(homeView)
        contentFrame.addView(serversView)
        contentFrame.addView(settingsView)

        // 3. ផ្នែកខាងក្រោម៖ ប៊ូតុងសកម្មភាពរហ័ស ៣ ពណ៌ + របារមីនុយ
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 12, 36, 32)
        }

        // ប៊ូតុង ៣ ពណ៌ (គណនី VIP | Bot ទិញ GB | របៀបទិញ)
        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }

        val btnVip = createPillButton("👑 គណនី VIP", "#F59E0B", "#000000") { showVipUnlockDialog() }
        val btnBot = createPillButton("✈️ Bot ទិញ GB", "#22B8CF", "#000000") {
            showInfoDialog("Telegram Bot ទិញ GB", "ទិញទិន្នន័យ GB ដោយស្វ័យប្រវត្តិ ២៤ ម៉ោងតាមរយៈ៖\n\n@AegisVPN_AutoBot")
        }
        val btnGuide = createPillButton("❓ របៀបទិញ", "#2563EB", "#FFFFFF") {
            showInfoDialog("របៀបទិញកញ្ចប់ VIP", "១. ចុចលើប៊ូតុង Bot ទិញ GB\n២. ជ្រើសរើសកញ្ចប់ (50GB / 100GB)\n៣. ស្កេនបង់ប្រាក់តាម KHQR ដើម្បីទទួលបាន CODE V ភ្លាមៗ។")
        }

        quickActions.addView(btnVip)
        quickActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        quickActions.addView(btnBot)
        quickActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        quickActions.addView(btnGuide)

        // របារមីនុយខាងក្រោម (ផ្ទះ | ម៉ាស៊ីនមេ | កំណត់ & Admin)
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 18, 16, 18)
            background = createRoundedCard("#121215", "#26262C", 48f, 2)
        }

        val navHome = createNavTab("🏠", "ផ្ទះ") { switchScreen("home") }
        val navServers = createNavTab("🌐", "ម៉ាស៊ីនមេ") { switchScreen("servers") }
        val navSettings = createNavTab("⚙️", "កំណត់ / Admin") { switchScreen("settings") }

        navBar.addView(navHome)
        navBar.addView(navServers)
        navBar.addView(navSettings)

        footer.addView(quickActions)
        footer.addView(navBar)

        mainShell.addView(header)
        mainShell.addView(contentFrame)
        mainShell.addView(footer)

        rootContainer.addView(mainShell)
        setContentView(rootContainer)
    }

    /**
     * ទំព័រទី ១៖ ផ្ទះ (Home Screen)
     */
    private fun createHomeScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // កាតគ្រប់គ្រងការតភ្ជាប់ធំ
            val mainCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 36, 40, 36)
                background = createRoundedCard("#121215", "#26262C", 56f, 2)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // ស្ថានភាពបណ្តាញខាងលើកាត
            val statusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val netLabel = TextView(this@MainActivity).apply {
                text = "ស្ថានភាពបណ្តាញ"
                textSize = 12f
                setTextColor(Color.parseColor("#A1A1AA"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val badgeWrap = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 10, 24, 10)
                background = createRoundedCard("#18181C", "#27272A", 36f, 2)
            }

            statusDot = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F43F5E"))
                }
                layoutParams = LinearLayout.LayoutParams(20, 20).apply { setMargins(0, 0, 14, 0) }
            }

            statusLabel = TextView(this@MainActivity).apply {
                text = "មិនទាន់បានភ្ជាប់"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#D4D4D8"))
            }

            badgeWrap.addView(statusDot)
            badgeWrap.addView(statusLabel)
            statusRow.addView(netLabel)
            statusRow.addView(badgeWrap)

            // ប៊ូតុងភ្ជាប់ VPN កណ្តាល + នាឡិកា
            val centerBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            powerBtnCircle = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = createCircleButton("#18181C", "#3F3F46", 10)
                layoutParams = LinearLayout.LayoutParams(360, 360)
                setOnClickListener { handleConnectClick() }
            }

            powerBtnIcon = TextView(this@MainActivity).apply {
                text = "⏻"
                textSize = 44f
                setTextColor(Color.parseColor("#A1A1AA"))
                gravity = Gravity.CENTER
            }

            powerBtnLabel = TextView(this@MainActivity).apply {
                text = "ចុចដើម្បីភ្ជាប់"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(0, 8, 0, 0)
            }

            powerBtnCircle.addView(powerBtnIcon)
            powerBtnCircle.addView(powerBtnLabel)

            timerText = TextView(this@MainActivity).apply {
                text = "00:00:00"
                textSize = 24f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#71717A"))
                setPadding(0, 28, 0, 6)
            }

            ipText = TextView(this@MainActivity).apply {
                text = "អាសយដ្ឋាន IP៖ 103.216.xxx.xxx (លាក់)"
                textSize = 11f
                setTextColor(Color.parseColor("#A1A1AA"))
            }

            centerBox.addView(powerBtnCircle)
            centerBox.addView(timerText)
            centerBox.addView(ipText)

            // កាត Server ដែលកំពុងជ្រើសរើសនៅខាងក្រោម
            val serverSelectorCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 28, 24)
                background = createRoundedCard("#18181C", "#26262C", 36f, 2)
                setOnClickListener { switchScreen("servers") }
            }

            homeServerFlag = TextView(this@MainActivity).apply {
                text = selectedServer.flagEmoji
                textSize = 26f
                setPadding(0, 0, 24, 0)
            }

            val srvTextCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            homeServerName = TextView(this@MainActivity).apply {
                text = selectedServer.name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            homeServerDesc = TextView(this@MainActivity).apply {
                text = selectedServer.subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#A1A1AA"))
            }

            srvTextCol.addView(homeServerName)
            srvTextCol.addView(homeServerDesc)

            homeServerPing = TextView(this@MainActivity).apply {
                text = "${selectedServer.ping} ms"
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
                setPadding(20, 10, 20, 10)
                background = createRoundedCard("#09221A", "#10B981", 30f, 1)
            }

            serverSelectorCard.addView(homeServerFlag)
            serverSelectorCard.addView(srvTextCol)
            serverSelectorCard.addView(homeServerPing)

            mainCard.addView(statusRow)
            mainCard.addView(centerBox)
            mainCard.addView(serverSelectorCard)

            // ស្ថិតិល្បឿនទាញយក / បង្ហោះខាងក្រោម
            val speedRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 20, 0, 0) }
            }

            val dlCard = createSpeedStatCard("⬇ ល្បឿនទាញយក", "0.0 Mbps", "#10B981") { downloadSpeedText = it }
            val ulCard = createSpeedStatCard("⬆ ល្បឿនបង្ហោះ", "0.0 Mbps", "#06B6D4") { uploadSpeedText = it }

            speedRow.addView(dlCard)
            speedRow.addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })
            speedRow.addView(ulCard)

            addView(mainCard)
            addView(speedRow)
        }
    }

    /**
     * ទំព័រទី ២៖ បញ្ជីម៉ាស៊ីនមេ VIP (Servers List Screen)
     */
    private fun createServersScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 32)
            background = createRoundedCard("#121215", "#26262C", 56f, 2)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Header + ប៊ូតុងតេស្ត Ping
            val topHeader = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 24)
            }

            val titleTv = TextView(this@MainActivity).apply {
                text = "បញ្ជីម៉ាស៊ីនមេ VIP"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pingAllBtn = TextView(this@MainActivity).apply {
                text = "📊 តេស្ត Ping"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
                setPadding(24, 12, 24, 12)
                background = createRoundedCard("#18181C", "#3F3F46", 32f, 2)
                setOnClickListener {
                    servers.forEach { it.ping = (10..45).random() }
                    refreshServerListUI()
                    homeServerPing.text = "${selectedServer.ping} ms"
                    showToast("បានធ្វើបច្ចុប្បន្នភាព Ping រួចរាល់!")
                }
            }

            topHeader.addView(titleTv)
            topHeader.addView(pingAllBtn)

            // របារច្រោះប្រភេទ Protocol
            val filterScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, 24)
            }
            val filterRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val categories = listOf("ទាំងអស់", "Xray V2Ray", "OpenVPN SSH", "SSH WS", "Shadowsocks")
            for (cat in categories) {
                val chip = TextView(this@MainActivity).apply {
                    text = cat
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(28, 14, 28, 14)
                    background = createRoundedCard("#18181C", "#27272A", 36f, 2)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 14, 0) }
                    setOnClickListener {
                        currentFilter = cat
                        refreshServerListUI()
                    }
                }
                filterRow.addView(chip)
            }
            filterScroll.addView(filterRow)

            // បញ្ជី Server
            val scrollView = ScrollView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            serverListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scrollView.addView(serverListContainer)

            addView(topHeader)
            addView(filterScroll)
            addView(scrollView)

            refreshServerListUI()
        }
    }

    /**
     * បង្ហាញបញ្ជីម៉ាស៊ីនមេឡើងវិញពេលមានការផ្លាស់ប្តូរ ឬដោះសោ CODE V
     */
    private fun refreshServerListUI() {
        serverListContainer.removeAllViews()
        val filtered = if (currentFilter == "ទាំងអស់") servers else servers.filter { it.protocol == currentFilter }

        for (srv in filtered) {
            val isSelected = srv.id == selectedServer.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 28, 24)
                background = createRoundedCard(
                    if (isSelected) "#1C1C21" else "#18181C",
                    if (isSelected) "#F59E0B" else "#26262C",
                    40f,
                    2
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 18) }

                setOnClickListener {
                    if (!isVipUnlocked) {
                        showToast("សូមបញ្ចូល CODE V ដើម្បីដោះសោម៉ាស៊ីនមេ VIP!")
                        showVipUnlockDialog()
                        return@setOnClickListener
                    }
                    selectedServer = srv
                    homeServerFlag.text = srv.flagEmoji
                    homeServerName.text = srv.name
                    homeServerDesc.text = srv.subtitle
                    homeServerPing.text = "${srv.ping} ms"
                    refreshServerListUI()
                    switchScreen("home")
                    showToast("បានជ្រើសរើស ${srv.name}")

                    if (isVpnConnected) {
                        stopVpnService()
                        Handler(Looper.getMainLooper()).postDelayed({ startVpnService() }, 400)
                    }
                }
            }

            val flagTv = TextView(this).apply {
                text = srv.flagEmoji
                textSize = 26f
                setPadding(0, 0, 24, 0)
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = "${srv.name} [${srv.protocol}]"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val subTv = TextView(this).apply {
                text = srv.subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#A1A1AA"))
            }

            infoCol.addView(nameTv)
            infoCol.addView(subTv)

            val lockAndPing = TextView(this).apply {
                val lockMark = if (!isVipUnlocked) "🔒 " else ""
                text = "$lockMark${srv.ping} ms"
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
                setPadding(18, 10, 18, 10)
                background = createRoundedCard("#09221A", "#10B981", 28f, 1)
            }

            card.addView(flagTv)
            card.addView(infoCol)
            card.addView(lockAndPing)
            serverListContainer.addView(card)
        }
    }

    /**
     * ទំព័រទី ៣៖ កំណត់ប្រព័ន្ធ និង Admin Panel
     */
    private fun createSettingsScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            background = createRoundedCard("#121215", "#26262C", 56f, 2)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val title = TextView(this@MainActivity).apply {
                text = "កំណត់ប្រព័ន្ធ & Admin"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 24)
            }

            val adminCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 28, 32, 28)
                background = createRoundedCard("#1F190D", "#F59E0B", 36f, 2)
                setOnClickListener { showAdminAddServerDialog() }
            }

            val adminTitle = TextView(this@MainActivity).apply {
                text = "🛡️ គ្រប់គ្រងម៉ាស៊ីនមេ (ADMIN)"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F59E0B"))
            }

            val adminSub = TextView(this@MainActivity).apply {
                text = "ចុចទីនេះដើម្បីបន្ថែម Config (V2Ray, Hysteria2, SSH, OpenVPN)"
                textSize = 11f
                setTextColor(Color.parseColor("#E4E4E7"))
                setPadding(0, 8, 0, 0)
            }

            adminCard.addView(adminTitle)
            adminCard.addView(adminSub)

            val accInfo = TextView(this@MainActivity).apply {
                text = "លេខសម្គាល់គណនី៖ VIP-8849201"
                textSize = 12f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(0, 36, 0, 0)
            }

            addView(title)
            addView(adminCard)
            addView(accInfo)
        }
    }

    /**
     * ផ្ទាំងដោះសោ VIP ដោយបញ្ចូល CODE V
     */
    private fun showVipUnlockDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val info = TextView(this).apply {
            text = if (isVipUnlocked) "✅ គណនី VIP របស់អ្នកត្រូវបានដោះសោរួចរាល់!" else "សូមបញ្ចូល CODE V (ឧ. V-VIP888) ដើម្បីដោះសោម៉ាស៊ីនមេ VIP ទាំងអស់៖"
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }

        val input = EditText(this).apply {
            hint = "V-VIP888"
            setText("V-VIP888")
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        layout.addView(info)
        if (!isVipUnlocked) layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("👑 កញ្ចប់ម៉ាស៊ីនមេ VIP")
            .setView(layout)
            .setPositiveButton(if (isVipUnlocked) "ចាក់សោវិញ" else "ដោះសោ") { _, _ ->
                if (isVipUnlocked) {
                    isVipUnlocked = false
                    if (isVpnConnected) stopVpnService()
                    refreshServerListUI()
                    showToast("បានចាក់សោកញ្ចប់ VIP ឡើងវិញ")
                } else {
                    val code = input.text.toString().trim().uppercase()
                    if (code.startsWith("V") && code.length >= 4) {
                        isVipUnlocked = true
                        refreshServerListUI()
                        showToast("ដោះសោកញ្ចប់ VIP ជោគជ័យ!")
                    } else {
                        showToast("CODE V មិនត្រឹមត្រូវទេ! (ត្រូវផ្តើមដោយអក្សរ V)")
                    }
                }
            }
            .setNegativeButton("បិទ", null)
            .show()
    }

    /**
     * ផ្ទាំង Admin សម្រាប់បន្ថែមម៉ាស៊ីនមេថ្មី
     */
    private fun showAdminAddServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val nameInput = EditText(this).apply { hint = "ឈ្មោះម៉ាស៊ីនមេ (ឧ. KH VIP Fast)" }
        val hostInput = EditText(this).apply { hint = "Server Host (ឧ. kh-vip.aegisvpn.net)" }
        val protoSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Xray V2Ray", "OpenVPN SSH", "SSH WS", "Shadowsocks")
            )
        }

        layout.addView(nameInput)
        layout.addView(hostInput)
        layout.addView(protoSpinner)

        AlertDialog.Builder(this)
            .setTitle("🛡️ Admin • បន្ថែម Config ថ្មី")
            .setView(layout)
            .setPositiveButton("បន្ថែម") { _, _ ->
                val srvName = nameInput.text.toString().trim().ifEmpty { "កម្ពុជា • Custom VIP" }
                val srvHost = hostInput.text.toString().trim().ifEmpty { "custom.aegisvpn.net" }
                val proto = protoSpinner.selectedItem.toString()

                servers.add(
                    0,
                    VipServerNode(
                        id = "custom_${System.currentTimeMillis()}",
                        name = srvName,
                        subtitle = "$proto • Manual Admin Config",
                        protocol = proto,
                        flagEmoji = "🇰🇭",
                        ping = 18,
                        host = srvHost
                    )
                )
                refreshServerListUI()
                showToast("បានបញ្ចូល $srvName រួចរាល់!")
            }
            .setNegativeButton("បោះបង់", null)
            .show()
    }

    /**
     * គ្រប់គ្រងការចុចប៊ូតុងភ្ជាប់ / ផ្តាច់ VPN
     */
    private fun handleConnectClick() {
        if (!isVipUnlocked) {
            showToast("កញ្ចប់ VIP កំពុងចាក់សោ! សូមបញ្ចូល CODE V")
            showVipUnlockDialog()
            return
        }

        if (isVpnConnected) {
            stopVpnService()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_CONNECT
            putExtra("SERVER_HOST", selectedServer.host)
            putExtra("SERVER_NAME", selectedServer.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    /**
     * ធ្វើបច្ចុប្បន្នភាពផ្ទាំង UI ពេលភ្ជាប់ ឬផ្តាច់ VPN
     */
    private fun updateConnectionState(connected: Boolean, upSpeed: String, downSpeed: String) {
        isVpnConnected = connected
        uploadSpeedText.text = upSpeed
        downloadSpeedText.text = downSpeed

        if (connected) {
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            statusLabel.text = "បានភ្ជាប់ (VIP សុវត្ថិភាព)"
            powerBtnCircle.background = createCircleButton("#08291E", "#10B981", 10)
            powerBtnIcon.setTextColor(Color.parseColor("#10B981"))
            powerBtnLabel.text = "បានភ្ជាប់"
            powerBtnLabel.setTextColor(Color.parseColor("#10B981"))
            timerText.setTextColor(Color.WHITE)
            ipText.text = "អាសយដ្ឋាន IP៖ 172.67.188.42 (បានការពារ)"

            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.postDelayed(timerRunnable, 1000)
        } else {
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F43F5E"))
            }
            statusLabel.text = "មិនទាន់បានភ្ជាប់"
            powerBtnCircle.background = createCircleButton("#18181C", "#3F3F46", 10)
            powerBtnIcon.setTextColor(Color.parseColor("#A1A1AA"))
            powerBtnLabel.text = "ចុចដើម្បីភ្ជាប់"
            powerBtnLabel.setTextColor(Color.parseColor("#A1A1AA"))

            timerHandler.removeCallbacks(timerRunnable)
            secondsElapsed = 0
            timerText.text = "00:00:00"
            timerText.setTextColor(Color.parseColor("#71717A"))
            ipText.text = "អាសយដ្ឋាន IP៖ 103.216.xxx.xxx (លាក់)"
            downloadSpeedText.text = "0.0 Mbps"
            uploadSpeedText.text = "0.0 Mbps"
        }
    }

    private fun switchScreen(screen: String) {
        homeView.visibility = if (screen == "home") View.VISIBLE else View.GONE
        serversView.visibility = if (screen == "servers") View.VISIBLE else View.GONE
        settingsView.visibility = if (screen == "settings") View.VISIBLE else View.GONE
    }

    // មុខងារជំនួយសម្រាប់បង្កើតធាតុ UI ស្អាតៗ
    private fun createRoundedCard(bgHex: String, borderHex: String, radius: Float, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = radius
            setStroke(strokeWidth, Color.parseColor(borderHex))
        }
    }

    private fun createCircleButton(bgHex: String, borderHex: String, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bgHex))
            setStroke(strokeWidth, Color.parseColor(borderHex))
        }
    }

    private fun createPillButton(label: String, bgHex: String, textHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textHex))
            gravity = Gravity.CENTER
            setPadding(16, 22, 16, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 50f
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun createNavTab(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }

            val iconTv = TextView(this@MainActivity).apply {
                text = icon
                textSize = 16f
                gravity = Gravity.CENTER
            }
            val labelTv = TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E4E4E7"))
                gravity = Gravity.CENTER
            }
            addView(iconTv)
            addView(labelTv)
        }
    }

    private fun createSpeedStatCard(title: String, initVal: String, accentHex: String, bind: (TextView) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = createRoundedCard("#121215", "#26262C", 36f, 2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val titleTv = TextView(this@MainActivity).apply {
                text = title
                textSize = 11f
                setTextColor(Color.parseColor(accentHex))
            }
            val valTv = TextView(this@MainActivity).apply {
                text = initVal
                textSize = 15f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 6, 0, 0)
            }
            bind(valTv)
            addView(titleTv)
            addView(valTv)
        }
    }

    private fun showInfoDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("យល់ព្រម", null)
            .show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
