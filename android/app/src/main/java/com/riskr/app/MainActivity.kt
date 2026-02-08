package com.riskr.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bitcoinj.core.Base58
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ==========================================
// 0. 配置区
// ==========================================
const val BACKEND_URL = "https://riskr-backend.onrender.com" // 请确认这是你的真实地址
const val TREASURY_ADDR = "XdHn3hTvMpniKmFD1UqgcHJggMu6bp8kfnKkAMorFEB"
// 🟢 Solana 标准焚化炉地址 (转进去就没了)
const val INCINERATOR_ADDR = "1nc1nerator11111111111111111111111111111111"

// ==========================================
// 1. UI 主题配置 (赛博朋克风格)
// ==========================================
val NeonCyan = Color(0xFF00E5FF)   // 青色光晕 (主色)
val NeonPurple = Color(0xFFD500F9) // 紫色光晕 (强调色)
val DarkBg = Color(0xFF050505)     // 纯黑背景
val TechPanelBg = Color(0xFF121212) // 面板背景色
val NeonGreen = Color(0xFF00FF66)  // 胜利/成功
val NeonRed = Color(0xFFFF3333)    // 失败/警告

@Composable
fun RiskrTechTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            secondary = NeonPurple,
            background = DarkBg,
            surface = TechPanelBg,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content
    )
}

// 科技感背景网格
@Composable
fun TechBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .drawBehind {
                val gridColor = NeonCyan.copy(alpha = 0.08f)
                val gridSize = 40.dp.toPx()
                for (y in 0..size.height.toInt() step gridSize.toInt()) {
                    drawLine(gridColor, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1f)
                }
                for (x in 0..size.width.toInt() step gridSize.toInt()) {
                    drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1f)
                }
            }
    )
}

// 发光科技卡片
@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    color: Color = NeonCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderBrush = Brush.horizontalGradient(
        colors = listOf(color.copy(alpha = 0.5f), color, color.copy(alpha = 0.5f)),
        tileMode = TileMode.Mirror
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = TechPanelBg.copy(alpha = 0.9f)),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
        border = BorderStroke(1.dp, borderBrush),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

// 科技按钮
@Composable
fun CyberButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    isOutline: Boolean = false,
    color: Color = NeonCyan
) {
    if (isOutline) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = CutCornerShape(6.dp),
            border = BorderStroke(1.dp, color),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
        ) {
            Text(text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = CutCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
        ) {
            Text(text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

// ==========================================
// 2. 数据模型 & 工具类
// ==========================================
data class GameSession(
    val id: String,
    val hostAddress: String,
    val betAmount: Int,
    val status: String,
    val winner: String? = null
)

data class GameResult(
    val isWin: Boolean,
    val isDraw: Boolean,
    val opponentMove: String,
    val profit: Int
)

object SolanaService {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val RPC_URL = "https://api.mainnet-beta.solana.com"
    const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"

    suspend fun getSkrBalance(accountAddress: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val targetAta = findSkrAccount(accountAddress) ?: return@withContext "0"
                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", "getTokenAccountBalance")
                    put("params", JSONArray().apply { put(targetAta); put(JSONObject().put("commitment", "confirmed")) })
                }.toString()
                val req = Request.Builder().url(RPC_URL).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
                val respStr = client.newCall(req).execute().body?.string() ?: return@withContext "0"
                val raw = JSONObject(respStr).optJSONObject("result")?.optJSONObject("value")?.optString("uiAmountString") ?: "0"
                return@withContext raw.toDoubleOrNull()?.toLong()?.toString() ?: "0"
            } catch (e: Exception) { return@withContext "0" }
        }
    }

    suspend fun findSkrAccount(owner: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", "getTokenAccountsByOwner")
                    put("params", JSONArray().apply {
                        put(owner); put(JSONObject().put("mint", SKR_MINT))
                        put(JSONObject().apply { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
                    })
                }.toString()
                val req = Request.Builder().url(RPC_URL).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
                val respStr = client.newCall(req).execute().body?.string() ?: return@withContext null
                val accounts = JSONObject(respStr).optJSONObject("result")?.optJSONArray("value")
                if (accounts != null && accounts.length() > 0) return@withContext accounts.getJSONObject(0).getString("pubkey")
            } catch (e: Exception) { e.printStackTrace() }
            return@withContext null
        }
    }

    suspend fun getRecentBlockhash(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val payload = """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed"}]}"""
                val req = Request.Builder().url(RPC_URL).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
                val respStr = client.newCall(req).execute().body?.string() ?: "{}"
                JSONObject(respStr).optJSONObject("result")?.optJSONObject("value")?.getString("blockhash")
            } catch (e: Exception) { null }
        }
    }
}

