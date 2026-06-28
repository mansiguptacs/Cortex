package com.echowalk.places

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * File-backed [PlaceStore] that survives process/app restarts — this is what proves milestone
 * **M-C1** ("enroll 3 landmarks, kill and relaunch, they're still there") on the JVM with **zero
 * Android/Room dependency**. The Android app drops in a Room/SQLite implementation behind the same
 * [PlaceStore] contract later; this class pins down the persistence semantics Room must honor.
 *
 * Implementation: state lives in a delegated [InMemoryPlaceStore]; after every mutation the whole
 * snapshot is rewritten to [file]. The data set is tiny (a few hundred vectors at most), so a full
 * rewrite is simpler and safer than incremental updates. On construction the snapshot is replayed
 * in landmark capture order, which deterministically reproduces each landmark's per-place
 * `orderIndex` (and therefore the default route sequence).
 *
 * Binary format (big-endian via [DataOutputStream]), versioned for forward safety:
 * ```
 * int     MAGIC
 * int     VERSION
 * int     placeCount     -> { UTF id, UTF name }
 * int     landmarkCount  -> { UTF placeId, UTF label,
 *                             int embCount -> { int byteLen, bytes (LE float[] via EmbeddingCodec) },
 *                             boolean hasHeading, [float headingDeg] }
 * ```
 * Landmarks are written sorted by id so replay preserves capture order.
 */
class FilePlaceStore(
    private val file: File,
    private val backing: InMemoryPlaceStore = InMemoryPlaceStore(),
) : PlaceStore {

    init {
        if (file.exists() && file.length() > 0) load()
    }

    override fun createPlace(id: String, name: String): Place =
        backing.createPlace(id, name).also { save() }

    override fun getPlace(id: String): Place? = backing.getPlace(id)

    override fun places(): List<Place> = backing.places()

    override fun addLandmark(
        placeId: String,
        label: String,
        embeddings: List<FloatArray>,
        headingDeg: Float?,
    ): Landmark = backing.addLandmark(placeId, label, embeddings, headingDeg).also { save() }

    override fun landmarks(placeId: String): List<Landmark> = backing.landmarks(placeId)

    override fun destinations(placeId: String): List<String> = backing.destinations(placeId)

    override fun deletePlace(id: String) {
        backing.deletePlace(id)
        save()
    }

    override fun clear() {
        backing.clear()
        save()
    }

    // ---- persistence --------------------------------------------------------

    private fun save() {
        val places = backing.places()
        // Flatten across places and order by id so replay regenerates identical orderIndex values.
        val allLandmarks = places.flatMap { backing.landmarks(it.id) }.sortedBy { it.id }
        val tmp = File(file.absolutePath + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(places.size)
            for (p in places) {
                out.writeUTF(p.id)
                out.writeUTF(p.name)
            }
            out.writeInt(allLandmarks.size)
            for (lm in allLandmarks) {
                out.writeUTF(lm.placeId)
                out.writeUTF(lm.label)
                out.writeInt(lm.embeddings.size)
                for (emb in lm.embeddings) {
                    val bytes = EmbeddingCodec.encode(emb)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                val heading = lm.headingDeg
                if (heading != null) {
                    out.writeBoolean(true)
                    out.writeFloat(heading)
                } else {
                    out.writeBoolean(false)
                }
            }
        }
        // Atomic-ish replace so a crash mid-write can't corrupt an existing store.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun load() {
        DataInputStream(BufferedInputStream(file.inputStream())).use { inp ->
            val magic = inp.readInt()
            require(magic == MAGIC) { "not an EchoWalk place store (bad magic 0x${magic.toString(16)})" }
            val version = inp.readInt()
            require(version == VERSION) { "unsupported place store version $version (expected $VERSION)" }

            val placeCount = inp.readInt()
            repeat(placeCount) {
                val id = inp.readUTF()
                val name = inp.readUTF()
                backing.createPlace(id, name)
            }

            val landmarkCount = inp.readInt()
            repeat(landmarkCount) {
                val placeId = inp.readUTF()
                val label = inp.readUTF()
                val embCount = inp.readInt()
                val embeddings = ArrayList<FloatArray>(embCount)
                repeat(embCount) {
                    val len = inp.readInt()
                    val bytes = ByteArray(len)
                    inp.readFully(bytes)
                    embeddings += EmbeddingCodec.decode(bytes)
                }
                val heading = if (inp.readBoolean()) inp.readFloat() else null
                backing.addLandmark(placeId, label, embeddings, heading)
            }
        }
    }

    companion object {
        private const val MAGIC = 0x45575043 // 'E' 'W' 'P' 'C'
        private const val VERSION = 1
    }
}
