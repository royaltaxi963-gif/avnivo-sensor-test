# AVNIVO — 100 til interfeys auditi

Audit source: `feat/offline-model-safe-installer-20261117/onboarding-tutorial-preview.html`
PR #22: https://github.com/royaltaxi963-gif/avnivo-sensor-test/pull/22

## Tasdiqlangan topilmalar
- PR #22 source/en.json: 179 keys; tutorial keys cover five steps (history, settings, microphone, online, offline) and tutorial.next.
- Current HTML has six tutorial steps. New sixth step `Tillarni tanlash` has no matching keys in PR #22.
- Current HTML uses hard-coded Uzbek text and lacks an integrated i18n initialization in this single-file entrypoint (the PR's index.html integration cannot automatically cover this HTML).
- PR #22 descriptions mark non-English locale packages as machine translated, not human reviewed.
- A complete 100-language release needs both dictionary coverage and runtime binding verification; neither is established by dictionary key counts alone.

## Current six tutorial stages
1. **Chatlar tarixi** — selector `.history-toggle`
   - Bu — tarjima suhbatlaringiz tarixi. AVNIVO bilan oldin yozishgan xabarlaringiz va tarjima chatlaringiz shu bo‘limda saqlanadi. Kerakli suhbatni topib, avvalgi xabarlarni qayta ko‘rishingiz, tarjimalarni tekshirishingiz yoki o‘sha suhbatni davom ettirishingiz mumkin. Yangi suhbat boshlash uchun ham tarix bo‘limidagi tegishli tugmadan foydalanasiz.
2. **Sozlamalar** — selector `.settings-toggle`
   - Sozlamalar bo‘limida tarjimonning ishlash usulini o‘zingizga moslashtirasiz. Online rejimda ovoz chiqarib o‘qish, mavjud ovozlar, o‘qish ohangi va uslubi kabi imkoniyatlarni boshqarish mumkin. Offline rejimda esa qurilmaga yuklanadigan ovoz tanish, tarjima va ovozli o‘qish modullarini hamda ularning holatini ko‘rishingiz mumkin. Ayrim imkoniyatlar tanlangan rejim va o‘rnatilgan modullarga bog‘liq.
3. **Mikrofon** — selector `.mic-icon`
   - Mikrofon tugmasi orqali gapirib tarjima qilishni boshlaysiz. Avval kerakli tillarni tanlang, so‘ng mikrofonni bosing va odatdagidek gapiring. Ilova tanlangan rejim imkoniyatlariga qarab nutqni taniydi va tarjima qilishga yordam beradi. Mikrofon uchun brauzer ruxsati talab qilinishi mumkin.
4. **Online rejim** — selector `#mode-online`
   - Bu tugma internet orqali ishlaydigan tarjimon rejimini tanlaydi. Online rejimda mavjud bulutli ovoz tanish va tarjima xizmatlaridan foydalanish mumkin. Internet aloqasi va tegishli xizmatlarning mavjudligi zarur. Hozir ilova Online rejimida ochilgan.
5. **Offline rejim** — selector `#mode-offline`
   - Bu tugma internetsiz ishlash uchun mo‘ljallangan rejimni tanlaydi. Offline imkoniyatlardan foydalanish uchun kerakli til va ovoz modullari qurilmaga oldindan yuklangan bo‘lishi lozim. Qo‘llab-quvvatlanadigan tillar va funksiyalar o‘rnatilgan modullarga bog‘liq.
6. **Tillarni tanlash** — selector `.language-row`
   - Pastdagi ikki til bo‘limidan qaysi tilni tanlasangiz, yozilgan yoki ovozdan matnga aylantirilgan gap o‘sha tilniki sifatida chatga yuboriladi. Tilni almashtirmoqchi bo‘lsangiz, til nomi ustiga biroz bosib turing — tillar ro‘yxati ochiladi. O‘rtadagi ikkita strelka tanlangan yo‘nalishni ko‘rsatadi.

## Extracted direct string assignments (static sample; dynamic concatenations, markup and templates need separate inspection)
1. Birinchi kirish
2. Til tanlash ro‘yxatini ochish
3. Mavjud tillar
4. AVNIVO fon
5. Tarjimon rejimi
6. Online rejimi
7. Offline rejimi
8. Chatlar tarixini ochish
9. Sozlamalarni ochish
10. Sozlamalar
11. Sozlamalarni yopish
12. Offline sozlamalar
13. Offline modullar
14. Faol offline modullar
15. Chatlar tarixi
16. Tarixni yopish
17. AVNIVO Tarjimon
18. Yuborilgan xabarlar
19. Tarjima tillari
20. O‘zbek tilini tanlash
21. Rus tilini tanlash
22. Online tillar
23. Yopish
24. Til nomi yoki kodini qidiring
25. Til qidirish
26. Matn yozish
27. Mikrofonni yoqish
28. O‘zbek tili
29. Русский язык
30. Yuklanmagan
31. Yuklash jarayoni
32. Yuklash moduli ochilmadi. Sahifani yangilang.
33. Haqiqiy yuklash boshlanmoqda. Internet uzilsa qayta bosib davom ettiring.
34. Yuklangan · dvigatel ulanmagan
35. Fayllar saqlandi va SHA-256 tasdiqlandi. Sherpa-ONNX ijro mexanizmi ulanmaguncha faol emas.
36. Yuklash to‘xtadi: 
37. Xavfsiz yuklash dasturi ochilmadi
38. Model uchun xavfsiz yuklash ruxsati tekshirilmoqda…
39. Yuklangan · dvigatel kutilmoqda
40. Hozircha saqlangan chatlar yo‘q. Yangi xabar yuborilganda shu yerda sana bilan paydo bo‘ladi.
41. O‘zbek tili = русский язык
42. Saqlangan: 
43. AI ovozi (Online)
44. O‘qish tezligi (Online)
45. Ovoz kuchi (Online)
46. Maxsus uslub tavsifi (180 belgigacha)
47. Masalan: warm and enthusiastic
48. 30 ta tayyor ovoz. Erkak/ayol/yosh ovoz xarakteri ovoz tanlovi bilan belgilanadi; uslub sozlamalari ovozning doimiy kimligini o‘zgartirmaydi. Kengaytirilgan ovozlar uchun alohida Voices API kerak.
49. Gemini 3.8 Flash TTS — online. Ovoz va hissiy uslubni tanlang.
50. 🔊 Ovoz sinovi
51. Gemini ovozi tayyorlanmoqda…
52. Ovoz ijro etilmoqda
53. Ovoz xatosi: 
54. Ovoz sinoviga tayyor
55. Ovoz sinovi faqat online rejimda
56. Salom! AVNIVO ovoz sinovi.
57. Tarjima uchun boshqa tilni tanlang
58. Tarjima qilinmoqda…

## Missing work before release
1. Add `tutorial.languages.title`, `tutorial.languages.body`, `tutorial.finish` to canonical source and all 100 locale dictionaries; validate placeholders and scripts.
2. Bind all six tutorial stages, title/body/button, to the selected interface language; preserve original first-launch animations and spotlight.
3. Inventory all static HTML text, ARIA labels, placeholders, and JS-generated messages, including online/offline status, voice/STT, history, TTS, model installer, settings and errors.
4. Map every visible string to a stable key and supply translations in all 100 packages, retaining machine-translation quality flags.
5. For the single-file Chrome HTML, bundle translations inline or use a verified local offline asset strategy. External `fetch('i18n/...')` alone does not guarantee standalone file operation.
6. Run 100-locale key/placeholder validation, DOM smoke tests, RTL layout tests, offline launch tests, first-run tutorial tests and Android Chrome tests.

## Audit limitations
- This is a source inspection, not a device/browser acceptance test.
- The extracted assignment list is not an exhaustive UI inventory: HTML nodes, template literals, runtime messages and imported modules require further parsing.
- No translations are certified as linguistically reviewed.