object TransactionUtils {
    private const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

    // 转账交易
    fun buildSplTokenTransferTx(sourceAtaStr: String, destAtaStr: String, ownerPubkeyStr: String, amount: Long, recentBlockhash: String): ByteArray {
        val sourceAta = Base58.decode(sourceAtaStr); val destAta = Base58.decode(destAtaStr); val owner = Base58.decode(ownerPubkeyStr); val tokenProgram = Base58.decode(TOKEN_PROGRAM_ID); val bh = Base58.decode(recentBlockhash)
        val msg = java.io.ByteArrayOutputStream()
        msg.write(1); msg.write(0); msg.write(1); msg.write(4); msg.write(owner); msg.write(sourceAta); msg.write(destAta); msg.write(tokenProgram); msg.write(bh)
        msg.write(1); msg.write(3); msg.write(3); msg.write(1); msg.write(2); msg.write(0); msg.write(9); msg.write(3) // Opcode 3 = Transfer
        val amt = ByteArray(8); var l = amount; for(i in 0..7){ amt[i]=(l and 0xFF).toByte(); l = l shr 8 }; msg.write(amt)
        val msgBytes = msg.toByteArray()
        val tx = java.io.ByteArrayOutputStream(); tx.write(1); tx.write(ByteArray(64)); tx.write(msgBytes)
        return tx.toByteArray()
    }

    // 🟢 修复后的 Burn 交易 (Opcode 8)
    fun buildSplTokenBurnTx(sourceAtaStr: String, mintStr: String, ownerPubkeyStr: String, amount: Long, recentBlockhash: String): ByteArray {
        val sourceAta = Base58.decode(sourceAtaStr)
        val mint = Base58.decode(mintStr)
        val owner = Base58.decode(ownerPubkeyStr)
        val tokenProgram = Base58.decode(TOKEN_PROGRAM_ID)
        val bh = Base58.decode(recentBlockhash)

        val msg = java.io.ByteArrayOutputStream()
        msg.write(1); msg.write(0); msg.write(1); msg.write(4)
        msg.write(owner); msg.write(sourceAta); msg.write(mint); msg.write(tokenProgram)
        msg.write(bh)
        msg.write(1); msg.write(3)
        msg.write(3); msg.write(1); msg.write(2); msg.write(0) // Accounts indices
        msg.write(9)
        msg.write(8) // 🔴 Opcode 8 = Burn

        val amt = ByteArray(8)
        var l = amount
        for (i in 0..7) { amt[i] = (l and 0xFF).toByte(); l = l shr 8 }
        msg.write(amt)

        val msgBytes = msg.toByteArray()
        val tx = java.io.ByteArrayOutputStream(); tx.write(1); tx.write(ByteArray(64)); tx.write(msgBytes)
        return tx.toByteArray()
    }
}

// ==========================================
// 3. MainActivity (主程序)
// ==========================================
class MainActivity : ComponentActivity() {
    private val walletAdapter = MobileWalletAdapter()
    private val httpClient = OkHttpClient()

    // UI States
    private var walletAddress by mutableStateOf<String?>(null)
    private var userBalance by mutableStateOf("LOADING...")
    private var currentUsername by mutableStateOf("Anonymous")
    private var gamesList by mutableStateOf<List<GameSession>>(emptyList())
    private var historyList by mutableStateOf<List<GameSession>>(emptyList())

    private var currentScreenIndex by mutableStateOf(0)

    // Dialog Controls
    private var showCreateDialog by mutableStateOf(false)
    private var showResultDialog by mutableStateOf<GameResult?>(null)
    private var showEditNameDialog by mutableStateOf(false)
    private var isUpdatingName by mutableStateOf(false)

    // 🟢 关键修复：防止重复点击 Burn
    private var isBurning by mutableStateOf(false)

    // 🟢 关键修复：防止加入游戏金额错误
    private var pendingJoinGameId by mutableStateOf<String?>(null)
    private var pendingJoinAmount by mutableStateOf(50) // 暂存加入房间的金额

