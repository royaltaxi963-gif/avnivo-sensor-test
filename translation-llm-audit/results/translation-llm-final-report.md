# Google Cloud Translation LLM — 117 ta Chirp 2 tili bo'yicha yakuniy hisobot

Sana: 2026-11-16
Model: `general/translation-llm` · Region: `us-central1` · Loyiha: `project-6bcf7fe6-cb55-49b7-9f4`

Bu hisobot mustaqil test muhitida (`translation-llm-audit/`) haqiqiy Google Cloud
Translation LLM API so'rovlari asosida tayyorlandi. AVNIVO asosiy ilovasi,
HTML interfeysi, offline modeli va Chirp 2 ovoz tanish mexanizmi o'zgartirilmadi.

Manbalar:
* Tillar: https://cloud.google.com/translate/docs/languages
* Narx: https://cloud.google.com/translate/pricing

## 1. Umumiy raqamlar

| Ko'rsatkich | Qiymat |
|-------------|--------|
| Jami tekshirilgan variantlar | 117 |
| A — rasmiy qo'llab-quvvatlanadi | 77 |
| B — eksperimental | 13 |
| C — qo'llab-quvvatlanmaydi | 27 |
| D — aniqlanmadi | 0 |
| Haqiqiy API sinovidan o'tgan variantlar | 117 |
| Sifat sinovidan o'tgan variantlar | 93 |
| PASSED | 67 |
| EXPERIMENTAL | 13 |
| FAILED | 24 |
| RETEST_REQUIRED | 13 |
| Haqiqiy API so'rovlari soni | 6833 |
| Umumiy xarajat (USD) | 4.17072 |
| Qolgan budjet (USD) | 25.82928 |
| Qamrov | 100.0% |

Narx modeli: har 1 million kiruvchi belgi uchun $10 va har 1 million chiquvchi
belgi uchun $10. $25 da yangi pullik so'rovlar avtomatik to'xtatiladi; mutlaq
chegara $30.

