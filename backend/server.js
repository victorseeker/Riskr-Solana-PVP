// ====================================================
// 🛡️ RISKR BACKEND (PUBLIC DEMO VERSION)
// NOTE: Sensitive keys and logic have been sanitized for public display.
// ====================================================

require('dotenv').config();
const express = require('express');
const admin = require('firebase-admin');
const { Connection, Keypair, PublicKey, Transaction } = require('@solana/web3.js');
const { getOrCreateAssociatedTokenAccount, createTransferInstruction } = require('@solana/spl-token');
const cors = require('cors');
const bs58 = require('bs58'); 

const app = express();
app.use(cors());
app.use(express.json());

// Health Check
app.get('/', (req, res) => {
    res.send('Riskr Backend is Running! 🚀');
});

// --- Configuration ---
// 🔒 [SECURITY] Service Account is loaded from environment variables in production
// const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
admin.initializeApp({
    // credential: admin.credential.cert(serviceAccount) 
    credential: admin.credential.applicationDefault() // Use default for demo
});
const db = admin.firestore();

// Solana Connection (Mainnet/Devnet)
const CONNECTION = new Connection("https://api.mainnet-beta.solana.com", "confirmed");
const SKR_MINT = new PublicKey("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3");

// 🔒 [SECURITY] Treasury Keypair is loaded securely in production
// const TREASURY_SECRET = bs58.decode(process.env.TREASURY_PRIVATE_KEY);
// const treasuryKeypair = Keypair.fromSecretKey(TREASURY_SECRET);
const treasuryKeypair = Keypair.generate(); // Placeholder for public repo

// --- API 1: Create Game ---
app.post('/create-game', async (req, res) => {
    try {
        const { hostAddress, move, txHash, amount } = req.body;
        const betAmount = amount || 50; 

        // [Production] Verify txHash on-chain before creating game...

        const newGame = {
            hostAddress,
            hostMove: move, // In production, this should be hashed/encrypted
            betAmount: betAmount,
            status: "WAITING",
            createdAt: Date.now()
        };
        
        const docRef = await db.collection('games').add(newGame);
        res.json({ success: true, gameId: docRef.id });
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// --- API 2: Join Game (Core Logic) ---
app.post('/join-game', async (req, res) => {
    try {
        const { gameId, joinerAddress, joinerMove, txHash } = req.body;
        const gameRef = db.collection('games').doc(gameId);

        await db.runTransaction(async (t) => {
            const doc = await t.get(gameRef);
            if (!doc.exists || doc.data().status !== 'WAITING') {
                throw new Error("Game unavailable");
            }
            const data = doc.data();
            
            // Determine Winner
            let winner = determineWinner(data.hostMove, joinerMove, data.hostAddress, joinerAddress);
            
            // Calculate Payouts
            const bet = data.betAmount * 1_000_000; 
            const totalPot = bet * 2;
            
            if (winner === 'DRAW') {
                await payout(data.hostAddress, bet);
                await payout(joinerAddress, bet);
            } else {
                const fee = totalPot * 0.10; // 10% Platform Fee
                const prize = totalPot - fee;
                await payout(winner, prize);
            }

            t.update(gameRef, {
                joinerAddress,
                joinerMove,
                status: "FINISHED",
                winner: winner,
                settledAt: Date.now()
            });

            res.json({ success: true, winner, hostMove: data.hostMove, myMove: joinerMove });
        });

    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// --- API 3: Cancel Game (With Anti-Spam) ---
app.post('/cancel-game', async (req, res) => {
    try {
        const { gameId, requesterAddress } = req.body;

        // 1. Check 5-minute cooldown
        const userRef = db.collection('users').doc(requesterAddress);
        const userSnap = await userRef.get();
        if (userSnap.exists) {
            const lastCancel = userSnap.data().lastCancelAt || 0;
            if (Date.now() - lastCancel < 300000) {
                 return res.status(400).json({ error: "Cooldown active." });
            }
        }

        // 2. Process Refund
        const gameRef = db.collection('games').doc(gameId);
        await db.runTransaction(async (t) => {
            const doc = await t.get(gameRef);
            if (!doc.exists) throw new Error("Game not found");
            
            // Refund logic...
            await payout(doc.data().hostAddress, doc.data().betAmount * 1000000);
            t.delete(gameRef); 
            t.set(userRef, { lastCancelAt: Date.now() }, { merge: true });
        });

        res.json({ success: true });
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// --- Helper Functions ---
function determineWinner(p1, p2, addr1, addr2) {
    if (p1 === p2) return 'DRAW';
    if ((p1 === 'ROCK' && p2 === 'SCISSORS') ||
        (p1 === 'SCISSORS' && p2 === 'PAPER') ||
        (p1 === 'PAPER' && p2 === 'ROCK')) {
        return addr1;
    }
    return addr2;
}

async function payout(toAddress, amount) {
    console.log(`[Mock Payout] Sending ${amount} to ${toAddress}`);
    // In production: Web3 transaction signing logic here
    return "mock_signature_123";
}

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`Riskr Public Node running on ${PORT}`));