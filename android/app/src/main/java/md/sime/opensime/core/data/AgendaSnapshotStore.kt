package md.sime.opensime.core.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.portal.VaultCipher

/** Encrypted, account-scoped snapshots with atomic replacement. */
class AgendaSnapshotStore(
    private val rootDirectory: File,
    private val cipher: VaultCipher,
    accountKey: String,
) {
    private val accountDirectory = File(rootDirectory, digest(accountKey))

    fun write(snapshot: StudentAgendaSnapshot) {
        val (iv, encrypted) = cipher.encrypt(SnapshotCodec.encode(snapshot))
        val payload = "${Base64.getEncoder().encodeToString(iv)}\n${Base64.getEncoder().encodeToString(encrypted)}"
        accountDirectory.mkdirs()
        val target = fileFor(snapshot.studentId)
        val temporary = File(accountDirectory, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            temporary.writeText(payload, Charsets.US_ASCII)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    fun read(studentId: StudentId): StudentAgendaSnapshot? = runCatching {
        val lines = fileFor(studentId).readLines(Charsets.US_ASCII)
        require(lines.size == 2)
        val plain = cipher.decrypt(
            iv = Base64.getDecoder().decode(lines[0]),
            cipherText = Base64.getDecoder().decode(lines[1]),
        )
        SnapshotCodec.decode(plain).verifyStudentOwnership(studentId)
    }.getOrNull()

    fun list(): List<StudentAgendaSnapshot> = accountDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "snapshot" }
        ?.mapNotNull { file ->
            runCatching {
                val lines = file.readLines(Charsets.US_ASCII)
                require(lines.size == 2)
                SnapshotCodec.decode(cipher.decrypt(
                    iv = Base64.getDecoder().decode(lines[0]),
                    cipherText = Base64.getDecoder().decode(lines[1]),
                ))
            }.getOrNull()
        }
        .orEmpty()

    fun clear() {
        accountDirectory.deleteRecursively()
    }

    private fun fileFor(studentId: StudentId): File = File(accountDirectory, "${digest(studentId.value)}.snapshot")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}