## 2. PASSED (67)

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 6 | `az-AZ` | Azerbaijani (Azerbaijan) | `az` | official_support_and_quality_ok |
| 7 | `be-BY` | Belarusian (Belarus) | `be` | official_support_and_quality_ok |
| 8 | `bg-BG` | Bulgarian (Bulgaria) | `bg` | official_support_and_quality_ok |
| 9 | `bn-BD` | Bengali (Bangladesh) | `bn` | official_support_and_quality_ok |
| 10 | `bn-IN` | Bengali (India) | `bn-IN` | official_support_and_quality_ok |
| 11 | `bs-BA` | Bosnian (Bosnia and Herzegovina) | `bs` | official_support_and_quality_ok |
| 12 | `ca-ES` | Catalan (Spain) | `ca` | official_support_and_quality_ok |
| 16 | `cmn-Hant-TW` | Chinese, Mandarin (Traditional, Taiwan) | `zh-TW` | official_support_and_quality_ok |
| 17 | `cs-CZ` | Czech (Czech Republic) | `cs` | official_support_and_quality_ok |
| 18 | `cy-GB` | Welsh (United Kingdom) | `cy` | official_support_and_quality_ok |
| 19 | `da-DK` | Danish (Denmark) | `da` | official_support_and_quality_ok |
| 21 | `el-GR` | Greek (Greece) | `el` | official_support_and_quality_ok |
| 22 | `en-AU` | English (Australia) | `en-AU` | official_support_and_quality_ok |
| 23 | `en-GB` | English (United Kingdom) | `en-GB` | official_support_and_quality_ok |
| 24 | `en-IN` | English (India) | `en` | official_support_and_quality_ok |
| 25 | `en-US` | English (United States) | `en-US` | official_support_and_quality_ok |
| 26 | `es-419` | Spanish (Latin American) | `es-419` | official_support_and_quality_ok |
| 27 | `es-ES` | Spanish (Spain) | `es-ES` | official_support_and_quality_ok |
| 28 | `es-US` | Spanish (United States) | `es-US` | official_support_and_quality_ok |
| 29 | `et-EE` | Estonian (Estonia) | `et` | official_support_and_quality_ok |
| 31 | `fa-IR` | Persian (Iran) | `fa` | official_support_and_quality_ok |
| 33 | `fi-FI` | Finnish (Finland) | `fi` | official_support_and_quality_ok |
| 34 | `fil-PH` | Filipino (Philippines) | `fil` | official_support_and_quality_ok |
| 35 | `fr-CA` | French (Canada) | `fr-CA` | official_support_and_quality_ok |
| 36 | `fr-FR` | French (France) | `fr` | official_support_and_quality_ok |
| 38 | `gl-ES` | Galician (Spain) | `gl` | official_support_and_quality_ok |
| 39 | `gu-IN` | Gujarati (India) | `gu` | official_support_and_quality_ok |
| 42 | `hr-HR` | Croatian (Croatia) | `hr` | official_support_and_quality_ok |
| 43 | `hu-HU` | Hungarian (Hungary) | `hu` | official_support_and_quality_ok |
| 45 | `id-ID` | Indonesian (Indonesia) | `id` | official_support_and_quality_ok |
| 47 | `is-IS` | Icelandic (Iceland) | `is` | official_support_and_quality_ok |
| 48 | `it-IT` | Italian (Italy) | `it` | official_support_and_quality_ok |
| 49 | `iw-IL` | Hebrew (Israel) | `iw` | official_support_and_quality_ok |
| 52 | `ka-GE` | Georgian (Georgia) | `ka` | official_support_and_quality_ok |
| 56 | `km-KH` | Khmer (Cambodia) | `km` | official_support_and_quality_ok |
| 57 | `kn-IN` | Kannada (India) | `kn` | official_support_and_quality_ok |
| 59 | `ky-KG` | Kyrgyz (Cyrillic) | `ky` | official_support_and_quality_ok |
| 62 | `ln-CD` | Lingala (Congo-Kinshasa) | `ln` | official_support_and_quality_ok |
| 63 | `lo-LA` | Lao (Laos) | `lo` | official_support_and_quality_ok |
| 64 | `lt-LT` | Lithuanian (Lithuania) | `lt` | official_support_and_quality_ok |
| 66 | `lv-LV` | Latvian (Latvia) | `lv` | official_support_and_quality_ok |
| 68 | `mk-MK` | Macedonian (North Macedonia) | `mk` | official_support_and_quality_ok |
| 69 | `ml-IN` | Malayalam (India) | `ml` | official_support_and_quality_ok |
| 71 | `mr-IN` | Marathi (India) | `mr` | official_support_and_quality_ok |
| 72 | `ms-MY` | Malay (Malaysia) | `ms` | official_support_and_quality_ok |
| 74 | `my-MM` | Burmese (Myanmar) | `my` | official_support_and_quality_ok |
| 75 | `ne-NP` | Nepali (Nepal) | `ne` | official_support_and_quality_ok |
| 76 | `nl-NL` | Dutch (Netherlands) | `nl` | official_support_and_quality_ok |
| 77 | `no-NO` | Norwegian Bokmal (Norway) | `no` | official_support_and_quality_ok |
| 83 | `pa-Guru-IN` | Punjabi (Gurmukhi India) | `pa` | official_support_and_quality_ok |
| 84 | `pl-PL` | Polish (Poland) | `pl` | official_support_and_quality_ok |
| 87 | `pt-PT` | Portuguese (Portugal) | `pt-PT` | official_support_and_quality_ok |
| 88 | `ro-RO` | Romanian (Romania) | `ro` | official_support_and_quality_ok |
| 93 | `sk-SK` | Slovak (Slovakia) | `sk` | official_support_and_quality_ok |
| 94 | `sl-SI` | Slovenian (Slovenia) | `sl` | official_support_and_quality_ok |
| 97 | `sq-AL` | Albanian (Albania) | `sq` | official_support_and_quality_ok |
| 100 | `sv-SE` | Swedish (Sweden) | `sv` | official_support_and_quality_ok |
| 101 | `sw` | Swahili | `sw` | official_support_and_quality_ok |
| 102 | `sw-KE` | Swahili (Kenya) | `sw` | official_support_and_quality_ok |
| 103 | `ta-IN` | Tamil (India) | `ta` | official_support_and_quality_ok |
| 104 | `te-IN` | Telugu (India) | `te` | official_support_and_quality_ok |
| 106 | `th-TH` | Thai (Thailand) | `th` | official_support_and_quality_ok |
| 108 | `uk-UA` | Ukrainian (Ukraine) | `uk` | official_support_and_quality_ok |
| 110 | `ur-PK` | Urdu (Pakistan) | `ur` | official_support_and_quality_ok |
| 111 | `uz-UZ` | Uzbek (Uzbekistan) | `uz` | official_support_and_quality_ok |
| 112 | `vi-VN` | Vietnamese (Vietnam) | `vi` | official_support_and_quality_ok |
| 117 | `zu-ZA` | Zulu (South Africa) | `zu` | official_support_and_quality_ok |