    // 🟢 强制更新控制
    private var isUpdateRequired by mutableStateOf(false)
    private var updateUrl by mutableStateOf("https://github.com/victorseeker/Riskr/releases")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sender = ActivityResultSender(this)
        val prefs = getSharedPreferences("RiskrData", Context.MODE_PRIVATE)

        walletAddress = prefs.getString("saved_wallet", null)
        if (walletAddress != null) refreshUserData()

        setupFirestoreListeners()

        // 🟢 启动时检查版本
        checkAppVersion()

        setContent {
            RiskrTechTheme {
                val context = LocalContext.current

                // 🟢 强制更新拦截层
                if (isUpdateRequired) {
                    ForceUpdateScreen(downloadUrl = updateUrl)
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TechBackground()

                        Scaffold(
                            containerColor = Color.Transparent,
                            bottomBar = {
                                NavigationBar(containerColor = TechPanelBg) {
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.Gamepad, "Lobby", modifier = Modifier.size(26.dp)) },
                                        label = { Text("LOBBY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp) },
                                        selected = currentScreenIndex == 0,
                                        onClick = { currentScreenIndex = 0 },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, unselectedIconColor = Color.Gray, indicatorColor = TechPanelBg)
                                    )
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.LocalFireDepartment, "Burn", modifier = Modifier.size(26.dp)) },
                                        label = { Text("BURN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp) },
                                        selected = currentScreenIndex == 1,
                                        onClick = { currentScreenIndex = 1 },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonRed, selectedTextColor = NeonRed, unselectedIconColor = Color.Gray, indicatorColor = TechPanelBg)
                                    )
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.Person, "Profile", modifier = Modifier.size(26.dp)) },
                                        label = { Text("PROFILE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp) },
                                        selected = currentScreenIndex == 2,
                                        onClick = { currentScreenIndex = 2 },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonPurple, selectedTextColor = NeonPurple, unselectedIconColor = Color.Gray, indicatorColor = TechPanelBg)
                                    )
                                }
                            },
                            floatingActionButton = {
                                if (currentScreenIndex == 0) {
                                    FloatingActionButton(
                                        onClick = {
                                            if (walletAddress == null) Toast.makeText(context, "Connect Wallet First!", Toast.LENGTH_SHORT).show()
                                            else showCreateDialog = true
                                        },
                                        containerColor = NeonCyan,
                                        shape = CutCornerShape(12.dp)
                                    ) { Icon(Icons.Default.Add, "Create", tint = Color.Black) }
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                when (currentScreenIndex) {
                                    0 -> LobbyScreen(
                                        games = gamesList,
                                        myAddress = walletAddress,
                                        onJoin = { gameId, amount ->
                                            if (walletAddress == null) {
                                                Toast.makeText(context, "Connect Wallet First!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                pendingJoinGameId = gameId
                                                pendingJoinAmount = amount // 🟢 记录金额
                                                showCreateDialog = true
                                            }
                                        },
                                        onCancel = { gameId -> cancelGame(gameId) }
                                    )
                                    1 -> BurnScreen(
                                        balance = userBalance,
                                        isLoading = isBurning, // 🟢 传入加载状态
                                        onBurn = { amount ->
                                            if (walletAddress == null) Toast.makeText(context, "Connect Wallet First!", Toast.LENGTH_SHORT).show()
                                            else burnTokens(sender, amount)
                                        }
                                    )
                                    2 -> ProfileScreen(
                                        walletAddress = walletAddress,
                                        username = currentUsername,
                                        balance = userBalance,
                                        history = historyList,
                                        onConnect = { connectWallet(sender) },
                                        onDisconnect = { disconnectWallet() },
                                        onRefresh = { refreshUserData() },
                                        onEditNameClick = { showEditNameDialog = true }
                                    )
                                }

                                if (showCreateDialog) {
                                    CreateGameDialog(
                                        isJoining = pendingJoinGameId != null,
                                        // 🟢 关键：传入正确金额
                                        initialAmount = if (pendingJoinGameId != null) pendingJoinAmount else 50,
                                        onDismiss = { showCreateDialog = false; pendingJoinGameId = null },
                                        onConfirm = { move, amount ->
                                            showCreateDialog = false
                                            if (pendingJoinGameId == null) createNewGame(sender, move, amount)
                                            else joinGame(sender, pendingJoinGameId!!, move, amount)
                                            pendingJoinGameId = null
                                        }
                                    )
                                }

                                if (showEditNameDialog) {
                                    EditNameDialog(
                                        currentName = currentUsername,
                                        isLoading = isUpdatingName,
                                        onDismiss = { showEditNameDialog = false },
                                        onConfirm = { newName -> updateUsername(newName) }
                                    )
                                }

                                showResultDialog?.let { result ->
                                    GameResultDialog(result = result, onDismiss = { showResultDialog = null })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Check Version ---
    private fun checkAppVersion() {
        val db = FirebaseFirestore.getInstance()
        db.collection("config").document("app_settings").get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val minVersion = document.getLong("min_version_code")?.toInt() ?: 1
                    val serverUrl = document.getString("latest_download_url")

                    if (serverUrl != null) updateUrl = serverUrl

                    try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val myVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                        if (myVersionCode < minVersion) {
                            isUpdateRequired = true
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
    }

    // --- Firebase & Listeners ---
    private fun setupFirestoreListeners() {
        val db = FirebaseFirestore.getInstance()
        db.collection("games")
            .whereEqualTo("status", "WAITING")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { s, _ -> if (s != null) gamesList = s.documents.map { mapGame(it) } }
    }

    private fun mapGame(doc: com.google.firebase.firestore.DocumentSnapshot): GameSession {
        return GameSession(
            id = doc.id,
            hostAddress = doc.getString("hostAddress") ?: "",
            betAmount = doc.getLong("betAmount")?.toInt() ?: 50,
            status = doc.getString("status") ?: "WAITING",
            winner = doc.getString("winner")
        )
    }

    // --- 核心业务逻辑 ---
    private fun burnTokens(sender: ActivityResultSender, amount: Int) {
        if (isBurning) return // 🔒 上锁防止重复点击
        isBurning = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myAddr = walletAddress ?: return@launch
                val blockhash = SolanaService.getRecentBlockhash() ?: return@launch
                val myAta = SolanaService.findSkrAccount(myAddr) ?: return@launch

                // 🟢 使用 Burn Tx (Opcode 8)
                val txBytes = TransactionUtils.buildSplTokenBurnTx(
                    sourceAtaStr = myAta,
                    mintStr = SolanaService.SKR_MINT,
                    ownerPubkeyStr = myAddr,
                    amount = amount * 1_000_000L,
                    recentBlockhash = blockhash
                )

                val result = walletAdapter.transact(sender) {
                    authorize(Uri.parse("https://riskr.app"), Uri.parse(""), "Riskr")
                    signAndSendTransactions(arrayOf(txBytes))
                }

                if (result is TransactionResult.Success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "ASHES TO ASHES... BURNED!", Toast.LENGTH_LONG).show()
                        refreshUserData()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Burn Cancelled", Toast.LENGTH_SHORT).show() }
            } finally {
                isBurning = false // 🔓 解锁
            }
        }
    }

    private fun createNewGame(sender: ActivityResultSender, move: String, amount: Int) {
        performTransaction(sender, amount.toLong()) { txHash ->
            val json = JSONObject().apply {
                put("hostAddress", walletAddress);
                put("move", move);
                put("amount", amount);
                put("txHash", txHash);
                put("version", 4);
            }
            postBackend("/create-game", json) { refreshUserData(); Toast.makeText(this, "Room Created", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun joinGame(sender: ActivityResultSender, gameId: String, move: String, amount: Int) {
        performTransaction(sender, amount.toLong()) { txHash ->
            val json = JSONObject().apply {
                put("gameId", gameId);
                put("joinerAddress", walletAddress);
                put("joinerMove", move);
                put("txHash", txHash);
                put("version", 4)
            }
            postBackend("/join-game", json) { respStr ->
                try {
                    val resp = JSONObject(respStr)
                    val winner = resp.getString("winner")
                    val hostMove = resp.getString("hostMove")
                    val isWin = winner == walletAddress
                    val isDraw = winner == "DRAW"
                    showResultDialog = GameResult(isWin, isDraw, hostMove, if (isWin) (amount * 0.8).toInt() else -amount)
                    refreshUserData()
                } catch(e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun cancelGame(gameId: String) {
        val json = JSONObject().apply { put("gameId", gameId); put("requesterAddress", walletAddress) }
        postBackend("/cancel-game", json) { respStr ->
            try {
                val resp = JSONObject(respStr)
                if (resp.optBoolean("success")) {
                    Toast.makeText(this, "Game Cancelled & Refunded!", Toast.LENGTH_SHORT).show()
                    refreshUserData()
                } else {
                    val errorMsg = resp.optString("error", "Cancel Failed")
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Game Cancelled!", Toast.LENGTH_SHORT).show()
                refreshUserData()
            }
        }
    }

    private fun updateUsername(newName: String) {
        if (walletAddress == null) return
        isUpdatingName = true
        val json = JSONObject().apply { put("walletAddress", walletAddress); put("newUsername", newName) }
        postBackend("/update-username", json) { respStr ->
            isUpdatingName = false
            try {
                val resp = JSONObject(respStr)
                if (resp.optBoolean("success")) {
                    currentUsername = resp.getString("username")
                    showEditNameDialog = false
                    Toast.makeText(this, "ID Updated: $currentUsername", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { Toast.makeText(this, "Update Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun performTransaction(sender: ActivityResultSender, amount: Long, onSuccess: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myAddr = walletAddress ?: return@launch
                val blockhash = SolanaService.getRecentBlockhash() ?: return@launch
                val myAta = SolanaService.findSkrAccount(myAddr) ?: return@launch
                val txBytes = TransactionUtils.buildSplTokenTransferTx(myAta, TREASURY_ADDR, myAddr, amount * 1_000_000L, blockhash)
                val result = walletAdapter.transact(sender) {
                    authorize(Uri.parse("https://riskr.app"), Uri.parse(""), "Riskr")
                    signAndSendTransactions(arrayOf(txBytes))
                }
                if (result is TransactionResult.Success) {
                    val signature = Base58.encode(result.payload.signatures[0])
                    withContext(Dispatchers.Main) { onSuccess(signature) }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Tx Aborted", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun postBackend(endpoint: String, json: JSONObject, onSuccess: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url(BACKEND_URL + endpoint)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = httpClient.newCall(req).execute()
                val respStr = resp.body?.string() ?: "{}"

                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) onSuccess(respStr)
                    else {
                        try {
                            val errorJson = JSONObject(respStr)
                            val errorMsg = errorJson.optString("error", "Unknown Error")
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Error: ${resp.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Network Error", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun refreshUserData() {
        if (walletAddress == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val bal = SolanaService.getSkrBalance(walletAddress!!)
            try {
                val db = FirebaseFirestore.getInstance()
                val userDoc = db.collection("users").document(walletAddress!!).get().await()
                val name = userDoc.getString("username")
                withContext(Dispatchers.Main) {
                    userBalance = bal
                    currentUsername = name ?: "Player_${walletAddress!!.take(4)}"
                }
            } catch (e: Exception) { e.printStackTrace() }
            try {
                val db = FirebaseFirestore.getInstance()
                val snap = db.collection("games")
                    .whereEqualTo("hostAddress", walletAddress)
                    .whereEqualTo("status", "FINISHED")
                    .limit(10).get().await()
                withContext(Dispatchers.Main) { historyList = snap.documents.map { mapGame(it) } }
            } catch (e: Exception) {}
        }
    }

    private fun connectWallet(sender: ActivityResultSender) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = walletAdapter.transact(sender) { authorize(Uri.parse("https://riskr.app"), Uri.parse(""), "Riskr") }
                if (res is TransactionResult.Success) {
                    val addr = Base58.encode(res.payload.publicKey)
                    withContext(Dispatchers.Main) {
                        walletAddress = addr
                        getSharedPreferences("RiskrData", Context.MODE_PRIVATE).edit().putString("saved_wallet", addr).apply()
                        refreshUserData()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun disconnectWallet() {
        walletAddress = null; userBalance = "0"; currentUsername = "Anonymous"
        getSharedPreferences("RiskrData", Context.MODE_PRIVATE).edit().remove("saved_wallet").apply()
    }
}

// ==========================================
// 4. UI 组件 (Cyberpunk UI)
// ==========================================

@Composable
fun LobbyScreen(games: List<GameSession>, myAddress: String?, onJoin: (String, Int) -> Unit, onCancel: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "// RISKR //",
            color = NeonCyan,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
        )
        if (games.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("[ NO GAMES YET ]", color = Color.Gray, fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn { items(games) { game -> GameCard(game, myAddress, onJoin, onCancel) } }
        }
    }
}

@Composable
fun GameCard(game: GameSession, myAddress: String?, onJoin: (String, Int) -> Unit, onCancel: (String) -> Unit) {
    val isMyGame = game.hostAddress == myAddress
    CyberCard(color = if (isMyGame) NeonPurple else NeonCyan) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("CHALLENGER:", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    text = if (isMyGame) "> YOU <" else "${game.hostAddress.take(4)}...${game.hostAddress.takeLast(4)}",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MonetizationOn, null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                    Text(" ${game.betAmount} SKR", color = NeonGreen, fontWeight = FontWeight.Black)
                }
            }
            if (isMyGame) CyberButton(onClick = { onCancel(game.id) }, text = "CANCEL", isOutline = true, color = NeonPurple)
            else CyberButton(onClick = { onJoin(game.id, game.betAmount) }, text = "PLAY", color = NeonCyan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGameDialog(isJoining: Boolean, initialAmount: Int, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var selectedMove by remember { mutableStateOf<String?>(null) }
    // 🟢 使用传入的 initialAmount 初始化
    var selectedAmount by remember { mutableStateOf(initialAmount) }

    Dialog(onDismissRequest = onDismiss) {
        CyberCard(color = if (isJoining) NeonCyan else NeonPurple) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isJoining) ">> JOIN GAME <<" else ">> CREATE GAME <<",
                    color = if (isJoining) NeonCyan else NeonPurple,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    letterSpacing = 1.sp
                )

                Spacer(Modifier.height(16.dp))

                if (!isJoining) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        listOf(50, 100, 500).forEach { amount ->
                            FilterChip(
                                selected = selectedAmount == amount,
                                onClick = { selectedAmount = amount },
                                label = { Text("$amount") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonPurple,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                } else {
                    Text(
                        text = "BET AMOUNT: $selectedAmount SKR",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("🪨" to "ROCK", "📄" to "PAPER", "✂️" to "SCISSORS").forEach { (emoji, value) ->
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedMove == value) (if (isJoining) NeonCyan else NeonPurple) else Color(0xFF222222))
                                .clickable { selectedMove = value },
                            contentAlignment = Alignment.Center
                        ) { Text(emoji, fontSize = 30.sp) }
                    }
                }

                Spacer(Modifier.height(24.dp))
                CyberButton(
                    onClick = { if (selectedMove != null) onConfirm(selectedMove!!, selectedAmount) },
                    text = if (isJoining) "PAY & FIGHT" else "CREATE",
                    color = if (isJoining) NeonCyan else NeonPurple,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun EditNameDialog(currentName: String, isLoading: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var tempName by remember { mutableStateOf(currentName) }
    Dialog(onDismissRequest = onDismiss) {
        CyberCard(color = NeonPurple) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CHANGE NAME", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = tempName, onValueChange = { if (it.length <= 12) tempName = it },
                    label = { Text("Name") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPurple, focusedLabelColor = NeonPurple, cursorColor = NeonPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.Gray),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                if (isLoading) CircularProgressIndicator(color = NeonPurple)
                else CyberButton(onClick = { onConfirm(tempName) }, text = "SAVE", color = NeonPurple, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun GameResultDialog(result: GameResult, onDismiss: () -> Unit) {
    val color = if (result.isWin) NeonGreen else if (result.isDraw) Color.White else NeonRed
    Dialog(onDismissRequest = onDismiss) {
        CyberCard(color = color) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Text(
                    text = if (result.isWin) "VICTORY" else if (result.isDraw) "DRAW" else "DEFEATED",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = color,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(16.dp))
                Text("OPPONENT:", color = Color.Gray, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if(result.opponentMove=="ROCK") "🪨" else if(result.opponentMove=="PAPER") "📄" else "✂️",
                    fontSize = 50.sp
                )
                Spacer(Modifier.height(20.dp))
                if (result.isDraw) {
                    Text("REFUNDED", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                } else if (result.isWin) {
                    Text("+${result.profit} SKR", color = NeonGreen, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                } else {
                    Text("${result.profit} SKR", color = NeonRed, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(24.dp))
                CyberButton(onClick = onDismiss, text = "OK", color = color, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun ProfileScreen(walletAddress: String?, username: String, balance: String, history: List<GameSession>, onConnect: () -> Unit, onDisconnect: () -> Unit, onRefresh: () -> Unit, onEditNameClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        if (walletAddress == null) {
            Text("NOT CONNECTED", color = Color.Gray, letterSpacing = 2.sp)
            Spacer(Modifier.height(20.dp))
            CyberButton(onClick = onConnect, text = "CONNECT WALLET", modifier = Modifier.fillMaxWidth().height(50.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(username.uppercase(), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                IconButton(onClick = onEditNameClick) { Icon(Icons.Default.Edit, "Edit", tint = NeonPurple) }
            }
            Text("[ ${walletAddress.take(4)}...${walletAddress.takeLast(4)} ]", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.height(30.dp))
            CyberCard(color = NeonGreen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("BALANCE", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("$balance SKR", color = NeonGreen, fontSize = 36.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    CyberButton(onClick = onRefresh, text = "SYNC", isOutline = true, color = NeonGreen, modifier = Modifier.height(36.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            Text("// HISTORY //", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start), letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(history) { game ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).drawBehind { drawLine(Color.DarkGray, Offset(0f, size.height), Offset(size.width, size.height)) }, horizontalArrangement = Arrangement.SpaceBetween) {
                        val isWin = game.winner == walletAddress
                        Text(if (isWin) "WIN" else if (game.winner == "DRAW") "DRAW" else "LOSS", color = if (isWin) NeonGreen else if (game.winner == "DRAW") Color.White else NeonRed, fontWeight = FontWeight.Bold)
                        Text("${game.betAmount} SKR", color = NeonCyan, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            CyberButton(onClick = onDisconnect, text = "DISCONNECT", color = NeonRed, modifier = Modifier.fillMaxWidth())
        }
    }
}

// 🟢 修复后的 Burn 页面 (带状态锁)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurnScreen(balance: String, isLoading: Boolean, onBurn: (Int) -> Unit) {
    var burnAmount by remember { mutableStateOf(10) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "// INCINERATOR //", color = NeonRed, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text = "PERMANENTLY DESTROY SKR", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(40.dp))

        CyberCard(color = NeonRed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Icon(imageVector = Icons.Default.LocalFireDepartment, contentDescription = "Fire", tint = if (isLoading) Color.Gray else NeonRed, modifier = Modifier.size(80.dp))
                Spacer(Modifier.height(20.dp))
                Text("SELECT AMOUNT", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf(10, 100, 500).forEach { amount ->
                        FilterChip(
                            selected = burnAmount == amount,
                            onClick = { if (!isLoading) burnAmount = amount },
                            label = { Text("$amount") },
                            enabled = !isLoading,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonRed, selectedLabelColor = Color.Black, containerColor = Color.Transparent, labelColor = NeonRed, disabledContainerColor = Color.Transparent, disabledLabelColor = Color.Gray)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("BALANCE: $balance SKR", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(40.dp))

        if (isLoading) CircularProgressIndicator(color = NeonRed)
        else CyberButton(onClick = { onBurn(burnAmount) }, text = "BURN", color = NeonRed, modifier = Modifier.fillMaxWidth().height(50.dp))

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isLoading) "BURNING WITH BLOCKCHAIN..." else "WARNING: THIS ACTION CANNOT BE UNDONE.",
            color = if (isLoading) NeonCyan else NeonRed.copy(alpha = 0.7f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
}

// 🟢 强制更新红屏页面
@Composable
fun ForceUpdateScreen(downloadUrl: String) {
    val context = LocalContext.current
    BackHandler(enabled = true) { } // 拦截返回键

    Box(modifier = Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = NeonRed.copy(alpha = 0.1f)
            val gridSize = 40.dp.toPx()
            for (y in 0..size.height.toInt() step gridSize.toInt()) drawLine(gridColor, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1f)
            for (x in 0..size.width.toInt() step gridSize.toInt()) drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = "Warning", tint = NeonRed, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("UPDATE APP", color = NeonRed, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(16.dp))
            Text("CRITICAL UPDATE REQUIRED.\nACCESS DENIED.", color = Color.White, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.alpha(0.8f))
            Spacer(Modifier.height(40.dp))
            CyberButton(
                onClick = { val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(downloadUrl)); context.startActivity(intent) },
                text = "DOWNLOAD PATCH", color = NeonRed, modifier = Modifier.fillMaxWidth().height(50.dp)
            )
        }
    }
}