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
| Haqiqiy API sinovidan o'tgan variantlar | 4 |
| PASSED | 2 |
| EXPERIMENTAL | 1 |
| FAILED | 1 |
| RETEST_REQUIRED | 113 |
| Haqiqiy API so'rovlari soni | 120 |
| Umumiy xarajat (USD) | 0.06077 |
| Qolgan budjet (USD) | 29.93923 |
| Qamrov | 3.4% |

Narx modeli: har 1 million kiruvchi belgi uchun $10 va har 1 million chiquvchi
belgi uchun $10. $25 da yangi pullik so'rovlar avtomatik to'xtatiladi; mutlaq
chegara $30.

## 2. PASSED (2)

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 3 | `ar-EG` | Arabic (Egypt) | `ar` | official_support_and_quality_ok |
| 15 | `cmn-Hans-CN` | Chinese (Simplified, China) | `zh` | official_support_and_quality_ok |

## 3. EXPERIMENTAL (1)

Google hujjatida eksperimental deb belgilangan. Sinov natijasi qayd etildi,
lekin asosiy Online ro'yxatga avtomatik qo'shilmaydi.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 2 | `am-ET` | Amharic (Ethiopia) | `am` | google_experimental_support |

## 4. FAILED (1)

Model bu til kodini rad etdi yoki yetarli dalillar bilan jiddiy muammo aniqlandi.
Manba koddan **hech narsa o'chirilmadi**; faqat tavsiya ro'yxati tayyorlandi.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 4 | `as-IN` | Assamese (India) | `as` | api_rejected_unsupported_language |

## 5. RETEST_REQUIRED (113)

Vaqtinchalik API muammosi, noaniq natija yoki yetarli sifat dalili yo'q.