## 3. EXPERIMENTAL (13)

Google hujjatida eksperimental deb belgilangan. Sinov natijasi qayd etildi,
lekin asosiy Online ro'yxatga avtomatik qo'shilmaydi.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 2 | `am-ET` | Amharic (Ethiopia) | `am` | google_experimental_support |
| 30 | `eu-ES` | Basque (Spain) | `eu` | google_experimental_support |
| 37 | `ga-IE` | Irish (Ireland) | `ga` | google_experimental_support |
| 40 | `ha-NG` | Hausa (Nigeria) | `ha` | google_experimental_support |
| 44 | `hy-AM` | Armenian (Armenia) | `hy` | google_experimental_support |
| 46 | `ig-NG` | Igbo (Nigeria) | `ig` | google_experimental_support |
| 60 | `lb-LU` | Luxembourgish (Luxembourg) | `lb` | google_experimental_support |
| 70 | `mn-MN` | Mongolian (Mongolia) | `mn` | google_experimental_support |
| 73 | `mt-MT` | Maltese (Malta) | `mt` | google_experimental_support |
| 82 | `or-IN` | Oriya (India) | `or` | google_experimental_support |
| 96 | `so-SO` | Somali | `so` | google_experimental_support |
| 98 | `sr-RS` | Serbian (Serbia) | `sr` | google_experimental_support |
| 105 | `tg-TJ` | Tajik (Tajikistan) | `tg` | google_experimental_support |

## 4. FAILED (24)

Model bu til kodini rad etdi yoki yetarli dalillar bilan jiddiy muammo aniqlandi.
Manba koddan **hech narsa o'chirilmadi**; faqat tavsiya ro'yxati tayyorlandi.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 4 | `as-IN` | Assamese (India) | `as` | api_rejected_unsupported_language |
| 5 | `ast-ES` | Asturian (Spain) | `ast` | api_rejected_unsupported_language |
| 13 | `ceb-PH` | Cebuano (Philippines) | `ceb` | api_rejected_unsupported_language |
| 14 | `ckb-IQ` | Central Kurdish (Iraq) | `ckb` | api_rejected_unsupported_language |
| 32 | `ff-SN` | Fulah (Senegal) | `ff` | api_rejected_unsupported_language |
| 51 | `jv-ID` | Javanese (Indonesia) | `jv` | api_rejected_unsupported_language |
| 53 | `kam-KE` | Kamba (Kenya) | `kam` | api_rejected_unsupported_language |
| 54 | `kea-CV` | Kabuverdianu (Cape Verde) | `kea` | api_rejected_unsupported_language |
| 55 | `kk-KZ` | Kazakh (Kazakhstan) | `kk` | api_rejected_unsupported_language |
| 61 | `lg-UG` | Ganda (Uganda) | `lg` | api_rejected_unsupported_language |
| 65 | `luo-KE` | Luo (Kenya) | `luo` | api_rejected_unsupported_language |
| 67 | `mi-NZ` | Maori (New Zealand) | `mi` | api_rejected_unsupported_language |
| 78 | `nso-ZA` | Sepedi (South Africa) | `nso` | api_rejected_unsupported_language |
| 79 | `ny-MW` | Nyanja (Malawi) | `ny` | api_rejected_unsupported_language |
| 80 | `oc-FR` | Occitan (France) | `oc` | api_rejected_unsupported_language |
| 81 | `om-ET` | Oromo (Ethiopia) | `om` | api_rejected_unsupported_language |
| 85 | `ps-AF` | Pashto | `ps` | api_rejected_unsupported_language |
| 90 | `rup-BG` | Aromanian (Bulgaria) | `rup` | api_rejected_unsupported_language |
| 91 | `sd-IN` | Sindhi (India) | `sd` | api_rejected_unsupported_language |
| 95 | `sn-ZW` | Shona (Zimbabwe) | `sn` | api_rejected_unsupported_language |
| 99 | `su-ID` | Sundanese (Indonesia) | `su` | api_rejected_unsupported_language |
| 109 | `umb-AO` | Umbundu (Angola) | `umb` | api_rejected_unsupported_language |
| 113 | `wo-SN` | Wolof (Senegal) | `wo` | api_rejected_unsupported_language |
| 116 | `yue-Hant-HK` | Chinese, Cantonese (Traditional Hong Kong) | `yue` | api_rejected_unsupported_language |

