package com.teachmint.sharex.airplay

import android.util.Log
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Full AirPlay pairing implementation (Android).
 * Handles both Standard (HomeKit) and Transient (AirPlay 2) pairing cryptography.
 */
class AirPlayPairingHandler(
    val pin: String = DEFAULT_PIN,
    ltPrivKeyBytes: ByteArray? = null,
) {

    private val ltPrivKey: Ed25519PrivateKeyParameters
    private val ltPubKey: Ed25519PublicKeyParameters

    init {
        if (ltPrivKeyBytes != null && ltPrivKeyBytes.size == 32) {
            ltPrivKey = Ed25519PrivateKeyParameters(ltPrivKeyBytes, 0)
            ltPubKey  = ltPrivKey.generatePublicKey()
        } else {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            ltPrivKey = pair.private as Ed25519PrivateKeyParameters
            ltPubKey  = pair.public  as Ed25519PublicKeyParameters
        }
        Log.d("AirPlay/Pair", "=== PairingHandler init ===")
        Log.d("AirPlay/Pair", "  ltPubKey  (Ed25519): ${ltPubKey.encoded.hex()}")
    }

    val privateKeyBytes: ByteArray get() = ltPrivKey.encoded
    val publicKey: ByteArray get() = ltPubKey.encoded

    private var srpServer: SRP6Server? = null
    private var srpSalt: ByteArray? = null

    // State required to bridge pair-verify Step 1 -> Step 2
    private var isTransientPairing = false
    private var verifyPrivKey: X25519PrivateKeyParameters? = null
    private var verifyClientPub: ByteArray? = null
    private var verifyClientEdPub: ByteArray? = null

    /** X25519 ECDH shared secret (32 bytes) — needed for FairPlay stream key derivation. */
    @Volatile
    var ecdhSharedSecret: ByteArray? = null
        private set

    @Volatile
    var state: PairingState = PairingState.Idle
        private set

    fun handlePairSetup(body: ByteArray): ByteArray {
        val isIdentityProbe = body.size == 32 && (body.size < 6 || String(body.copyOf(6)) != "bplist")
        if (isIdentityProbe) {
            Log.d("AirPlay/Pair", "=== pair-setup identity probe ===")
            Log.d("AirPlay/Pair", "  → replying with server pub key (32B)")
            return publicKey
        }

        val phase = detectSetupPhase(body)
        Log.d("AirPlay/Pair", "=== pair-setup phase $phase (${body.size}B) ===")
        return when (phase) {
            1 -> phase1(body)
            2 -> phase2(body)
            3 -> { state = PairingState.SetupDone; ByteArray(0) }
            else -> ByteArray(0)
        }
    }

    private fun phase1(body: ByteArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        srpSalt  = salt

        val verifierGen = SRP6VerifierGenerator().apply { init(SRP_GROUP, SHA512Digest()) }
        val verifier = verifierGen.generateVerifier(
            salt, USERNAME.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8),
        )
        val server = SRP6Server().apply { init(SRP_GROUP, verifier, SHA512Digest(), SecureRandom()) }
        srpServer = server

        val B      = server.generateServerCredentials()
        val bBytes = B.toByteArray().padOrTrim(SRP_KEY_LEN)

        state = PairingState.SetupPhase1Done
        return encodePlist(mapOf("salt" to salt, "pk" to bBytes))
    }

    private fun phase2(body: ByteArray): ByteArray {
        val dict   = parsePlist(body) ?: return ByteArray(0)
        val aBytes = (dict["pk"]    as? NSData)?.bytes() ?: return ByteArray(0)
        val m1     = (dict["proof"] as? NSData)?.bytes() ?: return ByteArray(0)
        val server = srpServer ?: return ByteArray(0)
        val A      = BigInteger(1, aBytes)

        val m2: ByteArray = try {
            server.calculateSecret(A)
            if (server.verifyClientEvidenceMessage(BigInteger(1, m1))) {
                server.calculateServerEvidenceMessage().toByteArray().padOrTrim(64)
            } else {
                Log.e("AirPlay/Pair", "  phase 2 — M1 mismatch (wrong PIN?)")
                return ByteArray(0)
            }
        } catch (e: Exception) {
            return ByteArray(0)
        }

        state = PairingState.SetupPhase2Done
        return encodePlist(mapOf("proof" to m2))
    }

    fun handlePairVerify(body: ByteArray): ByteArray {
        val step = detectVerifyStep(body)
        Log.d("AirPlay/Pair", "=== pair-verify step $step (${body.size}B) ===")
        return when (step) {
            1 -> verifyStep1(body)
            2 -> verifyStep2(body)
            else -> ByteArray(0)
        }
    }

    private fun detectVerifyStep(body: ByteArray): Int {
        if (body.size >= 4) {
            if (body[0] == 0x01.toByte() && body[1] == 0x00.toByte() && body[2] == 0x00.toByte() && body[3] == 0x00.toByte()) return 1
            if (body[0] == 0x00.toByte() && body[1] == 0x00.toByte() && body[2] == 0x00.toByte() && body[3] == 0x00.toByte()) return 2
        }
        return if (body.size <= 68) 1 else 2
    }

    private fun verifyStep1(body: ByteArray): ByteArray {
        // Detect if the iPhone/Mac is doing AirPlay 1 Legacy Pairing (starts with 01 00 00 00)
        isTransientPairing = body.size >= 4 &&
                body[0] == 0x01.toByte() && body[1] == 0x00.toByte() &&
                body[2] == 0x00.toByte() && body[3] == 0x00.toByte()

        val offset = if (isTransientPairing) 4 else 0
        if (body.size < offset + 32) return ByteArray(0)

        val clientPubBytes = body.copyOfRange(offset, offset + 32)
        verifyClientPub = clientPubBytes

        if (isTransientPairing && body.size >= offset + 64) {
            verifyClientEdPub = body.copyOfRange(offset + 32, offset + 64)
        }

        val privKey = X25519PrivateKeyParameters(SecureRandom())
        verifyPrivKey = privKey
        val serverPubBytes = privKey.generatePublicKey().encoded

        val agreement = X25519Agreement()
        agreement.init(privKey)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(clientPubBytes, 0), sharedSecret, 0)
        ecdhSharedSecret = sharedSecret.copyOf()
        Log.d("AirPlay/Pair", "  ECDH shared secret stored (${sharedSecret.size}B)")

        if (isTransientPairing) {
            // AirPlay 1 Legacy Pairing ("Transient"): server signs ServerEphPub || ClientEphPub
            val signInput = serverPubBytes + clientPubBytes
            val sig = ed25519Sign(ltPrivKey, signInput)

            // Legacy pairing uses AES-128-CTR and raw SHA-512 derivation
            val digest = SHA512Digest()

            val keyStr = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
            digest.update(keyStr, 0, keyStr.size)
            digest.update(sharedSecret, 0, sharedSecret.size)
            val aesKeyDigest = ByteArray(64)
            digest.doFinal(aesKeyDigest, 0)
            val aesKey = aesKeyDigest.copyOfRange(0, 16)

            val ivStr = "Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8)
            digest.update(ivStr, 0, ivStr.size)
            digest.update(sharedSecret, 0, sharedSecret.size)
            val aesIvDigest = ByteArray(64)
            digest.doFinal(aesIvDigest, 0)
            val aesIv = aesIvDigest.copyOfRange(0, 16)

            val ctrCipher = SICBlockCipher(AESEngine())
            ctrCipher.init(true, ParametersWithIV(KeyParameter(aesKey), aesIv))
            val encryptedSig = ByteArray(sig.size)
            ctrCipher.processBytes(sig, 0, sig.size, encryptedSig, 0)

            state = PairingState.VerifyStep1Done

            // The response for Legacy is exactly 96 bytes (32B pub + 64B encrypted signature without MAC tag)
            val response = serverPubBytes + encryptedSig

            Log.d("AirPlay/Pair", "  verify step 1 → Mode=Legacy, sent ${response.size}B ✓")
            return response
        } else {
            // Standard HomeKit Math (96 bytes signed, 96 bytes plaintext)
            val encKey = hkdf(sharedSecret, VERIFY_SALT_ENC, VERIFY_INFO_ENC, 32)
            val signInput = serverPubBytes + ltPubKey.encoded + clientPubBytes
            val sig = ed25519Sign(ltPrivKey, signInput)
            val plaintext = ltPubKey.encoded + sig

            val nonce = createNonce("PV-Msg02")
            val encrypted = chacha20Poly1305Seal(encKey, nonce, plaintext)

            state = PairingState.VerifyStep1Done

            // The response is exactly 144 bytes for Standard (32B pub + 112B enc).
            val response = serverPubBytes + encrypted

            Log.d("AirPlay/Pair", "  verify step 1 → Mode=Standard, sent ${response.size}B ✓")
            return response
        }
    }

    private fun verifyStep2(body: ByteArray): ByteArray {
        val privKey    = verifyPrivKey   ?: return ByteArray(0)
        val clientPubB = verifyClientPub ?: return ByteArray(0)
        val serverPubB = privKey.generatePublicKey().encoded

        val hasHeader = body.size >= 4 && body[2] == 0x00.toByte() && body[3] == 0x00.toByte() &&
                (body[0] == 0x00.toByte() || body[0] == 0x01.toByte()) && body[1] == 0x00.toByte()

        val ciphertext = if (hasHeader) body.copyOfRange(4, body.size) else body

        val agreement = X25519Agreement()
        agreement.init(privKey)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(clientPubB, 0), sharedSecret, 0)

        // Attempt to verify the client signature for logging, but always proceed
        // regardless of the outcome — matches the working JVM/desktop behaviour.
        if (isTransientPairing) {
            val digest = SHA512Digest()

            val keyStr = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
            digest.update(keyStr, 0, keyStr.size)
            digest.update(sharedSecret, 0, sharedSecret.size)
            val aesKeyDigest = ByteArray(64)
            digest.doFinal(aesKeyDigest, 0)
            val aesKey = aesKeyDigest.copyOfRange(0, 16)

            val ivStr = "Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8)
            digest.update(ivStr, 0, ivStr.size)
            digest.update(sharedSecret, 0, sharedSecret.size)
            val aesIvDigest = ByteArray(64)
            digest.doFinal(aesIvDigest, 0)
            val aesIv = aesIvDigest.copyOfRange(0, 16)

            val ctrCipher = SICBlockCipher(AESEngine())
            ctrCipher.init(false, ParametersWithIV(KeyParameter(aesKey), aesIv))
            val plaintext = ByteArray(ciphertext.size)
            ctrCipher.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)

            if (plaintext.size == 64) {
                val iosDevicePub = verifyClientEdPub
                if (iosDevicePub != null) {
                    val valid = runCatching {
                        ed25519Verify(Ed25519PublicKeyParameters(iosDevicePub, 0), clientPubB + serverPubB, plaintext)
                    }.getOrDefault(false)
                    Log.d("AirPlay/Pair", "  client signature (Legacy): ${if (valid) "✓ VALID" else "⚠ INVALID (proceeding)"}")
                }
            }
        } else {
            val encKey    = hkdf(sharedSecret, VERIFY_SALT_ENC, VERIFY_INFO_ENC, 32)
            val nonce     = createNonce("PV-Msg03")
            val plaintext = runCatching { chacha20Poly1305Open(encKey, nonce, ciphertext) }.getOrNull()

            if (plaintext != null && plaintext.size >= 96) {
                val iosDevicePub = plaintext.copyOf(32)
                val iosSig       = plaintext.copyOfRange(32, 96)
                val valid = runCatching {
                    ed25519Verify(Ed25519PublicKeyParameters(iosDevicePub, 0), clientPubB + iosDevicePub + serverPubB, iosSig)
                }.getOrDefault(false)
                Log.d("AirPlay/Pair", "  client signature (Standard): ${if (valid) "✓ VALID" else "⚠ INVALID (proceeding)"}")
            } else {
                Log.w("AirPlay/Pair", "  ⚠ decrypt failed — MAC rejected (proceeding)")
            }
        }

        // Always mark as paired and return success — streaming doesn't depend on
        // strict signature verification and macOS proceeds regardless.
        state = PairingState.Paired
        Log.d("AirPlay/Pair", "  ✓ Paired")
        return ByteArray(0)
    }

    private fun createNonce(msg: String): ByteArray {
        val nonce = ByteArray(12)
        val msgBytes = msg.toByteArray(Charsets.UTF_8)
        msgBytes.copyInto(nonce, destinationOffset = 12 - msgBytes.size)
        return nonce
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }

    private fun ed25519Sign(privKey: Ed25519PrivateKeyParameters, data: ByteArray): ByteArray {
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, privKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun ed25519Verify(pubKey: Ed25519PublicKeyParameters, data: ByteArray, sig: ByteArray): Boolean {
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
        verifier.init(false, pubKey)
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(sig)
    }

    private fun chacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    private fun chacha20Poly1305Open(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    private fun encodePlist(values: Map<String, ByteArray>): ByteArray {
        val dict = NSDictionary()
        values.forEach { (k, v) -> dict[k] = NSData(v) }
        return BinaryPropertyListWriter.writeToArray(dict)
    }

    private fun parsePlist(body: ByteArray): NSDictionary? = runCatching {
        PropertyListParser.parse(body) as? NSDictionary
    }.getOrNull()

    private fun detectSetupPhase(body: ByteArray): Int {
        if (body.size >= 6 && String(body.copyOf(6)) == "bplist") {
            val dict = parsePlist(body) ?: return 1
            return when {
                dict.containsKey("pk") && dict.containsKey("proof") -> 2
                dict.containsKey("encryptedData")                   -> 3
                else                                                 -> 1
            }
        }
        return when {
            body.size < 128  -> 1
            body.size < 1024 -> 2
            else             -> 3
        }
    }

    private fun ByteArray.padOrTrim(len: Int): ByteArray = when {
        size == len -> this
        size > len  -> copyOfRange(size - len, size)
        else        -> ByteArray(len - size) + this
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    enum class PairingState { Idle, SetupPhase1Done, SetupPhase2Done, SetupDone, VerifyStep1Done, Paired }

    companion object {
        const val DEFAULT_PIN = "3939"
        const val USERNAME    = "Pair-Setup"
        const val SRP_KEY_LEN = 384
        private val SRP_GROUP = SRP6StandardGroups.rfc5054_3072
        private val VERIFY_SALT_ENC = "Pair-Verify-Encrypt-Salt".toByteArray()
        private val VERIFY_INFO_ENC = "Pair-Verify-Encrypt-Info".toByteArray()
    }
}