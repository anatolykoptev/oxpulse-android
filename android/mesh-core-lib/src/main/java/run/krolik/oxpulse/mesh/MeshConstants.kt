// Hand-written constants replaced by codegen — see mesh-constants.json.
// MeshConstants is kept as a stable facade; callers need not change.
package run.krolik.oxpulse.mesh

object MeshConstants {
    val SERVICE_UUID get() = MeshConstantsGenerated.SERVICE_UUID
    val RX_CHARACTERISTIC_UUID get() = MeshConstantsGenerated.RX_CHARACTERISTIC_UUID
    val TX_CHARACTERISTIC_UUID get() = MeshConstantsGenerated.TX_CHARACTERISTIC_UUID
    val FRAME_MAGIC get() = MeshConstantsGenerated.FRAME_MAGIC
    val MAX_FRAME_SIZE get() = MeshConstantsGenerated.MAX_FRAME_SIZE
}