## 5. RETEST_REQUIRED (13)

Vaqtinchalik API muammosi, noaniq natija yoki yetarli sifat dalili yo'q.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 1 | `af-ZA` | Afrikaans (South Africa) | `af` | quality_signals_need_review |
| 3 | `ar-EG` | Arabic (Egypt) | `ar` | quality_signals_need_review |
| 15 | `cmn-Hans-CN` | Chinese (Simplified, China) | `zh` | quality_signals_need_review |
| 20 | `de-DE` | German (Germany) | `de` | quality_signals_need_review |
| 41 | `hi-IN` | Hindi (India) | `hi` | quality_signals_need_review |
| 50 | `ja-JP` | Japanese (Japan) | `ja` | quality_signals_need_review |
| 58 | `ko-KR` | Korean (South Korea) | `ko` | quality_signals_need_review |
| 86 | `pt-BR` | Portuguese (Brazil) | `pt-BR` | quality_signals_need_review |
| 89 | `ru-RU` | Russian (Russia) | `ru` | quality_signals_need_review |
| 92 | `si-LK` | Sinhala (Sri Lanka) | `si` | undocumented_but_api_accepts |
| 107 | `tr-TR` | Turkish (Turkey) | `tr` | quality_signals_need_review |
| 114 | `xh-ZA` | Xhosa (South Africa) | `xh` | undocumented_but_api_accepts |
| 115 | `yo-NG` | Yoruba (Nigeria) | `yo` | undocumented_but_api_accepts |

## 5b. Hujjat va API o'rtasidagi ziddiyat (3)

Quyidagi tillar Google'ning rasmiy Translation LLM jadvalida **yo'q**, lekin
haqiqiy API ularni qabul qildi va to'g'ri yozuv tizimida tarjima qildi. Shuning
uchun ular avtomatik ravishda Online ro'yxatga qo'shilmaydi (RETEST_REQUIRED),
ammo bu holat alohida qayd etildi.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 92 | `si-LK` | Sinhala (Sri Lanka) | `si` | undocumented_but_api_accepts |
| 114 | `xh-ZA` | Xhosa (South Africa) | `xh` | undocumented_but_api_accepts |
| 115 | `yo-NG` | Yoruba (Nigeria) | `yo` | undocumented_but_api_accepts |

## 6. Metodologiya va cheklovlar

* Har bir til uchun o'zbekchadan va inglizchadan tarjima, hamda teskarisi sinovdan o'tkazildi.
* Sifat bahosi skript (yozuv tizimi), sonlar/sanalar, bo'sh natija va barqarorlik
  bo'yicha avtomatik signalga asoslanadi. Insoniy etalon barcha 117 til uchun
  mavjud emas, shu sababli avtomatik metrikalar inson bahosi o'rnini bosmaydi.
* `FAILED` faqat API aniq rad etganda (HTTP 400/404) yoki jiddiy sifat signali
  bo'lganda beriladi; vaqtinchalik 429/5xx xatolar qo'llab-quvvatlanmaydi deb
  hisoblanmaydi.
* Test natijalari taxminiy emas: har bir yozuv haqiqiy HTTP javobidan olingan.

## 7. Online ro'yxat tavsiyasi

Asosiy Online ro'yxatga **faqat PASSED** tillarni kiritish tavsiya etiladi.
EXPERIMENTAL tillar alohida saqlanadi. FAILED tillar ro'yxatdan chiqarish uchun
`translation-llm-online-removal-candidates.json` faylida keltirilgan.
