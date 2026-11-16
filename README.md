# AVNIVO Tarjimon — asosiy ilova

**ASOSIY ILOVA / MAIN APP:** [index.html](./index.html) (GitHub `main` tarmog‘i).

**Chrome’da ochish:** https://raw.githack.com/royaltaxi963-gif/avnivo-sensor-test/main/index.html

**GitHub Actions tekshiruvlari:** [AVNIVO main app validation](https://github.com/royaltaxi963-gif/avnivo-sensor-test/actions/workflows/avnivo-main-app.yml)

## Yangi chatda loyihani davom ettirish

- Repozitoriy: `royaltaxi963-gif/avnivo-sensor-test`
- Asosiy ilova: `main:index.html` — **shu faylni birinchi oching va tekshiring**.
- GitHub Actions workflow: `.github/workflows/avnivo-main-app.yml`.
- **Tasdiqlangan asosiy interfeys:** `main/index.html`, Online/Offline ranglari va boshqaruvi bilan, commit `842b37a1bdf0397e93cac547c9123cc0e453f21a`. Eski preview versiyalari asosiy ilova emas.
- Tarixiy HTML fayllar arxiv/oldingi versiyalar sifatida qoladi. Yangi chatda eski preview’ni asosiy ilova deb qabul qilmang.
- Funksiyalar: chat xabarlarining tilga qarab o‘ng/chap joylashishi, yozuv mashinkasi uslubidagi **sinov** tarjimasi, ovoz chiqarib o‘qish, tarix, o‘ngdan ochiladigan sozlamalar paneli, klaviatura va scroll holatini kuzatish.
- **Eslatma:** tarjima hozircha `Tarjima sinov so‘zi` yozuvini ko‘rsatadi, haqiqiy tarjima API emas. Actions statik HTML/JS tekshiradi; Android Chrome’dagi amaliy sinovni almashtirmaydi.

## Ishlash tartibi

Yangi o‘zgarishlar `main:index.html` asosida ishlab chiqiladi. GitHub Actions yashil bo‘lgandan keyin asosiy fayl yangilanadi. `index.html` ni boshqa eski ilova bilan almashtirmang.

## QAT’IY REJIMLAR QOIDASI — ONLINE / OFFLINE (2026-11-16)

**Foydalanuvchining aniq talabi; kelgusi barcha chat va o‘zgarishlarda amal qiladi.**

1. **ASOSIY ILOVA:** faqat `main/index.html`. Joriy tasdiqlangan UI bazasi: `842b37a1bdf0397e93cac547c9123cc0e453f21a`.
2. **UMUMIY ASOS:** hozir ikkala rejimda mavjud bo‘lgan barcha funksiyalar, elementlar va ko‘rinishlar saqlanadi: chat, mikrofon, til tanlash, tarjima sinovi, ovozli o‘qish, tarix, sozlamalar, Wi-Fi/No Wi-Fi rejim tanlash, klaviatura va chat harakati. Har rejimning hozirgi alohida ranglari saqlanadi.
3. **ONLINE UCHUN** deyilsa, yangi funksiya/interfeys **faqat Online** rejimida ko‘rinsin va ishlasin; Offline rejimiga yangi element/funksiya qo‘shilmasin.
4. **OFFLINE UCHUN** deyilsa, yangi funksiya/interfeys **faqat Offline** rejimida ko‘rinsin va ishlasin; Online rejimiga yangi element/funksiya qo‘shilmasin.
5. **REJIM AYTILMAGAN BO‘LSA:** o‘zgartirishdan **oldin** foydalanuvchidan `Online yoki Offline?` deb so‘rash SHART. Mustaqil taxmin qilib biror rejimga qo‘shish yoki ikkala rejimga tarqatish TAQIQLANADI.
6. Rejimga xos yangi komponentlar tegishli rejimga shartli ko‘rsatilishi va rejim o‘zgarganda ikkinchi rejimdan yashirilishi kerak; faqat CSS rangi bilan farqlash yetarli emas. Rejimga xos xizmatlar ham boshqa rejimda avtomatik ishga tushmasin.
7. Boshqa mavjud funksiyalarni, saqlangan chatlarni, klaviatura va animatsiyalarni buzmasdan minimal o‘zgarish qiling. Rejimlar orasidagi o‘zgarishlar uchun alohida tekshiruvlar o‘tkazing.
8. Hozircha Online/Offline tugmalari vizual rejimni almashtiradi; real tarmoq/offline tarjima dvigatellari integratsiya qilingan deb da’vo qilmang.

**Kelgusi ish:** foydalanuvchi Online rejimi uchun yangi o‘zgarishlar so‘rashini bildirgan. Biroq har bir yangi topshiriqda rejim ko‘rsatilmagan bo‘lsa, baribir so‘rash kerak.

## Online Chirp 2 STT integratsiyasi (2026-11-16)

- `main/index.html` Online mikrofonini Google Chirp 2 candidate serveriga ulaydi: `https://candidate---avnivo-sensor-test-5xwrf7netq-ew.a.run.app/api/transcribe-google`.
- Online rejimida mikrofonni bir marta bosib yozishni boshlang, qayta bosib tugating; tanilgan matn yozish maydoniga tushadi. So‘rovda `audio/webm` va tanlangan til `uz-UZ` yoki `ru-RU` yuboriladi.
- Google API kaliti HTML ichida saqlanmaydi; Cloud Run xizmat akkaunti ishlatiladi. Har bir yozuv pullik STT so‘rovi bo‘lishi mumkin. API 429, 400, 503 va ulanish xatolari foydalanuvchiga bildiriladi.
- Offline rejimining avvalgi `SpeechRecognition` kodi saqlangan; Online rejimining yangi recorder/API chaqiruvlari Offline rejimida ishlamaydi.
- **Cheklovlar:** Chirp 2 backend 117 locale uchun sinovdan o‘tgan, lekin hozirgi asosiy HTML til tanlagichi faqat UZ/RU. 117 locale UI bilan hali ulanmagan. Candidate endpoint amalda Android Chrome’dan tekshirilmagan; 100% kafolat berilmaydi. Production trafik almashmagan.
- Integratsiya commit: `5d986dfb4d00708e8f7dce10483108f53861822f`.

## Online 93-locale picker + Translation LLM routing (2026-11-16)

- `main/index.html` Online rejimida har ikki til tugmasini **1100 ms** bosib turganda 93-locale ro‘yxatini ochadi. Oddiy bosish avvalgidek yo‘nalishni tanlaydi; Offline UI saqlanadi.
- 93 ta locale va Translation LLM kodi `feat/translation-llm-integration:translator-api/translation_languages.py` audit reyestridan olingan. Holatlar: **77 official, 13 experimental, 3 retest**. 24 unsupported kiritilmagan. `cmn-Hans-CN→zh`, `cmn-Hant-TW→zh-TW` alohida saqlanadi.
- Online matn yuborish `https://avnivo-translation-test-792624108019.europe-west1.run.app/api/translate` ga `{text,source:<Chirp2 locale>,target:<Chirp2 locale>}` yuboradi, javobdagi model/source/target kodlarini tekshiradi; muvaffaqiyatsizlikda soxta tarjima chiqarmaydi.
- **MUHIM BLOKER:** 2026-11-16 holatida production `translator-api/main.py` hali faqat 4 tilni qabul qiladi. 93 til backend kodi **PR #13** da, lekin u merge/deploy qilinmagan. Demak frontenddagi 93 til tanlanadi, ammo ularning real tarjimasi production yangilanmaguncha ishlashi tasdiqlanmagan. PR #2 STT registry konflikti ham hal qilinishi kerak. Frontend sintaksisi tekshirilgan, ammo mobil E2E yoki pullik 93 til tarjima sinovi o‘tkazilmagan.
- Integratsiya commitlari: `cb19a149d9d53dc3524b738893cc0caba2b5cd75` (picker), `fddf256286203fe10322ebc7c90c3819bffc2693` (translation routing).
