package com.goldenaccountant.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
private fun Context.roundedBackground(fill:Int, stroke:Int = DARK_GOLD, radius:Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }
private fun Context.rippleBackground(content: android.graphics.drawable.Drawable, rippleColor:Int = 0x330F172A): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

fun Context.text(s:String,size:Float=15f,color:Int=TEXT,bold:Boolean=false)=TextView(this).apply{
 text=s;textSize=size;setTextColor(color);textDirection=View.TEXT_DIRECTION_RTL;if(bold)setTypeface(typeface,Typeface.BOLD)
}
fun Context.header(title:String,onBack:(()->Unit)?,vararg actions:Pair<String,()->Unit>)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;background=gradient();minimumHeight=dp(56)
 if(onBack!=null)addView(text("‹",30f,Color.WHITE).apply{gravity=Gravity.CENTER;contentDescription="رجوع";setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(52),dp(56)))
 addView(text(title,18f,Color.WHITE,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
 actions.forEach{(label,click)->
  addView(text(label,18f,NAVY,true).apply{
   gravity=Gravity.CENTER
   background=rippleBackground(roundedBackground(GOLD, DARK_GOLD, 11))
   setPadding(dp(5),dp(5),dp(5),dp(5))
   minWidth=dp(46); minHeight=dp(42)
   elevation=dp(2).toFloat()
   isClickable=true
   setOnClickListener{click()}
  },LinearLayout.LayoutParams(dp(46),dp(42)).apply{marginStart=dp(3);marginEnd=dp(3)})
 }
}
fun Context.footer(onAdd: (() -> Unit)?,label:String="")=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setBackgroundColor(NAVY);minimumHeight=dp(58)
 addView(text(label,13f,Color.WHITE).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
 if(onAdd!=null)addView(text("+",27f,NAVY,true).apply{
  gravity=Gravity.CENTER
  background=rippleBackground(GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(GOLD)})
  elevation=dp(2).toFloat()
  isClickable=true
  setOnClickListener{onAdd()}
 },LinearLayout.LayoutParams(dp(44),dp(44)).apply{marginEnd=dp(12)})
}
fun Context.pill(label:String,onClick:()->Unit)=text(label,14f,NAVY,true).apply{
 gravity=Gravity.CENTER
 minHeight=dp(46)
 setPadding(dp(18),dp(12),dp(18),dp(12))
 background=rippleBackground(roundedBackground(GOLD, DARK_GOLD, 12))
 isClickable=true
 isFocusable=true
 elevation=dp(2).toFloat()
 setOnClickListener{onClick()}
}
fun Context.card(title:String,value:String,accent:Int=GOLD,onClick:(()->Unit)?=null)=LinearLayout(this).apply{
 orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(12),dp(14),dp(12),dp(14));background=GradientDrawable().apply{cornerRadius=dp(14).toFloat();setColor(SURFACE);setStroke(dp(1),BORDER)}
 addView(text(title,13f,MUTED,true));addView(text(value,19f,TEXT,true).apply{setPadding(0,dp(6),0,0)});if(onClick!=null){isClickable=true;isFocusable=true;setOnClickListener{onClick()}}
}
fun Context.rowOf(vararg cols:String,header:Boolean=false)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(10),dp(if(header)8 else 12),dp(10),dp(if(header)8 else 12));if(header)setBackgroundColor(Color.parseColor("#F1F5F9"))
 cols.forEach{c->addView(text(c,if(header)13f else 14f,if(header)BLUE else TEXT,header).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))}
}
fun Context.field(hint:String,number:Boolean=false)=EditText(this).apply{
 this.hint=hint;textSize=15f;textDirection=View.TEXT_DIRECTION_RTL
 setPadding(dp(12),dp(10),dp(12),dp(10))
 background=GradientDrawable().apply{cornerRadius=dp(9).toFloat();setColor(SURFACE);setStroke(dp(1),BORDER)}
 if(number)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
}

/** Autocomplete adapter that searches inside names and tolerates common Arabic letter variants. */
private fun normalizeSuggestion(value:String):String = buildString {
    value.lowercase(Locale.ROOT).forEach { ch ->
        when (ch) {
            in '\u064B'..'\u065F', '\u0670', '\u0640' -> Unit // diacritics and tatweel
            'أ', 'إ', 'آ', 'ٱ' -> append('ا')
            'ى' -> append('ي')
            else -> append(ch)
        }
    }
}

fun Context.suggestionAdapter(values:List<String>):ArrayAdapter<String> =
    object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, values.distinct().sorted()) {
        private val allValues = values.distinct().sorted()
        private val nameFilter = object : Filter() {
            override fun performFiltering(constraint:CharSequence?):FilterResults {
                val query = normalizeSuggestion(constraint?.toString().orEmpty().trim())
                val matches = if (query.isEmpty()) allValues else allValues.filter {
                    normalizeSuggestion(it).contains(query)
                }
                return FilterResults().apply { this.values=matches; count=matches.size }
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint:CharSequence?, results:FilterResults?) {
                clear()
                val matches = results?.values as? List<String> ?: emptyList()
                addAll(matches)
                notifyDataSetChanged()
            }
            override fun convertResultToString(resultValue:Any?):CharSequence = resultValue?.toString().orEmpty()
        }
        override fun getFilter():Filter = nameFilter
        override fun getDropDownView(position:Int, convertView:View?, parent:ViewGroup):View {
            val view = super.getDropDownView(position, convertView, parent)
            (view as? TextView)?.apply {
                textDirection=View.TEXT_DIRECTION_RTL
                setTextColor(TEXT)
                textSize=15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight=dp(44)
            }
            return view
        }
    }

fun Context.toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
fun String.num():BigDecimal?=map{if(it in '٠'..'٩' )('0'.code+(it-'٠')).toChar() else it}.joinToString("").replace("٫",".").trim().toBigDecimalOrNull()
fun fmt(v:BigDecimal)=String.format(Locale.US,"%,.2f",v)
fun fmtMicro(m:Long)=fmt(BigDecimal.valueOf(m,4))
fun MainActivity.ask(title:String,hints:List<String>,numeric: Set<Int> = emptySet(),onOk:(List<String>)->Unit){
 val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val fields=hints.mapIndexed{i,h->field(h,i in numeric).also{box.addView(it)}}
 androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("حفظ"){_,_->onOk(fields.map{it.text.toString()})}.setNegativeButton("إلغاء",null).show()
}
fun MainActivity.choose(title:String,options:List<String>,onPick:(Int)->Unit){androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()){_,i->onPick(i)}.show()}
fun today()=java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(java.util.Date())
