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
