// @generated DO NOT EDIT — see mesh-constants.json + scripts/gen-mesh-constants.mjs
package run.krolik.oxpulse.mesh

import java.util.UUID

object MeshConstantsGenerated {
    val SERVICE_UUID: UUID = UUID.fromString("f0f10000-6f78-7075-6c73-65000000c8b1")
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("f0f10001-6f78-7075-6c73-65000000c8b1")
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("f0f10002-6f78-7075-6c73-65000000c8b1")
    const val FRAME_MAGIC: Byte = 0xC8.toByte()
    const val MAX_FRAME_SIZE: Int = 65536
    const val GATT_MTU_DEFAULT: Int = 23
    const val GATT_MTU_TARGET: Int = 247
    const val PEER_ID_BYTES: Int = 8
    const val NOISE_PATTERN_ID: String = "xx_25519_aesgcm_sha256"
    const val MLKEM_PARAM_SET: String = "ml-kem-768"
    const val MLKEM_PUBLIC_KEY_BYTES: Int = 1184
    const val MLKEM_CIPHERTEXT_BYTES: Int = 1088
    const val MLKEM_SHARED_SECRET_BYTES: Int = 32
    const val AEAD_NONCE_BYTES: Int = 12
    const val AEAD_KEY_BYTES: Int = 16
    const val SAS_DIGIT_COUNT: Int = 5
    const val IDENTITY_KEY_VERSION: Int = 1
    const val REPLAY_WINDOW_SIZE: Int = 64
    /** Target distinct msgId count before Bloom FP rate degrades. */
    const val DEDUP_BLOOM_CAPACITY: Int = 50000
    /** Acceptable false-positive rate at capacity (1-in-1000). */
    const val DEDUP_BLOOM_FP_RATE: Double = 0.001
    /** Schema version for the Bloom IndexedDB store. */
    const val DEDUP_BLOOM_DB_VERSION: Int = 1
    const val MAX_HANDSHAKE_MSG_BYTES: Int = 1500
    const val HANDSHAKE_TIMEOUT_MS: Int = 15000
    /** Maximum number of TOFU entries; oldest evicted when exceeded. */
    const val TOFU_MAX_ENTRIES: Int = 1000
    /** Number of bytes taken from BLAKE3 output for channel IDs. */
    const val CHANNEL_ID_HASH_BYTE_COUNT: Int = 4
    /** Geohash precision (character count). 4 chars ≈ 20×20 km cell. */
    const val GEOHASH_LENGTH: Int = 4
    /** Base-32 alphabet for geohash encoding. MUST match TS/Rust derivation. */
    const val GEOHASH_ALPHABET: String = "0123456789bcdefghjkmnpqrstuvwxyz"
    /** Max inbox entries before eviction; ~48 MB proxy at 1.6 KB average bundle (roadmap §B.3). */
    const val MESH_INBOX_MAX_ENTRIES: Int = 30000
    /** Max spool entries before eviction; same 50 MB proxy as inbox. */
    const val MESH_SPOOL_MAX_ENTRIES: Int = 30000
}
