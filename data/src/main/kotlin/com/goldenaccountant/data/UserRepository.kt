package com.goldenaccountant.data

import android.util.Base64
import androidx.room.withTransaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ITERATIONS = 120_000
    fun hash(password: String): Pair<String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP) to Base64.encodeToString(derive(password, salt), Base64.NO_WRAP)
    }
    fun verify(password: String, saltText: String, expectedText: String): Boolean = try {
        MessageDigest.isEqual(derive(password, Base64.decode(saltText, Base64.NO_WRAP)), Base64.decode(expectedText, Base64.NO_WRAP))
    } catch (_: Exception) { false }
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

class UserRepository(private val db: AppDatabase) {
    suspend fun count(): Int = db.users().count()
    suspend fun all(): List<AppUserE> = db.users().all()
    suspend fun createUser(username: String, displayName: String, password: String, role: String): Long = db.withTransaction {
        val normalized = username.trim().lowercase(Locale.US)
        require(normalized.matches(Regex("[a-z0-9_.-]{3,40}"))) { "اسم الدخول يجب أن يكون 3 إلى 40 حرفاً إنجليزياً أو رقماً" }
        require(displayName.trim().isNotBlank()) { "الاسم الظاهر مطلوب" }
        require(password.length >= 6) { "كلمة المرور يجب ألا تقل عن 6 أحرف" }
        require(role in setOf("ADMIN", "MANAGER", "ACCOUNTANT", "CASHIER", "WAREHOUSE")) { "الدور غير معروف" }
        require(db.users().byUsername(normalized) == null) { "اسم الدخول مستخدم بالفعل" }
        val (salt, hash) = PasswordHasher.hash(password)
        val id = db.users().insert(AppUserE(username = normalized, displayName = displayName.trim(), passwordSalt = salt, passwordHash = hash, role = role))
        db.logAudit("CREATE", "user", id, "إنشاء مستخدم بالدور $role")
        id
    }
    suspend fun authenticate(username: String, password: String): AppUserE? {
        val user = db.users().byUsername(username.trim().lowercase(Locale.US)) ?: return null
        return user.takeIf { it.active && PasswordHasher.verify(password, it.passwordSalt, it.passwordHash) }
    }
    suspend fun setActive(id: Long, active: Boolean) = db.withTransaction {
        val user = db.users().byId(id) ?: throw IllegalArgumentException("المستخدم غير موجود")
        if (!active && user.role == "ADMIN") require(db.users().activeAdminCount() > 1) { "لا يمكن تعطيل آخر مدير نظام نشط" }
        db.users().update(user.copy(active = active))
        db.logAudit(if (active) "ENABLE" else "DISABLE", "user", id, "تغيير حالة المستخدم ${user.username}")
    }

    suspend fun setRole(id: Long, role: String) = db.withTransaction {
        require(role in setOf("ADMIN", "MANAGER", "ACCOUNTANT", "CASHIER", "WAREHOUSE")) { "الدور غير معروف" }
        val user = db.users().byId(id) ?: throw IllegalArgumentException("المستخدم غير موجود")
        if (user.active && user.role == "ADMIN" && role != "ADMIN") {
            require(db.users().activeAdminCount() > 1) { "لا يمكن تغيير دور آخر مدير نظام نشط" }
        }
        db.users().update(user.copy(role = role))
        db.logAudit("ROLE_CHANGE", "user", id, "تغيير دور ${user.username} من ${user.role} إلى $role")
    }

    suspend fun resetPassword(id: Long, newPassword: String) = db.withTransaction {
        require(newPassword.length >= 6) { "كلمة المرور يجب ألا تقل عن 6 أحرف" }
        val user = db.users().byId(id) ?: throw IllegalArgumentException("المستخدم غير موجود")
        val (salt, hash) = PasswordHasher.hash(newPassword)
        db.users().update(user.copy(passwordSalt = salt, passwordHash = hash))
        db.logAudit("PASSWORD_RESET", "user", id, "إعادة تعيين كلمة مرور المستخدم ${user.username}")
    }
}

suspend fun AppDatabase.logAudit(action: String, entityType: String, entityId: Long?, details: String) {
    val username = settings().get("session_username")?.takeIf { it.isNotBlank() }
    val actor = username?.let { users().byUsername(it) }
    auditLogs().insert(AuditLogE(action = action, entityType = entityType, entityId = entityId, details = details, actorUserId = actor?.id, actorName = actor?.displayName ?: username ?: "النظام"))
}
