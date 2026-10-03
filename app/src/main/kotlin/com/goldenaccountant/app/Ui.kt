package com.goldenaccountant.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.math.BigDecimal
import java.util.Locale

val GOLD=Color.parseColor("#D4AF37"); val DARK_GOLD=Color.parseColor("#A67C00")
val NAVY=Color.parseColor("#0F172A"); val BLUE=Color.parseColor("#1E40AF")
val BG=Color.parseColor("#F8FAFC"); val SURFACE=Color.WHITE
val SUCCESS=Color.parseColor("#059669"); val DANGER=Color.parseColor("#DC2626")
val WARNING=Color.parseColor("#D97706"); val TEXT=Color.parseColor("#1E293B")
val MUTED=Color.parseColor("#64748B"); val BORDER=Color.parseColor("#E2E8F0")
val B1=BLUE; val B2=DARK_GOLD; val LINK=BLUE; val LINE=BORDER

fun Context.dp(v:Int)= (v*resources.displayMetrics.density).toInt()
fun Context.gradient()=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(BLUE,NAVY))
fun Context.goldGradient()=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(DARK_GOLD,GOLD))
fun Context.text(s:String,size:Float=15f,color:Int=TEXT,bold:Boolean=false)=TextView(this).apply{
 text=s;textSize=size;setTextColor(color);textDirection=View.TEXT_DIRECTION_RTL;if(bold)setTypeface(typeface,Typeface.BOLD)
}
fun Context.header(title:String,onBack:(()->Unit)?,vararg actions:Pair<String,()->Unit>)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;background=gradient();minimumHeight=dp(56)
 if(onBack!=null)addView(text("‹",30f,Color.WHITE).apply{gravity=Gravity.CENTER;setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(52),dp(56)))
 addView(text(title,18f,Color.WHITE,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
 actions.forEach{(label,click)->addView(text(label,19f,Color.WHITE).apply{gravity=Gravity.CENTER;setOnClickListener{click()}},LinearLayout.LayoutParams(dp(52),dp(56)))}
}
fun Context.footer(onAdd:(()->Unit)?,label:String="")=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;background=NAVY;minimumHeight=dp(54)
 addView(text(label,13f,Color.WHITE).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
 if(onAdd!=null)addView(text("+",28f,NAVY,true).apply{gravity=Gravity.CENTER;background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(GOLD)};setOnClickListener{onAdd()}},LinearLayout.LayoutParams(dp(42),dp(42)).apply{marginEnd=dp(12)})
}
fun Context.pill(label:String,onClick:()->Unit)=text(label,14f,Color.WHITE,true).apply{
 gravity=Gravity.CENTER;setPadding(dp(14),dp(10),dp(14),dp(10));background=GradientDrawable().apply{cornerRadius=dp(10).toFloat();setColor(BLUE)};setOnClickListener{onClick()}
}
fun Context.card(title:String,value:String,accent:Int=GOLD,onClick:(()->Unit)?=null)=LinearLayout(this).apply{
 orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(12),dp(14),dp(12),dp(14));background=GradientDrawable().apply{cornerRadius=dp(14).toFloat();setColor(SURFACE);setStroke(dp(1),BORDER)}
 addView(text(title,13f,MUTED,true));addView(text(value,19f,TEXT,true).apply{setPadding(0,dp(6),0,0)});if(onClick!=null)setOnClickListener{onClick()}
}
fun Context.rowOf(vararg cols:String,header:Boolean=false)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(10),dp(if(header)8 else 12),dp(10),dp(if(header)8 else 12));if(header)setBackgroundColor(Color.parseColor("#F1F5F9"))
 cols.forEach{c->addView(text(c,if(header)13f else 14f,if(header)BLUE else TEXT,header).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))}
}
fun Context.field(hint:String,number:Boolean=false)=EditText(this).apply{this.hint=hint;textSize=15f;textDirection=View.TEXT_DIRECTION_RTL;if(number)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL}
fun Context.toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
fun String.num():BigDecimal?=map{if(it in '٠'..'٩' )('0'.code+(it-'٠')).toChar() else it}.joinToString("").replace("٫",".").trim().toBigDecimalOrNull()
fun fmt(v:BigDecimal)=String.format(Locale.US,"%,.2f",v)
fun fmtMicro(m:Long)=fmt(BigDecimal.valueOf(m,4))
fun MainActivity.ask(title:String,hints:List<String>,numeric:Set<Int>=emptySet(),onOk:(List<String>)->Unit){
 val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val fields=hints.mapIndexed{i,h->field(h,i in numeric).also{box.addView(it)}}
 androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("حفظ"){_,_->onOk(fields.map{it.text.toString()})}.setNegativeButton("إلغاء",null).show()
}
fun MainActivity.choose(title:String,options:List<String>,onPick:(Int)->Unit){androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()){_,i->onPick(i)}.show()}
fun today()=java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(java.util.Date())