| # | Chirp 2 | Til | LLM kod | Sabab |
|---|---------|-----|---------|-------|
| 1 | `af-ZA` | Afrikaans (South Africa) | `af` | not_yet_tested |
| 5 | `ast-ES` | Asturian (Spain) | `ast` | not_yet_tested |
| 6 | `az-AZ` | Azerbaijani (Azerbaijan) | `az` | not_yet_tested |
| 7 | `be-BY` | Belarusian (Belarus) | `be` | not_yet_tested |
| 8 | `bg-BG` | Bulgarian (Bulgaria) | `bg` | not_yet_tested |
| 9 | `bn-BD` | Bengali (Bangladesh) | `bn` | not_yet_tested |
| 10 | `bn-IN` | Bengali (India) | `bn-IN` | not_yet_tested |
| 11 | `bs-BA` | Bosnian (Bosnia and Herzegovina) | `bs` | not_yet_tested |
| 12 | `ca-ES` | Catalan (Spain) | `ca` | not_yet_tested |
| 13 | `ceb-PH` | Cebuano (Philippines) | `ceb` | not_yet_tested |
| 14 | `ckb-IQ` | Central Kurdish (Iraq) | `ckb` | not_yet_tested |
| 16 | `cmn-Hant-TW` | Chinese, Mandarin (Traditional, Taiwan) | `zh-TW` | not_yet_tested |
| 17 | `cs-CZ` | Czech (Czech Republic) | `cs` | not_yet_tested |
| 18 | `cy-GB` | Welsh (United Kingdom) | `cy` | not_yet_tested |
| 19 | `da-DK` | Danish (Denmark) | `da` | not_yet_tested |
| 20 | `de-DE` | German (Germany) | `de` | not_yet_tested |
| 21 | `el-GR` | Greek (Greece) | `el` | not_yet_tested |
| 22 | `en-AU` | English (Australia) | `en-AU` | not_yet_tested |
| 23 | `en-GB` | English (United Kingdom) | `en-GB` | not_yet_tested |
| 24 | `en-IN` | English (India) | `en` | not_yet_tested |
| 25 | `en-US` | English (United States) | `en-US` | not_yet_tested |
| 26 | `es-419` | Spanish (Latin American) | `es-419` | not_yet_tested |
| 27 | `es-ES` | Spanish (Spain) | `es-ES` | not_yet_tested |
| 28 | `es-US` | Spanish (United States) | `es-US` | not_yet_tested |
| 29 | `et-EE` | Estonian (Estonia) | `et` | not_yet_tested |
| 30 | `eu-ES` | Basque (Spain) | `eu` | not_yet_tested |
| 31 | `fa-IR` | Persian (Iran) | `fa` | not_yet_tested |
| 32 | `ff-SN` | Fulah (Senegal) | `ff` | not_yet_tested |
| 33 | `fi-FI` | Finnish (Finland) | `fi` | not_yet_tested |
| 34 | `fil-PH` | Filipino (Philippines) | `fil` | not_yet_tested |
| 35 | `fr-CA` | French (Canada) | `fr-CA` | not_yet_tested |
| 36 | `fr-FR` | French (France) | `fr` | not_yet_tested |
| 37 | `ga-IE` | Irish (Ireland) | `ga` | not_yet_tested |
| 38 | `gl-ES` | Galician (Spain) | `gl` | not_yet_tested |
| 39 | `gu-IN` | Gujarati (India) | `gu` | not_yet_tested |
| 40 | `ha-NG` | Hausa (Nigeria) | `ha` | not_yet_tested |
| 41 | `hi-IN` | Hindi (India) | `hi` | not_yet_tested |
| 42 | `hr-HR` | Croatian (Croatia) | `hr` | not_yet_tested |
| 43 | `hu-HU` | Hungarian (Hungary) | `hu` | not_yet_tested |
| 44 | `hy-AM` | Armenian (Armenia) | `hy` | not_yet_tested |
| 45 | `id-ID` | Indonesian (Indonesia) | `id` | not_yet_tested |
| 46 | `ig-NG` | Igbo (Nigeria) | `ig` | not_yet_tested |
| 47 | `is-IS` | Icelandic (Iceland) | `is` | not_yet_tested |
| 48 | `it-IT` | Italian (Italy) | `it` | not_yet_tested |
| 49 | `iw-IL` | Hebrew (Israel) | `iw` | not_yet_tested |
| 50 | `ja-JP` | Japanese (Japan) | `ja` | not_yet_tested |
| 51 | `jv-ID` | Javanese (Indonesia) | `jv` | not_yet_tested |
| 52 | `ka-GE` | Georgian (Georgia) | `ka` | not_yet_tested |
| 53 | `kam-KE` | Kamba (Kenya) | `kam` | not_yet_tested |
| 54 | `kea-CV` | Kabuverdianu (Cape Verde) | `kea` | not_yet_tested |
| 55 | `kk-KZ` | Kazakh (Kazakhstan) | `kk` | not_yet_tested |
| 56 | `km-KH` | Khmer (Cambodia) | `km` | not_yet_tested |
| 57 | `kn-IN` | Kannada (India) | `kn` | not_yet_tested |
| 58 | `ko-KR` | Korean (South Korea) | `ko` | not_yet_tested |
| 59 | `ky-KG` | Kyrgyz (Cyrillic) | `ky` | not_yet_tested |
| 60 | `lb-LU` | Luxembourgish (Luxembourg) | `lb` | not_yet_tested |
| 61 | `lg-UG` | Ganda (Uganda) | `lg` | not_yet_tested |
| 62 | `ln-CD` | Lingala (Congo-Kinshasa) | `ln` | not_yet_tested |
| 63 | `lo-LA` | Lao (Laos) | `lo` | not_yet_tested |
| 64 | `lt-LT` | Lithuanian (Lithuania) | `lt` | not_yet_tested |
| 65 | `luo-KE` | Luo (Kenya) | `luo` | not_yet_tested |
| 66 | `lv-LV` | Latvian (Latvia) | `lv` | not_yet_tested |
| 67 | `mi-NZ` | Maori (New Zealand) | `mi` | not_yet_tested |
| 68 | `mk-MK` | Macedonian (North Macedonia) | `mk` | not_yet_tested |
| 69 | `ml-IN` | Malayalam (India) | `ml` | not_yet_tested |
| 70 | `mn-MN` | Mongolian (Mongolia) | `mn` | not_yet_tested |
| 71 | `mr-IN` | Marathi (India) | `mr` | not_yet_tested |
| 72 | `ms-MY` | Malay (Malaysia) | `ms` | not_yet_tested |
| 73 | `mt-MT` | Maltese (Malta) | `mt` | not_yet_tested |
| 74 | `my-MM` | Burmese (Myanmar) | `my` | not_yet_tested |
| 75 | `ne-NP` | Nepali (Nepal) | `ne` | not_yet_tested |
| 76 | `nl-NL` | Dutch (Netherlands) | `nl` | not_yet_tested |
| 77 | `no-NO` | Norwegian Bokmal (Norway) | `no` | not_yet_tested |
| 78 | `nso-ZA` | Sepedi (South Africa) | `nso` | not_yet_tested |
| 79 | `ny-MW` | Nyanja (Malawi) | `ny` | not_yet_tested |
| 80 | `oc-FR` | Occitan (France) | `oc` | not_yet_tested |
| 81 | `om-ET` | Oromo (Ethiopia) | `om` | not_yet_tested |
| 82 | `or-IN` | Oriya (India) | `or` | not_yet_tested |
| 83 | `pa-Guru-IN` | Punjabi (Gurmukhi India) | `pa` | not_yet_tested |
| 84 | `pl-PL` | Polish (Poland) | `pl` | not_yet_tested |
| 85 | `ps-AF` | Pashto | `ps` | not_yet_tested |
| 86 | `pt-BR` | Portuguese (Brazil) | `pt-BR` | not_yet_tested |
| 87 | `pt-PT` | Portuguese (Portugal) | `pt-PT` | not_yet_tested |
| 88 | `ro-RO` | Romanian (Romania) | `ro` | not_yet_tested |
| 89 | `ru-RU` | Russian (Russia) | `ru` | not_yet_tested |
| 90 | `rup-BG` | Aromanian (Bulgaria) | `rup` | not_yet_tested |
| 91 | `sd-IN` | Sindhi (India) | `sd` | not_yet_tested |
| 92 | `si-LK` | Sinhala (Sri Lanka) | `si` | not_yet_tested |
| 93 | `sk-SK` | Slovak (Slovakia) | `sk` | not_yet_tested |
| 94 | `sl-SI` | Slovenian (Slovenia) | `sl` | not_yet_tested |
| 95 | `sn-ZW` | Shona (Zimbabwe) | `sn` | not_yet_tested |
| 96 | `so-SO` | Somali | `so` | not_yet_tested |
| 97 | `sq-AL` | Albanian (Albania) | `sq` | not_yet_tested |
| 98 | `sr-RS` | Serbian (Serbia) | `sr` | not_yet_tested |
| 99 | `su-ID` | Sundanese (Indonesia) | `su` | not_yet_tested |
| 100 | `sv-SE` | Swedish (Sweden) | `sv` | not_yet_tested |
| 101 | `sw` | Swahili | `sw` | not_yet_tested |
| 102 | `sw-KE` | Swahili (Kenya) | `sw` | not_yet_tested |
| 103 | `ta-IN` | Tamil (India) | `ta` | not_yet_tested |
| 104 | `te-IN` | Telugu (India) | `te` | not_yet_tested |
| 105 | `tg-TJ` | Tajik (Tajikistan) | `tg` | not_yet_tested |
| 106 | `th-TH` | Thai (Thailand) | `th` | not_yet_tested |
| 107 | `tr-TR` | Turkish (Turkey) | `tr` | not_yet_tested |
| 108 | `uk-UA` | Ukrainian (Ukraine) | `uk` | not_yet_tested |
| 109 | `umb-AO` | Umbundu (Angola) | `umb` | not_yet_tested |
| 110 | `ur-PK` | Urdu (Pakistan) | `ur` | not_yet_tested |
| 111 | `uz-UZ` | Uzbek (Uzbekistan) | `uz` | not_yet_tested |
| 112 | `vi-VN` | Vietnamese (Vietnam) | `vi` | not_yet_tested |
| 113 | `wo-SN` | Wolof (Senegal) | `wo` | not_yet_tested |
| 114 | `xh-ZA` | Xhosa (South Africa) | `xh` | not_yet_tested |
| 115 | `yo-NG` | Yoruba (Nigeria) | `yo` | not_yet_tested |
| 116 | `yue-Hant-HK` | Chinese, Cantonese (Traditional Hong Kong) | `yue` | not_yet_tested |
| 117 | `zu-ZA` | Zulu (South Africa) | `zu` | not_yet_tested |

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
