package com.goldenaccountant.app

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

object RolePolicy {
    private val roles = mapOf(
        "ADMIN" to setOf("sales", "purchases", "accounts", "vouchers", "journal", "inventory", "reports", "settings", "admin", "backup"),
        "MANAGER" to setOf("sales", "purchases", "accounts", "vouchers", "journal", "inventory", "reports", "settings", "backup"),
        "ACCOUNTANT" to setOf("sales", "purchases", "accounts", "vouchers", "journal", "reports", "settings"),
        "CASHIER" to setOf("sales", "vouchers"),
        "WAREHOUSE" to setOf("inventory"),
    )
    fun can(role: String?, permission: String): Boolean = role != null && permission in (roles[role] ?: emptySet())
    fun label(role: String): String = when (role) {
        "ADMIN" -> "مدير النظام"
        "MANAGER" -> "مدير"
        "ACCOUNTANT" -> "محاسب"
        "CASHIER" -> "أمين صندوق"
        "WAREHOUSE" -> "أمين مخزن"
        else -> role
    }
}

class UserSetupScreen(private val firstRun: Boolean) : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(a.header("إنشاء مدير النظام", null))
        val name = a.field("الاسم الظاهر")
        val username = a.field("اسم الدخول (إنجليزي)")
        val password = a.field("كلمة المرور").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val confirm = a.field("تأكيد كلمة المرور").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16)) }
        form.addView(a.text("أنشئ حساب مدير النظام. كلمة المرور لا تُخزّن كنص صريح.", 14f, MUTED))
        listOf(name, username, password, confirm).forEach { form.addView(it) }
        form.addView(a.pill("إنشاء الحساب وبدء الاستخدام") {
            if (password.text.toString() != confirm.text.toString()) return@pill a.toast("كلمتا المرور غير متطابقتين")
            a.lifecycleScope.launch {
                try {
                    val id = a.userRepository().createUser(username.text.toString(), name.text.toString(), password.text.toString(), "ADMIN")
                    val user = a.userRepository().all().first { it.id == id }
                    a.app.settings.put("session_username", user.username)
                    a.setCurrentUser(user)
                    a.setRoot(HomeScreen())
                } catch (e: Exception) { a.toast(e.message ?: "تعذر إنشاء المستخدم") }
            }
        }.apply { setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(18) })
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}

class LoginScreen : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(a.header("تسجيل الدخول", null))
        val username = a.field("اسم الدخول")
        val password = a.field("كلمة المرور").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(28), a.dp(20), a.dp(20)) }
        form.addView(a.text("المحاسب الذهبي", 24f, NAVY, true))
        form.addView(a.text("سجّل الدخول للمتابعة وفق صلاحيات حسابك.", 14f, MUTED))
        listOf(username, password).forEach { form.addView(it) }
        form.addView(a.pill("دخول") {
            a.lifecycleScope.launch {
                val user = a.userRepository().authenticate(username.text.toString(), password.text.toString())
                if (user == null) a.toast("اسم الدخول أو كلمة المرور غير صحيحة أو الحساب معطّل")
                else {
                    a.app.settings.put("session_username", user.username)
                    a.setCurrentUser(user)
                    a.setRoot(HomeScreen())
                }
            }
        }.apply { setPadding(a.dp(20), a.dp(14), a.dp(20), a.dp(14)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(18) })
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}

class UsersScreen : ListScreen("إدارة المستخدمين", listOf("الاسم", "اسم الدخول", "الدور", "الحالة"), { a ->
    a.choose("الدور الجديد", listOf("مدير النظام", "مدير", "محاسب", "أمين صندوق", "أمين مخزن")) { index ->
        val roles = listOf("ADMIN", "MANAGER", "ACCOUNTANT", "CASHIER", "WAREHOUSE")
        a.ask("مستخدم جديد", listOf("الاسم الظاهر", "اسم الدخول", "كلمة المرور"), setOf(2)) { values ->
            a.lifecycleScope.launch {
                try {
                    a.userRepository().createUser(values.getOrElse(1) { "" }, values.getOrElse(0) { "" }, values.getOrElse(2) { "" }, roles[index])
                    a.toast("تم إنشاء المستخدم")
                    a.refresh()
                } catch (e: Exception) { a.toast(e.message ?: "تعذر إنشاء المستخدم") }
            }
        }
    }
}) {
    override suspend fun load(a: MainActivity): List<Row> = a.userRepository().all().map { user ->
        Row(listOf(user.displayName, user.username, RolePolicy.label(user.role), if (user.active) "نشط" else "معطّل")) {
            if (user.id == a.currentUser?.id) a.toast("لا يمكن تغيير حالة حسابك أثناء تسجيل الدخول")
            else AlertDialog.Builder(a).setTitle(user.displayName)
                .setMessage(if (user.active) "تعطيل هذا المستخدم؟" else "إعادة تفعيل هذا المستخدم؟")
                .setPositiveButton(if (user.active) "تعطيل" else "تفعيل") { _, _ ->
                    a.lifecycleScope.launch {
                        try { a.userRepository().setActive(user.id, !user.active); a.refresh() }
                        catch (e: Exception) { a.toast(e.message ?: "تعذر تغيير الحالة") }
                    }
                }.setNegativeButton("إلغاء", null).show()
        }
    }
}

class AuditScreen : ListScreen("سجل التدقيق", listOf("الوقت", "المستخدم", "الإجراء", "الكيان", "التفاصيل")) {
    override suspend fun load(a: MainActivity): List<Row> = a.app.db.auditLogs().recent(500, 0).map { log ->
        Row(listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(log.createdAt)),
            log.actorName, log.action, log.entityType + (log.entityId?.let { " #$it" } ?: ""), log.details,
        ))
    }
}
