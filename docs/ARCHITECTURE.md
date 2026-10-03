# البنية
UI (Fragments/Compose لاحقا) ← ViewModel ← UseCases ← Repository ← Room.
- core (Kotlin JVM نقي، قابل للاختبار بلا أندرويد): محاسبة، مخزون، شجرة الحسابات.
- data (Android library): Room Entities/DAOs/AppDatabase، المبالغ كعدد صحيح ×10000.
- app (قادم): الواجهة المطابقة للأصل، الأمان، الطباعة، النسخ الاحتياطي.
قاعدة: لا ترحيل بدون JournalEntry متوازن؛ الأرصدة تُحسب دائما من journal_lines.
