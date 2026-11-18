# AVNIVO TARJIMON — SAQLANGAN ASOSIY HTML (2026-11-18)

## Qayerda?
- Repo: `royaltaxi963-gif/avnivo-sensor-test`
- Branch: `feat/offline-model-safe-installer-20261117`
- O'zgarmas nusxa: `snapshots/AVNIVO_TRANSLATOR_LATEST_2026-11-18.html`
- Manba fayl: `onboarding-tutorial-preview.html`
- Nusxa yaratilgan commit: `396b228b021da773684bf0d228e4d3905c5917fc`
- Manba HTML blob SHA: `400ca8941ac6192f070f8eedae8e5f18c885f6ef`

## Boshqa chatda davom ettirish
Avval aynan yuqoridagi snapshot faylni GitHub'dan o'qing. Keyin joriy branch bilan farqlarni tekshiring. Snapshotni o'zgartirmang, yangi ishlarni ishchi branchda bajaring. Oldingi ishlarni takrorlamang.

## Muhim
Bu HTML ichida online/offline sozlamalar, 100 tillik interfeys integratsiyasi, onboarding va xavfsiz model installer interfeysi mavjud. HTML `offline-uzbek-download.js` va `offline-model-installer.js` kabi tashqi fayllarni chaqiradi; shuning uchun snapshotning o'zi to'liq offline APK emas. 97 ta til paketi hozir tashqi manbadan olinadi. APK sinovida fayllarni APK assets ichiga yig'ish, WebView/IndexedDB, SHA-256, model runtime va real inference sinovlari talab etiladi. Model yuklanishi uning avtomatik ishlashini isbotlamaydi.

## Sinov APK maqsadi
Yuklab olish -> SHA-256 tekshirish -> saqlash -> runtime yuklash -> real STT/translation/TTS inference -> muvaffaqiyatli sinovdan keyingina READY holati. Mavjud UI dizaynini saqlang.
