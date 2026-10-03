package com.goldenaccountant.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.goldenaccountant.core.accounting.InvoiceKind

class HomeScreen:Screen{
 private var drawerOpen=false
 private val quick=listOf("فاتورة بيع" to {a:MainActivity->a.push(InvoiceListScreen(InvoiceKind.SALE))},"فاتورة شراء" to {a:MainActivity->a.push(InvoiceListScreen(InvoiceKind.PURCHASE))},"الحسابات" to {a:MainActivity->a.push(AccountsScreen())},"قبض / صرف" to {a:MainActivity->a.push(VouchersScreen())},"المخزون" to {a:MainActivity->push(ItemsScreen())},"التقارير" to {a:MainActivity->push(ReportsHubScreen())})
 override fun build(a:MainActivity):View{
  val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
  root.addView(a.header("المحاسب الذهبي",null,"☰" to {drawerOpen=true;a.refresh()},"⚙" to {a.push(SettingsScreen())}))
  if(drawerOpen){root.addView(drawer(a),LinearLayout.LayoutParams(-1,0,1f));return root}
  val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(a.dp(14),a.dp(14),a.dp(14),a.dp(18))}
  body.addView(a.text("لوحة التحكم",20f,NAVY,true).apply{setPadding(a.dp(4),a.dp(4),0,a.dp(12))})
  val summary=GridLayout(a).apply{columnCount=2}
  listOf("المبيعات اليوم" to "0.00","المشتريات اليوم" to "0.00","الذمم المدينة" to "0.00","قيمة المخزون" to "0.00").forEach{(t,v)->summary.addView(a.card(t,v),GridLayout.LayoutParams().apply{width=0;columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(a.dp(5),a.dp(5),a.dp(5),a.dp(5))})}
  body.addView(summary);body.addView(a.text("العمليات السريعة",17f,NAVY,true).apply{setPadding(a.dp(4),a.dp(20),0,a.dp(10))})
  val actions=GridLayout(a).apply{columnCount=2};quick.forEach{(label,click)->actions.addView(a.pill(label){click(a)},GridLayout.LayoutParams().apply{width=0;columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(a.dp(5),a.dp(5),a.dp(5),a.dp(5))})};body.addView(actions)
  body.addView(a.text("القوائم المحاسبية",17f,NAVY,true).apply{setPadding(a.dp(4),a.dp(20),0,a.dp(10))})
  listOf("دليل الحسابات","حركة الصندوق","ميزان المراجعة","قائمة الدخل","المركز المالي","حركة الأصناف").forEach{name->body.addView(a.rowOf(name,"›").apply{setOnClickListener{when(name){"دليل الحسابات"->a.push(ChartScreen());"حركة الصندوق"->a.push(StatementScreen(-3));"ميزان المراجعة"->a.push(TrialBalanceScreen());"قائمة الدخل"->a.push(IncomeScreen());"المركز المالي"->a.push(BalanceSheetScreen());"حركة الأصناف"->a.push(StockReportScreen())}}})}
  root.addView(ScrollView(a).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f));root.addView(a.footer(null,"المخزن الرئيسي"));return root
 }
 private fun drawer(a:MainActivity):View{
  val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(SURFACE)}
  box.addView(a.text("المحاسب الذهبي",22f,Color.WHITE,true).apply{gravity=Gravity.CENTER;setPadding(0,a.dp(28),0,a.dp(28));background=a.goldGradient()})
  listOf("الرئيسية" to {a.back()},"المبيعات" to {a.push(InvoiceListScreen(InvoiceKind.SALE))},"المشتريات" to {a.push(InvoiceListScreen(InvoiceKind.PURCHASE))},"الحسابات" to {a.push(AccountsScreen())},"المخزون" to {a.push(ItemsScreen())},"التقارير" to {a.push(ReportsHubScreen())},"دليل الحسابات" to {a.push(ChartScreen())},"الإعدادات" to {a.push(SettingsScreen())}).forEach{(n,c)->box.addView(a.text(n,16f,TEXT,true).apply{setPadding(a.dp(22),a.dp(15),a.dp(22),a.dp(15));setOnClickListener{drawerOpen=false;c()}})}
  box.addView(a.text("حفظ نسخة احتياطية",15f,MUTED).apply{setPadding(a.dp(22),a.dp(18),a.dp(22),a.dp(18))});box.addView(a.text("استرجاع قاعدة البيانات",15f,MUTED).apply{setPadding(a.dp(22),a.dp(18),a.dp(22),a.dp(18))})
  return ScrollView(a).apply{addView(box)}
 }
}
