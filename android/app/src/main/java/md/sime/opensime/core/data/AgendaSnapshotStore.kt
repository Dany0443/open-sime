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
    private val deleteAccountDirectory: (File) -> Boolean = { it.deleteRecursively() },
) {
    val accountStorageId = digest(accountKey)
    private val accountDirectory = File(rootDirectory, accountStorageId)
    private val accountPrefix = "open-sime-account-v1:$accountStorageId\n"

    fun write(snapshot: StudentAgendaSnapshot) {
        val (iv, encrypted) = cipher.encrypt(accountPrefix.toByteArray(Charsets.UTF_8) + SnapshotCodec.encode(snapshot))
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
        decode(fileFor(studentId)).verifyStudentOwnership(studentId)
    }.getOrNull()

    private fun decode(file: File): StudentAgendaSnapshot {
        val lines = file.readLines(Charsets.US_ASCII)
        require(lines.size == 2)
        val plain = cipher.decrypt(
            iv = Base64.getDecoder().decode(lines[0]),
            cipherText = Base64.getDecoder().decode(lines[1]),
        )
        val prefix = accountPrefix.toByteArray(Charsets.UTF_8)
        require(plain.size > prefix.size && plain.copyOfRange(0, prefix.size).contentEquals(prefix))
        val snapshot = SnapshotCodec.decode(plain.copyOfRange(prefix.size, plain.size))
        require(file.name == fileFor(snapshot.studentId).name)
        return snapshot
    }

    fun list(): List<StudentAgendaSnapshot> = accountDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "snapshot" }
        ?.mapNotNull { file ->
            runCatching {
                decode(file)
            }.getOrNull()
        }
        .orEmpty()

    fun clear() {
        clearDirectory(accountDirectory, deleteAccountDirectory)
    }

    companion object {
        /** Retries only the account recorded by an interrupted explicit logout. */
        fun clearPendingLogout(rootDirectory: File, accountStorageId: String) {
            require(accountStorageId.matches(Regex("[a-f0-9]{64}")))
            clearDirectory(File(rootDirectory, accountStorageId)) { it.deleteRecursively() }
        }

        private fun clearDirectory(directory: File, delete: (File) -> Boolean) {
            if (directory.exists()) check(delete(directory)) { "Private cache cleanup failed" }
        }
    }

    private fun fileFor(studentId: StudentId): File = File(accountDirectory, "${digest(studentId.value)}.snapshot")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
